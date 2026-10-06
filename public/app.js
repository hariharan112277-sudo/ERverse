/* ==========================================================================
   ERverse dashboard client
   - polls GET /api/dashboard every five seconds (Table 5.2)
   - every mutation is followed by an immediate refresh, so the 5 s interval
     never makes a staff action feel laggy (Week 9 review noted the latency)
   - local ticker keeps wait timers and ambulance ETAs moving between polls
   ========================================================================== */
'use strict';

const POLL_MS = 5000;
const RANK = { CRITICAL: 1, HIGH: 2, MEDIUM: 3, LOW: 4 };

let latest = null;
let historyFilter = 'all';
let renderedAt = Date.now();
let consecutiveFailures = 0;

const $ = (id) => document.getElementById(id);

/* ------------------------------------------------------------ utilities */

const esc = (value) => String(value === null || value === undefined ? '' : value)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

const clock = (iso) => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

const priorityChip = (priority) =>
  `<span class="chip chip-${esc(priority)}">${esc(priority)}</span>`;

const statusChip = (status) => {
  const label = { WAITING: 'Waiting', IN_TREATMENT: 'In treatment', DISCHARGED: 'Discharged' }[status] || status;
  const cls = { WAITING: 'chip-plain', IN_TREATMENT: 'chip-HIGH', DISCHARGED: 'chip-LOW' }[status] || 'chip-plain';
  return `<span class="chip ${cls}">${esc(label)}</span>`;
};

const vitalsLine = (p) => {
  const parts = [];
  if (p.heartRate) parts.push(`HR ${p.heartRate}`);
  if (p.spo2) parts.push(`SpO₂ ${p.spo2}%`);
  if (p.bloodPressure && p.bloodPressure !== '-') parts.push(`BP ${p.bloodPressure}`);
  return parts.length ? parts.join(' · ') : 'vitals not recorded';
};

function toast(kind, title, message) {
  const el = document.createElement('div');
  el.className = `toast ${kind}`;
  el.innerHTML = `<div class="t-title">${esc(title)}</div><div>${esc(message)}</div>`;
  $('toasts').appendChild(el);
  setTimeout(() => el.remove(), kind === 'err' ? 6500 : 4200);
}

async function api(method, url, body) {
  const response = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  let payload = {};
  try {
    payload = await response.json();
  } catch (err) {
    payload = { message: response.statusText };
  }
  if (!response.ok) {
    const error = new Error(payload.message || `HTTP ${response.status}`);
    error.code = payload.error;
    throw error;
  }
  return payload;
}

/* ----------------------------------------------------------- rendering */

function renderMetrics(metrics) {
  const bedPercent = metrics.bedsTotal ? Math.round((metrics.bedsFree / metrics.bedsTotal) * 100) : 0;
  const cards = [
    { label: 'Waiting', value: metrics.waiting, sub: `${metrics.criticalWaiting} critical`, cls: metrics.criticalWaiting ? 'is-critical' : 'is-accent' },
    { label: 'In treatment', value: metrics.inTreatment, sub: `${metrics.treatedToday} treated today`, cls: 'is-warn' },
    { label: 'Beds free', value: `${metrics.bedsFree}/${metrics.bedsTotal}`, sub: `${bedPercent}% of ED capacity`, cls: metrics.bedsFree === 0 ? 'is-critical' : 'is-good', bar: bedPercent },
    { label: 'Doctors available', value: `${metrics.doctorsAvailable}/${metrics.doctorsTotal}`, sub: metrics.doctorsAvailable ? 'ready to be assigned' : 'all in treatment', cls: metrics.doctorsAvailable ? 'is-good' : 'is-critical' },
    { label: 'Ambulances en route', value: metrics.ambulancesEnRoute, sub: `avg wait ${metrics.averageWaitMinutes} min`, cls: 'is-accent' },
  ];
  $('metrics').innerHTML = cards.map((c) => `
    <div class="metric glass ${c.cls}">
      <div class="label">${esc(c.label)}</div>
      <div class="value">${esc(c.value)}</div>
      <div class="sub">${esc(c.sub)}</div>
      ${c.bar === undefined ? '' : `<div class="bar"><span style="width:${c.bar}%"></span></div>`}
    </div>`).join('');
}

