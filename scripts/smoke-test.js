#!/usr/bin/env node
/**
 * ERverse REST smoke test - the HTTP counterpart of the JUnit suite.
 *
 * Runs the same TC-01..TC-04 workflow against a live server and checks that the
 * JavaScript mirror of the triage engine reports the same outcomes as the Java
 * core, including the error codes that mirror the TriageException subclasses.
 *
 *   node server/app.js            # terminal 1
 *   node scripts/smoke-test.js    # terminal 2
 */

'use strict';

const BASE = process.env.ERVERSE_URL || `http://127.0.0.1:${process.env.PORT || 3000}`;

let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log(`  PASS  ${name}`);
  } else {
    failed++;
    console.log(`  FAIL  ${name}${detail ? ` -> ${detail}` : ''}`);
  }
}

async function call(method, path, body) {
  const response = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch (err) {
    json = { raw: text };
  }
  return { status: response.status, body: json, contentType: response.headers.get('content-type') || '' };
}

const rank = { CRITICAL: 1, HIGH: 2, MEDIUM: 3, LOW: 4 };

(async function run() {
  console.log(`ERverse REST smoke test against ${BASE}\n`);

  const health = await call('GET', '/api/health');
  check('GET /api/health responds 200', health.status === 200, `status ${health.status}`);
  if (health.status !== 200) {
    console.log('\nServer not reachable - start it with "node server/app.js".');
    process.exit(1);
  }

  await call('POST', '/api/reset');

  // ---- TC-01: priority ordering -----------------------------------------
  console.log('\nTC-01  Priority queue reordering');
  const low = await call('POST', '/api/patients', { name: 'Low Acuity', age: 22, symptoms: 'Ankle sprain', priority: 'LOW' });
  const critical = await call('POST', '/api/patients', { name: 'Critical Case', age: 64, symptoms: 'Cardiac arrest', priority: 'CRITICAL', heartRate: 128, spo2: 89, bloodPressure: '90/60' });
  check('POST /api/patients returns 201', low.status === 201 && critical.status === 201, `${low.status}/${critical.status}`);
  const afterRegistration = await call('GET', '/api/dashboard');
  check('Critical patient is queue position 1', afterRegistration.body.queue[0].patientId === critical.body.patient.patientId,
    `head=${afterRegistration.body.queue[0] && afterRegistration.body.queue[0].name}`);
  check('Low-acuity patient is behind', afterRegistration.body.queue[1].patientId === low.body.patient.patientId);
  const ranks = afterRegistration.body.queue.map((p) => rank[p.priority]);
  check('Queue is sorted by severity rank', ranks.every((r, i) => i === 0 || ranks[i - 1] <= r), JSON.stringify(ranks));

  // ---- comparator parity with Patient.compareTo() ------------------------
  console.log('\nOrdering rule parity with Java Patient.compareTo()');
  const tie = await call('POST', '/api/patients', { name: 'First Low', age: 30, symptoms: 'tie a', priority: 'LOW' });
  const tie2 = await call('POST', '/api/patients', { name: 'Second Low', age: 31, symptoms: 'tie b', priority: 'LOW' });
  const tied = await call('GET', '/api/dashboard');
  const lows = tied.body.queue.filter((p) => p.priority === 'LOW');
  check('Equal severity falls back to arrival order (FIFO)',
    lows[0].patientId === low.body.patient.patientId
      && lows[1].patientId === tie.body.patient.patientId
      && lows[2].patientId === tie2.body.patient.patientId,
    lows.map((p) => p.name).join(' , '));

  // ---- TC-02: resource allocation ---------------------------------------
  console.log('\nTC-02  Automatic resource allocation');
  const treat = await call('POST', '/api/patients/treat-next');
  check('POST /api/patients/treat-next returns 200', treat.status === 200, `status ${treat.status}`);
  check('Doctor assigned to the critical patient', treat.body.doctor && treat.body.doctor.doctorId === 'D01',
    treat.body.doctor && treat.body.doctor.doctorId);
  check('ICU bed assigned to the critical patient', treat.body.bed && treat.body.bed.type === 'ICU',
    treat.body.bed && treat.body.bed.bedId);
  check('Patient status becomes IN_TREATMENT', treat.body.patient.status === 'IN_TREATMENT', treat.body.patient.status);

  // ---- error parity with the Java exception hierarchy --------------------
  console.log('\nException parity (Inherited from the Java TriageException hierarchy)');
  const invalid = await call('POST', '/api/patients', { name: '   ', age: 30, priority: 'LOW' });
  check('Blank name -> 400 INVALID_PATIENT_DATA', invalid.status === 400 && invalid.body.error === 'INVALID_PATIENT_DATA',
    `${invalid.status} ${invalid.body.error}`);
  const badAge = await call('POST', '/api/patients', { name: 'Age Check', age: 0, priority: 'LOW' });
  check('Impossible age -> 400 INVALID_PATIENT_DATA', badAge.status === 400 && badAge.body.error === 'INVALID_PATIENT_DATA');
  const unknown = await call('POST', '/api/patients/999999/discharge');
  check('Unknown patient -> 404 PATIENT_NOT_FOUND', unknown.status === 404 && unknown.body.error === 'PATIENT_NOT_FOUND');

  // Saturate the doctors, then confirm the 409 + patient retention rule.
  await call('POST', '/api/patients/treat-next');
  const thirdTreat = await call('POST', '/api/patients/treat-next');
  const saturated = await call('POST', '/api/patients/treat-next');
  check('Doctor exhaustion -> 409 NO_DOCTORS_AVAILABLE',
    saturated.status === 409 && saturated.body.error === 'NO_DOCTORS_AVAILABLE',
    `${saturated.status} ${saturated.body.error}`);
  const stillQueued = await call('GET', '/api/dashboard');
  check('A failed dispatch leaves the patient queued (not dropped)',
    stillQueued.body.metrics.waiting === 1, `waiting=${stillQueued.body.metrics.waiting}`);

  // ---- TC-03: discharge and release --------------------------------------
  console.log('\nTC-03  Discharge and resource release');
  const treatedId = treat.body.patient.patientId;
  const discharge = await call('POST', `/api/patients/${treatedId}/discharge`);
  check('POST discharge returns 200', discharge.status === 200, `status ${discharge.status}`);
  const afterDischarge = await call('GET', '/api/dashboard');
  const bed = afterDischarge.body.beds.find((b) => b.bedId === treat.body.bed.bedId);
  const doctor = afterDischarge.body.doctors.find((d) => d.doctorId === treat.body.doctor.doctorId);
  check('Bed returns to FREE', bed && bed.occupied === false, JSON.stringify(bed));
  check('Doctor returns to AVAILABLE', doctor && doctor.available === true, JSON.stringify(doctor));
  const doubleDischarge = await call('POST', `/api/patients/${treatedId}/discharge`);
  check('Double discharge -> 409 ALREADY_DISCHARGED', doubleDischarge.status === 409 && doubleDischarge.body.error === 'ALREADY_DISCHARGED',
    `${doubleDischarge.status} ${doubleDischarge.body.error}`);

  // ---- reassessment ------------------------------------------------------
  console.log('\nReassessment re-heapifies the queue');
  // "Second Low" is the only patient still waiting: the other LOW patients were
  // sent for treatment in the saturation step above.
  const waitingPatientId = tie2.body.patient.patientId;
  const reassess = await call('POST', `/api/patients/${waitingPatientId}/reassess`, { priority: 'CRITICAL' });
  check('Reassess returns 200', reassess.status === 200, `status ${reassess.status}`);
  check('Reassessed waiting patient is now position 1', reassess.body.position === 1, `position ${reassess.body.position}`);
  const afterReassess = await call('GET', '/api/dashboard');
  check('Queue head is the reassessed patient', afterReassess.body.queue[0].patientId === waitingPatientId,
    afterReassess.body.queue[0].name);
  const reassessTreated = await call('POST', `/api/patients/${thirdTreat.body.patient.patientId}/reassess`, { priority: 'LOW' });
  check('Reassessing a patient already in treatment reports no queue position',
    reassessTreated.status === 200 && reassessTreated.body.position === null,
    `status ${reassessTreated.status} position ${reassessTreated.body.position}`);

  // ---- TC-04: CSV export -------------------------------------------------
  console.log('\nTC-04  CSV report generation');
  const csv = await call('GET', '/api/report.csv');
  check('GET /api/report.csv returns text/csv', csv.status === 200 && csv.contentType.includes('text/csv'), csv.contentType);
  const csvText = await (await fetch(BASE + '/api/report.csv')).text();
  const lines = csvText.trim().split('\n');
  check('CSV has a header row', lines[0].startsWith('patient_id,name,age,priority'));
  check('CSV has one row per patient plus a summary', lines.length >= 4, `${lines.length} lines`);
  check('CSV summary line present', lines[lines.length - 1].startsWith('summary,total='));

  // ---- ambulance simulation ---------------------------------------------
  console.log('\nAmbulance ETA simulation');
  const beforeTick = (await call('GET', '/api/dashboard')).body.ambulances;
  const tick = await call('POST', '/api/ambulances/tick');
  check('POST /api/ambulances/tick returns 200', tick.status === 200);
  const etasMoved = tick.body.ambulances.every((after) => {
    const before = beforeTick.find((b) => b.ambulanceId === after.ambulanceId);
    if (after.status !== 'EN_ROUTE') {
      return true;                     // ARRIVED / AVAILABLE are terminal for this check
    }
    return after.etaMinutes === before.etaMinutes - 1;
  });
  check('Each en-route ETA decrements by exactly one minute', etasMoved,
    JSON.stringify(tick.body.ambulances.map((a) => `${a.ambulanceId}:${a.etaMinutes}`)));

  // ---- dashboard contract -----------------------------------------------
  console.log('\nDashboard payload contract (polled every 5 s)');
  const dash = await call('GET', '/api/dashboard');
  const requiredMetrics = ['waiting', 'criticalWaiting', 'bedsFree', 'doctorsAvailable', 'ambulancesEnRoute', 'averageWaitMinutes'];
  check('All metric cards present', requiredMetrics.every((k) => typeof dash.body.metrics[k] === 'number'),
    Object.keys(dash.body.metrics).join(','));
  check('Queue, doctors, beds, ambulances and events all returned',
    Array.isArray(dash.body.queue) && Array.isArray(dash.body.doctors)
      && Array.isArray(dash.body.beds) && Array.isArray(dash.body.ambulances) && Array.isArray(dash.body.events));

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
})().catch((err) => {
  console.error('Smoke test crashed:', err);
  process.exit(1);
});