function renderQueue(queue) {
  const host = $('queue');
  if (!queue.length) {
    host.innerHTML = '<div class="empty">No patients waiting — the heap is empty.</div>';
    return;
  }
  host.innerHTML = queue.map((p) => `
    <div class="queue-item ${p.position === 1 ? 'is-next' : ''}">
      <div class="pos">${p.position}</div>
      <div>
        <div class="pt-name">
          ${esc(p.name)}
          <span class="muted">#${p.patientId} · ${p.age}y</span>
          ${priorityChip(p.priority)}
          ${p.position === 1 ? '<span class="chip chip-plain">next to treat</span>' : ''}
        </div>
        <div class="pt-meta">
          ${esc(p.symptoms || 'no symptoms recorded')} · ${esc(vitalsLine(p))} ·
          waiting <span class="pt-wait" data-arrival="${esc(p.arrivalTime)}">${p.minutesWaiting}</span> min
        </div>
      </div>
      <div class="pt-actions">
        <select data-reassess="${p.patientId}" aria-label="Reassess priority">
          <option value="">Reassess…</option>
          <option value="CRITICAL">1 · CRITICAL</option>
          <option value="HIGH">2 · HIGH</option>
          <option value="MEDIUM">3 · MEDIUM</option>
          <option value="LOW">4 · LOW</option>
        </select>
        <button class="btn mini" data-discharge="${p.patientId}">Discharge</button>
      </div>
    </div>`).join('');
}

function renderBeds(beds) {
  $('beds').innerHTML = beds.map((b) => `
    <div class="bed ${b.occupied ? 'occupied-' + esc(b.type) : 'free'}">
      <div class="bed-id">${esc(b.bedId)}</div>
      <div class="bed-type">${esc(b.type)}${b.type === 'ICU' ? ' · resuscitation' : b.type === 'TRAUMA' ? ' · trauma bay' : ''}</div>
      <div class="bed-occupant">${b.occupied
        ? `<svg viewBox="0 0 24 24" width="12" height="12" aria-hidden="true" class="person"><circle cx="12" cy="8" r="3.4" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M5.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg> ${esc(b.patientName || '#' + b.patientId)}`
        : '<span class="muted">FREE</span>'}</div>
    </div>`).join('');
  $('bed-legend').innerHTML = '<span>ICU → CRITICAL</span><span>Trauma → HIGH</span><span>General → MEDIUM / LOW</span>';
}

function renderDoctors(doctors) {
  $('doctors').innerHTML = doctors.map((d) => `
    <div class="doc ${d.available ? '' : 'busy'}">
      <span class="status-dot"></span>
      <div>
        <div class="doc-name">Dr. ${esc(d.name)} <span class="muted">[${esc(d.doctorId)}]</span></div>
        <div class="doc-meta">${esc(d.specialization)}</div>
      </div>
      <span class="chip ${d.available ? 'chip-LOW' : 'chip-HIGH'}">
        ${d.available ? 'Available' : 'Patient #' + d.currentPatientId}
      </span>
    </div>`).join('');
  const free = doctors.filter((d) => d.available).length;
  $('doctor-hint').textContent = `${free} of ${doctors.length} free`;
}

function renderAmbulances(ambulances) {
  $('ambulances').innerHTML = ambulances.map((a) => {
    const statusCls = a.status === 'EN_ROUTE' ? 'chip-HIGH' : a.status === 'ARRIVED' ? 'chip-LOW' : 'chip-plain';
    const percent = a.status === 'EN_ROUTE' ? Math.max(8, 100 - Math.min(100, a.etaMinutes * 5)) : 100;
    return `
      <div class="amb">
        <div class="amb-top">
          <span class="amb-id">${esc(a.ambulanceId)}</span>
          <span class="chip ${statusCls}">${esc(a.status.replace('_', ' '))}</span>
        </div>
        <div class="pt-meta">
          ${a.status === 'EN_ROUTE'
            ? `ETA <span class="eta-value" data-eta="${a.etaMinutes}" data-since="${renderedAt}">${a.etaMinutes}</span> min`
            : a.status === 'ARRIVED' ? 'At the entrance' : 'Idle on base'}
        </div>
        ${a.patientOnBoard ? `<div class="amb-note">Carrying: ${esc(a.patientOnBoard)} ${a.patientPriority ? priorityChip(a.patientPriority) : ''}</div>` : ''}
        <div class="eta-track"><span style="width:${percent}%"></span></div>
        <div class="amb-actions">
          <button class="btn mini" data-dispatch="${esc(a.ambulanceId)}">Dispatch</button>
          ${a.status === 'ARRIVED' ? `<button class="btn mini" data-prearrival="${esc(a.ambulanceId)}">Log inbound patient</button>` : ''}
        </div>
      </div>`;
  }).join('');
}

function renderHistory(patients) {
  const rows = patients
    .filter((p) => historyFilter === 'all' || p.status === historyFilter)
    .map((p) => `
      <tr>
        <td>#${p.patientId}</td>
        <td>${esc(p.name)}</td>
        <td>${p.age}</td>
        <td>${priorityChip(p.priority)}</td>
        <td>${statusChip(p.status)}</td>
        <td>${p.assignedDoctorName ? 'Dr. ' + esc(p.assignedDoctorName) : '<span class="muted">—</span>'}</td>
        <td>${p.assignedBed ? esc(p.assignedBed) : '<span class="muted">—</span>'}</td>
        <td>${clock(p.arrivalTime)}</td>
        <td>${p.status === 'WAITING' ? `<span class="pt-wait" data-arrival="${esc(p.arrivalTime)}">${p.minutesWaiting}</span> min` : '—'}</td>
        <td>${p.status === 'DISCHARGED' ? '' : `<button class="btn mini" data-discharge="${p.patientId}">Discharge</button>`}</td>
      </tr>`).join('');
  $('history').querySelector('tbody').innerHTML = rows
    || '<tr><td colspan="10" class="muted">No patients in this state.</td></tr>';
}

const EVENT_LABEL = {
  REGISTER: 'Registration', TREAT: 'Treatment', DISCHARGE: 'Discharge',
  REASSESS: 'Reassessment', ESCALATE: 'Vitals escalation',
  AMBULANCE_ARRIVED: 'Ambulance', AMBULANCE_DISPATCH: 'Dispatch',
  PRE_ARRIVAL: 'Pre-arrival', REPORT_EXPORT: 'Report export', RESET: 'Reset',
};

function renderEvents(events) {
  $('events').innerHTML = events.length
    ? events.map((e) => `
        <div class="event">
          <span class="muted">${clock(e.at)}</span>
          <span class="ev-name ev-${esc(e.event)}">${esc(EVENT_LABEL[e.event] || e.event)}</span>
          <span>${e.patientId ? `#${e.patientId} · ` : ''}${esc(e.detail)}</span>
        </div>`).join('')
    : '<div class="empty">No activity recorded yet.</div>';
}

function render(payload) {
  latest = payload;
  renderedAt = Date.now();
  renderMetrics(payload.metrics);
  renderQueue(payload.queue);
  renderBeds(payload.beds);
  renderDoctors(payload.doctors);
  renderAmbulances(payload.ambulances);
  renderHistory(payload.patients);
  renderEvents(payload.events);
  $('bed-hint').textContent = `${payload.metrics.bedsFree} free of ${payload.metrics.bedsTotal}`;
  $('last-updated').textContent = 'updated ' + clock(payload.generatedAt);
  $('footer-stats').textContent =
    `${payload.metrics.totalRegistered} registered · ${payload.metrics.treatedToday} treated · `
    + `${payload.metrics.dischargedToday} discharged · avg wait ${payload.metrics.averageWaitMinutes} min`;
}

/** Keeps "waiting X min" and ambulance ETAs moving between polls. */
function tickLocal() {
  const secondsSinceRender = (Date.now() - renderedAt) / 1000;
  document.querySelectorAll('.pt-wait').forEach((el) => {
    const arrival = Date.parse(el.dataset.arrival);
    if (!Number.isNaN(arrival)) {
      el.textContent = Math.max(0, Math.round((Date.now() - arrival) / 60000));
    }
  });
  document.querySelectorAll('.eta-value').forEach((el) => {
    const base = Number(el.dataset.eta);
    el.textContent = Math.max(0, base - Math.floor(secondsSinceRender / 60));
  });
}

/* --------------------------------------------------------------- polling */

function setConnection(live) {
  const pill = $('connection');
  pill.className = 'pill ' + (live ? 'pill-live' : 'pill-stale');
  pill.innerHTML = live
    ? '<span class="dot"></span> Live · 5 s poll'
    : '<span class="dot"></span> Reconnecting…';
}

async function refresh() {
  try {
    const payload = await api('GET', '/api/dashboard');
    consecutiveFailures = 0;
    setConnection(true);
    render(payload);
  } catch (err) {
    consecutiveFailures += 1;
    if (consecutiveFailures === 1 || consecutiveFailures % 6 === 0) {
      setConnection(false);
      toast('err', 'Dashboard offline', 'Could not reach /api/dashboard. Is server/app.js running?');
    }
  }
}

/* --------------------------------------------------------------- actions */

async function guard(label, fn) {
  try {
    const result = await fn();
    if (result && result.message) {
      toast('ok', label, result.message);
    }
    await refresh();
    return result;
  } catch (err) {
    const suffix = err.code ? ` [${err.code}]` : '';
    toast('err', label + ' failed', (err.message || 'Unexpected error') + suffix);
    return null;
  }
}

$('btn-treat').addEventListener('click', () => guard('Treat next patient',
  () => api('POST', '/api/patients/treat-next')));

$('btn-tick').addEventListener('click', () => guard('Ambulance tick',
  () => api('POST', '/api/ambulances/tick')));

$('btn-reset').addEventListener('click', () => {
  if (!window.confirm('Clear all patients and release every doctor and bed?')) {
    return;
  }
  guard('Reset', () => api('POST', '/api/reset'));
});

$('btn-print').addEventListener('click', () => window.print());

$('history-filter').addEventListener('click', (event) => {
  const button = event.target.closest('button[data-filter]');
  if (!button) {
    return;
  }
  historyFilter = button.dataset.filter;
  $('history-filter').querySelectorAll('button').forEach((b) => b.classList.toggle('chip-active', b === button));
  if (latest) {
    renderHistory(latest.patients);
  }
});

// Delegated handlers for the dynamically rendered cards, rows and tiles.
document.addEventListener('click', (event) => {
  const dischargeBtn = event.target.closest('[data-discharge]');
  if (dischargeBtn) {
    const id = dischargeBtn.dataset.discharge;
    guard('Discharge', () => api('POST', `/api/patients/${id}/discharge`));
    return;
  }
  const dispatchBtn = event.target.closest('[data-dispatch]');
  if (dispatchBtn) {
    const id = dispatchBtn.dataset.dispatch;
    const eta = window.prompt(`ETA in minutes for ${id}?`, '10');
    if (eta !== null) {
      guard('Dispatch', () => api('POST', `/api/ambulances/${id}/dispatch`, { etaMinutes: Number(eta) || 10 }));
    }
    return;
  }
  const preBtn = event.target.closest('[data-prearrival]');
  if (preBtn) {
    const id = preBtn.dataset.prearrival;
    const name = window.prompt('Patient name on board?', 'Inbound patient');
    if (name) {
      const priority = (window.prompt('Severity: CRITICAL, HIGH, MEDIUM or LOW', 'CRITICAL') || '').toUpperCase();
      guard('Pre-arrival', () => api('POST', `/api/ambulances/${id}/pre-arrival`, { patientName: name, priority }));
    }
  }
});

document.addEventListener('change', (event) => {
  const select = event.target.closest('[data-reassess]');
  if (select && select.value) {
    const id = select.dataset.reassess;
    const priority = select.value;
    select.value = '';
    guard('Reassessment', () => api('POST', `/api/patients/${id}/reassess`, { priority }));
  }
});

/* ----------------------------------------------------------------- modal */

const modal = $('modal');

function openModal() {
  $('form-error').hidden = true;
  modal.hidden = false;
  modal.querySelector('input[name="name"]').focus();
}

function closeModal() {
  modal.hidden = true;
  $('register-form').reset();
  $('vitals-warning').hidden = true;
}

$('btn-register').addEventListener('click', openModal);
$('modal-close').addEventListener('click', closeModal);
$('modal-cancel').addEventListener('click', closeModal);
modal.addEventListener('click', (event) => {
  if (event.target === modal) {
    closeModal();
  }
});
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && !modal.hidden) {
    closeModal();
  }
});

// Client-side echo of the engine's vitals rule, so staff see the warning before submitting.
['heartRate', 'spo2'].forEach((field) => {
  document.querySelector(`#register-form input[name="${field}"]`).addEventListener('input', () => {
    const hr = Number($('register-form').elements.heartRate.value);
    const spo2 = Number($('register-form').elements.spo2.value);
    const flag = (spo2 && spo2 < 92) || (hr && (hr > 130 || hr < 40));
    $('vitals-warning').hidden = !flag;
  });
});

$('register-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const data = {
    name: form.elements.name.value,
    age: form.elements.age.value,
    symptoms: form.elements.symptoms.value,
    priority: form.querySelector('input[name="priority"]:checked').value,
    heartRate: form.elements.heartRate.value,
    spo2: form.elements.spo2.value,
    bloodPressure: form.elements.bloodPressure.value,
  };
  try {
    const result = await api('POST', '/api/patients', data);
    closeModal();
    toast(result.escalated ? 'warn' : 'ok', 'Patient triaged', result.message);
    if (result.vitalWarning && !result.escalated) {
      toast('warn', 'Vitals raised', 'Recorded vitals suggest a more urgent band than the one selected.');
    }
    await refresh();
  } catch (err) {
    const error = $('form-error');
    error.hidden = false;
    error.textContent = (err.message || 'Registration refused') + (err.code ? ` [${err.code}]` : '');
  }
});

/* ------------------------------------------------------------------ boot */

refresh();
setInterval(refresh, POLL_MS);
setInterval(tickLocal, 1000);
