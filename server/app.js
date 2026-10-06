/**
 * ERverse REST API (Table 5.2 of the PBL report).
 *
 * Express server on port 3000 that mirrors the Java triage engine
 * (TriageSystem.java) so HTTP clients - chiefly the glassmorphism dashboard in
 * public/ - can register, treat and discharge patients without embedding a JVM.
 * State is persisted to data/erverse_data.json after every mutation.
 *
 * The ordering rule is a single specification with two implementations
 * (Section 3.6.2): severity rank first, arrival timestamp second, and patient id
 * last for full determinism. It is implemented once, here in comparePatients(),
 * exactly as Patient.compareTo() implements it in Java.
 */

'use strict';

const express = require('express');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '0.0.0.0';
const DATA_FILE = path.join(__dirname, '..', 'data', 'erverse_data.json');
const PUBLIC_DIR = path.join(__dirname, '..', 'public');

// ---------------------------------------------------------------------------
// Domain constants - kept numerically identical to the Java enums
// ---------------------------------------------------------------------------

const RANK = { CRITICAL: 1, HIGH: 2, MEDIUM: 3, LOW: 4 };
const PRIORITY_DESCRIPTION = {
  CRITICAL: 'Critical - immediate life threat',
  HIGH: 'High - urgent, treat within 15 min',
  MEDIUM: 'Medium - treat within 60 min',
  LOW: 'Low - non urgent',
};
const STATUS = { WAITING: 'WAITING', IN_TREATMENT: 'IN_TREATMENT', DISCHARGED: 'DISCHARGED' };
const BED_TYPE = { ICU: 'ICU', TRAUMA: 'TRAUMA', GENERAL: 'GENERAL' };
const AMB_STATUS = { EN_ROUTE: 'EN_ROUTE', ARRIVED: 'ARRIVED', AVAILABLE: 'AVAILABLE' };
const VITAL_NOT_RECORDED = 0;

/** Mirrors TriageSystem.preferredSpecializations(). */
const PREFERRED_SPECIALIZATIONS = {
  CRITICAL: ['Trauma', 'Emergency', 'Cardiology'],
  HIGH: ['Cardiology', 'Emergency', 'General'],
  MEDIUM: ['General', 'Emergency'],
  LOW: ['General', 'Emergency'],
};

/** Mirrors TriageSystem.selectBed(). */
const PREFERRED_BED_TYPES = {
  CRITICAL: [BED_TYPE.ICU, BED_TYPE.TRAUMA, BED_TYPE.GENERAL],
  HIGH: [BED_TYPE.TRAUMA, BED_TYPE.GENERAL, BED_TYPE.ICU],
  MEDIUM: [BED_TYPE.GENERAL, BED_TYPE.TRAUMA],
  LOW: [BED_TYPE.GENERAL, BED_TYPE.TRAUMA],
};

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

let state = {
  nextPatientId: 1000,
  patients: [],
  doctors: [],
  beds: [],
  ambulances: [],
  events: [],
};

function seedResources() {
  state.doctors = [
    { doctorId: 'D01', name: 'Anitha Raj', specialization: 'Trauma', available: true, currentPatientId: null },
    { doctorId: 'D02', name: 'Vikram Shah', specialization: 'Cardiology', available: true, currentPatientId: null },
    { doctorId: 'D03', name: 'Priya Menon', specialization: 'General', available: true, currentPatientId: null },
  ];
  state.beds = [
    { bedId: 'BED-101', type: BED_TYPE.ICU, occupied: false, patientId: null, patientName: null },
    { bedId: 'BED-102', type: BED_TYPE.TRAUMA, occupied: false, patientId: null, patientName: null },
    { bedId: 'BED-103', type: BED_TYPE.GENERAL, occupied: false, patientId: null, patientName: null },
    { bedId: 'BED-104', type: BED_TYPE.GENERAL, occupied: false, patientId: null, patientName: null },
  ];
  state.ambulances = [
    { ambulanceId: 'AMB-101', status: AMB_STATUS.EN_ROUTE, etaMinutes: 8, patientOnBoard: null, patientPriority: null },
    { ambulanceId: 'AMB-102', status: AMB_STATUS.EN_ROUTE, etaMinutes: 15, patientOnBoard: null, patientPriority: null },
  ];
}

function loadState() {
  try {
    const raw = fs.readFileSync(DATA_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    if (parsed && Array.isArray(parsed.patients)) {
      state = Object.assign({
        nextPatientId: 1000,
        patients: [],
        doctors: [],
        beds: [],
        ambulances: [],
        events: [],
      }, parsed);
      return true;
    }
  } catch (err) {
    // First run, or the file was removed: fall through to a fresh roster.
  }
  seedResources();
  return false;
}

let saveTimer = null;
function persist() {
  // Coalesce bursts of mutations into one write.
  if (saveTimer) {
    return;
  }
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
      const tmp = DATA_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
      fs.renameSync(tmp, DATA_FILE);
    } catch (err) {
      console.error('[ERverse][API] Could not persist state:', err.message);
    }
  }, 40);
}

function logEvent(event, patientId, detail) {
  state.events.unshift({
    at: new Date().toISOString(),
    event,
    patientId: patientId === undefined ? null : patientId,
    detail: detail || '',
  });
  if (state.events.length > 200) {
    state.events.length = 200;
  }
}

// ---------------------------------------------------------------------------
// Triage engine - the mirror of TriageSystem.java
// ---------------------------------------------------------------------------

/**
 * The single ordering specification (Section 3.6.2): severity rank, then
 * arrival timestamp, then patient id. Identical to Patient.compareTo().
 */
function comparePatients(a, b) {
  const byRank = RANK[a.priority] - RANK[b.priority];
  if (byRank !== 0) {
    return byRank;
  }
  const byArrival = Date.parse(a.arrivalTime) - Date.parse(b.arrivalTime);
  if (byArrival !== 0) {
    return byArrival;
  }
  return a.patientId - b.patientId;
}

const waitingQueue = () => state.patients.filter((p) => p.status === STATUS.WAITING).sort(comparePatients);
const inTreatment = () => state.patients.filter((p) => p.status === STATUS.IN_TREATMENT);

class TriageError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function validatePatientInput(body) {
  const name = typeof body.name === 'string' ? body.name.trim() : '';
  const age = Number(body.age);
  const heartRate = body.heartRate === '' || body.heartRate === undefined || body.heartRate === null
    ? VITAL_NOT_RECORDED : Number(body.heartRate);
  const spo2 = body.spo2 === '' || body.spo2 === undefined || body.spo2 === null
    ? VITAL_NOT_RECORDED : Number(body.spo2);
  let bloodPressure = typeof body.bloodPressure === 'string' && body.bloodPressure.trim() !== ''
    ? body.bloodPressure.trim() : '-';
  const priority = typeof body.priority === 'string' ? body.priority.trim().toUpperCase() : '';

  if (!name) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'Patient name is required');
  }
  if (!Number.isFinite(age) || age <= 0 || age > 120) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'Age must be between 1 and 120');
  }
  if (heartRate !== VITAL_NOT_RECORDED && (!Number.isFinite(heartRate) || heartRate < 20 || heartRate > 250)) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'Heart rate out of range (20-250 bpm)');
  }
  if (spo2 !== VITAL_NOT_RECORDED && (!Number.isFinite(spo2) || spo2 < 0 || spo2 > 100)) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'SpO2 out of range (0-100 %)');
  }
  if (bloodPressure !== '-' && !/^\d{2,3}\/\d{2,3}$/.test(bloodPressure)) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'Blood pressure must look like 120/80');
  }
  if (!RANK[priority]) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'Severity must be CRITICAL, HIGH, MEDIUM or LOW');
  }
  return {
    name,
    age,
    symptoms: typeof body.symptoms === 'string' ? body.symptoms.trim() : '',
    priority,
    heartRate: heartRate === VITAL_NOT_RECORDED ? VITAL_NOT_RECORDED : Math.round(heartRate),
    spo2: spo2 === VITAL_NOT_RECORDED ? VITAL_NOT_RECORDED : Math.round(spo2),
    bloodPressure,
  };
}

function registerPatient(body) {
  const input = validatePatientInput(body);
  const now = new Date().toISOString();
  const patient = Object.assign(input, {
    patientId: ++state.nextPatientId,
    arrivalTime: now,
    status: STATUS.WAITING,
    assignedDoctor: null,
    assignedDoctorName: null,
    assignedBed: null,
    treatedAt: null,
    dischargedAt: null,
    audit: [`${now}  Registered at triage with priority ${input.priority}`],
  });
  state.patients.push(patient);
  logEvent('REGISTER', patient.patientId, `priority=${patient.priority} symptoms=${patient.symptoms}`);
  persist();
  return patient;
}

function selectDoctor(priority) {
  for (const wanted of PREFERRED_SPECIALIZATIONS[priority]) {
    const match = state.doctors.find((d) => d.available && d.specialization === wanted);
    if (match) {
      return match;
    }
  }
  return state.doctors.find((d) => d.available) || null;
}

function selectBed(priority) {
  for (const type of PREFERRED_BED_TYPES[priority]) {
    const match = state.beds.find((b) => !b.occupied && b.type === type);
    if (match) {
      return match;
    }
  }
  return null;
}

/**
 * Peek -> secure resources -> dequeue, mirroring the corrected Java method: a
 * failed dispatch must never drop a patient out of the queue.
 */
function treatNextPatient() {
  const queue = waitingQueue();
  const next = queue[0];
  if (!next) {
    throw new TriageError(404, 'NO_PATIENTS_WAITING', 'No patients waiting.');
  }
  const doctor = selectDoctor(next.priority);
  if (!doctor) {
    throw new TriageError(409, 'NO_DOCTORS_AVAILABLE',
      `No doctor available for ${next.name} (Priority ${next.priority}). Patient remains #1 in the queue.`);
  }
  const bed = selectBed(next.priority);
  if (!bed) {
    throw new TriageError(409, 'NO_BEDS_AVAILABLE',
      `No bed of a suitable type free for ${next.name} (Priority ${next.priority}). Patient remains #1 in the queue.`);
  }

  doctor.available = false;
  doctor.currentPatientId = next.patientId;
  bed.occupied = true;
  bed.patientId = next.patientId;
  bed.patientName = next.name;
  next.assignedDoctor = doctor.doctorId;
  next.assignedDoctorName = doctor.name;
  next.assignedBed = bed.bedId;
  next.status = STATUS.IN_TREATMENT;
  next.treatedAt = new Date().toISOString();
  next.audit.push(`${next.treatedAt}  Treatment started - Dr. ${doctor.name} (${doctor.doctorId}), bed ${bed.bedId} (${bed.type})`);

  logEvent('TREAT', next.patientId, `doctor=${doctor.doctorId} bed=${bed.bedId}`);
  persist();
  return { patient: next, doctor, bed };
}

function findPatient(patientId) {
  return state.patients.find((p) => p.patientId === patientId) || null;
}

function dischargePatient(patientId) {
  const patient = findPatient(patientId);
  if (!patient) {
    throw new TriageError(404, 'PATIENT_NOT_FOUND', `No patient with id #${patientId}`);
  }
  if (patient.status === STATUS.DISCHARGED) {
    throw new TriageError(409, 'ALREADY_DISCHARGED', `Patient #${patientId} (${patient.name}) is already discharged.`);
  }
  const doctor = state.doctors.find((d) => d.doctorId === patient.assignedDoctor);
  if (doctor) {
    doctor.available = true;
    doctor.currentPatientId = null;
  }
  const bed = state.beds.find((b) => b.bedId === patient.assignedBed);
  if (bed) {
    bed.occupied = false;
    bed.patientId = null;
    bed.patientName = null;
  }
  patient.status = STATUS.DISCHARGED;
  patient.dischargedAt = new Date().toISOString();
  patient.audit.push(`${patient.dischargedAt}  Discharged from ${patient.assignedBed || 'ED'}`);

  logEvent('DISCHARGE', patientId, `doctor=${patient.assignedDoctor} bed=${patient.assignedBed}`);
  persist();
  return patient;
}

function reassessPatient(patientId, priorityWanted) {
  const patient = findPatient(patientId);
  if (!patient) {
    throw new TriageError(404, 'PATIENT_NOT_FOUND', `No patient with id #${patientId}`);
  }
  const priority = String(priorityWanted || '').trim().toUpperCase();
  if (!RANK[priority]) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'New severity must be CRITICAL, HIGH, MEDIUM or LOW');
  }
  if (patient.status === STATUS.DISCHARGED) {
    throw new TriageError(409, 'ALREADY_DISCHARGED', `Patient #${patientId} has already been discharged.`);
  }
  const previous = patient.priority;
  patient.priority = priority;
  patient.audit.push(`${new Date().toISOString()}  Priority reassessed ${previous} -> ${priority} (staff reassessment)`);

  logEvent('REASSESS', patientId, `${previous} -> ${priority}`);
  persist();
  const position = positionInQueue(patientId);
  return { patient, previous, priority, position };
}

function positionInQueue(patientId) {
  const queue = waitingQueue();
  const index = queue.findIndex((p) => p.patientId === patientId);
  return index === -1 ? null : index + 1;
}

function vitalsSuggestEscalation(patient) {
  const { heartRate, spo2 } = patient;
  const lowOxygen = spo2 !== VITAL_NOT_RECORDED && spo2 < 92;
  const tachycardic = heartRate !== VITAL_NOT_RECORDED && heartRate > 130;
  const bradycardic = heartRate !== VITAL_NOT_RECORDED && heartRate < 40;
  return lowOxygen || tachycardic || bradycardic;
}

function nextMoreUrgentBand(priority) {
  const wanted = RANK[priority] - 1;
  return Object.keys(RANK).find((p) => RANK[p] === wanted) || null;
}

function vitalsEscalationSweep() {
  const promoted = [];
  for (const patient of waitingQueue()) {
    if (!vitalsSuggestEscalation(patient)) {
      continue;
    }
    const next = nextMoreUrgentBand(patient.priority);
    if (!next) {
      continue;
    }
    const previous = patient.priority;
    patient.priority = next;
    patient.audit.push(`${new Date().toISOString()}  Auto-escalated ${previous} -> ${next} (vitals)`);
    logEvent('ESCALATE', patient.patientId, `${previous} -> ${next} (HR ${patient.heartRate}, SpO2 ${patient.spo2})`);
    promoted.push({ patient, previous, priority: next });
  }
  if (promoted.length) {
    persist();
  }
  return promoted;
}

function tickAmbulances() {
  const arrived = [];
  for (const amb of state.ambulances) {
    if (amb.status === AMB_STATUS.EN_ROUTE) {
      if (amb.etaMinutes > 0) {
        amb.etaMinutes -= 1;
      }
      if (amb.etaMinutes === 0) {
        amb.status = AMB_STATUS.ARRIVED;
        arrived.push(amb);
        logEvent('AMBULANCE_ARRIVED', null,
          `${amb.ambulanceId}${amb.patientOnBoard ? ` carrying ${amb.patientOnBoard} (${amb.patientPriority})` : ''}`);
      }
    }
  }
  persist();
  return arrived;
}

// ---------------------------------------------------------------------------
// Serialisation for the dashboard
// ---------------------------------------------------------------------------

function minutesWaiting(patient, now) {
  const arrival = Date.parse(patient.arrivalTime);
  const end = patient.treatedAt ? Date.parse(patient.treatedAt) : now;
  return Math.max(0, Math.round((end - arrival) / 60000));
}

function buildDashboard() {
  const now = Date.now();
  const queue = waitingQueue();
  const treated = state.patients.filter((p) => p.treatedAt);
  const averageWait = treated.length
    ? Math.round(treated.reduce((sum, p) => sum + (Date.parse(p.treatedAt) - Date.parse(p.arrivalTime)), 0)
        / treated.length / 60000)
    : 0;

  return {
    generatedAt: new Date(now).toISOString(),
    pollIntervalMs: 5000,
    metrics: {
      waiting: queue.length,
      criticalWaiting: queue.filter((p) => p.priority === 'CRITICAL').length,
      inTreatment: inTreatment().length,
      bedsFree: state.beds.filter((b) => !b.occupied).length,
      bedsTotal: state.beds.length,
      doctorsAvailable: state.doctors.filter((d) => d.available).length,
      doctorsTotal: state.doctors.length,
      ambulancesEnRoute: state.ambulances.filter((a) => a.status === AMB_STATUS.EN_ROUTE).length,
      treatedToday: treated.length,
      dischargedToday: state.patients.filter((p) => p.status === STATUS.DISCHARGED).length,
      averageWaitMinutes: averageWait,
      totalRegistered: state.patients.length,
    },
    queue: queue.map((p, index) => Object.assign({ position: index + 1, minutesWaiting: minutesWaiting(p, now) }, p)),
    inTreatment: inTreatment().map((p) => Object.assign({ minutesWaiting: minutesWaiting(p, now) }, p)),
    doctors: state.doctors,
    beds: state.beds,
    ambulances: state.ambulances,
    patients: state.patients.slice().sort((a, b) => b.patientId - a.patientId),
    events: state.events.slice(0, 25),
    priorityLegend: Object.keys(RANK).map((p) => ({ priority: p, rank: RANK[p], description: PRIORITY_DESCRIPTION[p] })),
  };
}

function buildCsv() {
  const escape = (value) => {
    const text = value === null || value === undefined ? '' : String(value);
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const header = 'patient_id,name,age,priority,status,arrival_time,heart_rate,spo2,blood_pressure,'
    + 'assigned_doctor,assigned_bed,symptoms';
  const rows = state.patients
    .slice()
    .sort((a, b) => a.patientId - b.patientId)
    .map((p) => [
      p.patientId, escape(p.name), p.age, p.priority, p.status,
      escape(new Date(p.arrivalTime).toISOString().slice(0, 19).replace('T', ' ')),
      p.heartRate === VITAL_NOT_RECORDED ? '' : p.heartRate,
      p.spo2 === VITAL_NOT_RECORDED ? '' : p.spo2,
      escape(p.bloodPressure), escape(p.assignedDoctor), escape(p.assignedBed), escape(p.symptoms),
    ].join(','));
  const metrics = buildDashboard().metrics;
  const summary = `summary,total=${metrics.totalRegistered},waiting=${metrics.waiting},`
    + `in_treatment=${metrics.inTreatment},discharged=${metrics.dischargedToday},`
    + `beds_free=${metrics.bedsFree},doctors_available=${metrics.doctorsAvailable}`;
  return [header, ...rows, summary].join('\n') + '\n';
}

// ---------------------------------------------------------------------------
// HTTP layer
// ---------------------------------------------------------------------------

app.use(express.json({ limit: '64kb' }));
app.use(express.static(PUBLIC_DIR, { extensions: ['html'] }));

const wrap = (handler) => (req, res) => {
  try {
    handler(req, res);
  } catch (err) {
    if (err instanceof TriageError) {
      res.status(err.status).json({ error: err.code, message: err.message });
    } else {
      console.error('[ERverse][API] Unhandled error:', err);
      res.status(500).json({ error: 'INTERNAL_ERROR', message: err.message });
    }
  }
};

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', service: 'erverse-api', patients: state.patients.length });
});

/** Metric cards, live queue, resource panels - polled every five seconds. */
app.get('/api/dashboard', wrap((req, res) => {
  res.json(buildDashboard());
}));

app.post('/api/patients', wrap((req, res) => {
  const patient = registerPatient(req.body || {});
  const escalated = vitalsSuggestEscalation(patient) && patient.priority !== 'CRITICAL'
    ? vitalsEscalationSweep().filter((x) => x.patient.patientId === patient.patientId)
    : [];
  const stored = findPatient(patient.patientId) || patient;
  res.status(201).json({
    patient: Object.assign({ position: positionInQueue(patient.patientId) }, stored),
    vitalWarning: vitalsSuggestEscalation(stored) && stored.priority !== 'CRITICAL',
    escalated: escalated.length > 0,
    message: `Registered ${stored.name} (#${stored.patientId}) as ${stored.priority}`
      + (stored.status === STATUS.WAITING ? ` - queue position ${positionInQueue(stored.patientId)}` : ''),
  });
}));

app.post('/api/patients/treat-next', wrap((req, res) => {
  const result = treatNextPatient();
  res.json({
    message: `Now treating: ${result.patient.name} (Priority: ${result.patient.priority})`,
    patient: result.patient,
    doctor: result.doctor,
    bed: result.bed,
  });
}));

app.post('/api/patients/:id/discharge', wrap((req, res) => {
  const patient = dischargePatient(Number(req.params.id));
  res.json({
    message: `Discharged: ${patient.name} (#${patient.patientId})`,
    patient,
    releasedDoctor: patient.assignedDoctor,
    releasedBed: patient.assignedBed,
  });
}));

app.post('/api/patients/:id/reassess', wrap((req, res) => {
  const result = reassessPatient(Number(req.params.id), (req.body || {}).priority);
  const note = result.position === null
    ? ' (already in treatment - queue order unaffected)'
    : ` - now queue position ${result.position}`;
  res.json({
    message: `Reassessed ${result.patient.name}: ${result.previous} -> ${result.priority}${note}`,
    patient: result.patient,
    previousPriority: result.previous,
    priority: result.priority,
    position: result.position,
  });
}));

app.post('/api/vitals-sweep', wrap((req, res) => {
  const promoted = vitalsEscalationSweep();
  res.json({
    promoted: promoted.map((p) => ({ patientId: p.patient.patientId, name: p.patient.name, previous: p.previous, priority: p.priority })),
    message: promoted.length ? `${promoted.length} patient(s) escalated on vitals.` : 'No vitals escalation required.',
  });
}));

app.post('/api/ambulances/tick', wrap((req, res) => {
  const arrived = tickAmbulances();
  res.json({
    arrived: arrived.map((a) => ({ ambulanceId: a.ambulanceId, patientOnBoard: a.patientOnBoard })),
    ambulances: state.ambulances,
    message: arrived.length ? `${arrived.length} ambulance(s) arrived.` : 'ETAs updated.',
  });
}));

app.post('/api/ambulances/:id/dispatch', wrap((req, res) => {
  const amb = state.ambulances.find((a) => a.ambulanceId.toLowerCase() === req.params.id.toLowerCase());
  if (!amb) {
    throw new TriageError(404, 'AMBULANCE_NOT_FOUND', `Unknown ambulance ${req.params.id}`);
  }
  const eta = Number((req.body || {}).etaMinutes);
  amb.etaMinutes = Number.isFinite(eta) && eta > 0 ? Math.round(eta) : 10;
  amb.status = AMB_STATUS.EN_ROUTE;
  amb.patientOnBoard = null;
  amb.patientPriority = null;
  logEvent('AMBULANCE_DISPATCH', null, `${amb.ambulanceId} eta=${amb.etaMinutes}`);
  persist();
  res.json({ ambulance: amb, message: `Ambulance ${amb.ambulanceId} dispatched, ETA ${amb.etaMinutes} min.` });
}));

app.post('/api/ambulances/:id/pre-arrival', wrap((req, res) => {
  const amb = state.ambulances.find((a) => a.ambulanceId.toLowerCase() === req.params.id.toLowerCase());
  if (!amb) {
    throw new TriageError(404, 'AMBULANCE_NOT_FOUND', `Unknown ambulance ${req.params.id}`);
  }
  const body = req.body || {};
  const name = typeof body.patientName === 'string' ? body.patientName.trim() : '';
  const priority = String(body.priority || '').toUpperCase();
  if (!name) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'Patient name is required');
  }
  if (!RANK[priority]) {
    throw new TriageError(400, 'INVALID_PATIENT_DATA', 'Severity must be CRITICAL, HIGH, MEDIUM or LOW');
  }
  amb.patientOnBoard = name;
  amb.patientPriority = priority;
  logEvent('PRE_ARRIVAL', null, `${name} via ${amb.ambulanceId} priority=${priority}`);
  persist();
  res.json({ ambulance: amb, message: `Pre-arrival logged: ${name} (${priority}) inbound on ${amb.ambulanceId}.` });
}));

/** TC-04 - the dashboard's Export CSV button. */
app.get('/api/report.csv', wrap((req, res) => {
  res.setHeader('Content-Type', 'text/csv; charset=utf-8');
  res.setHeader('Content-Disposition', 'attachment; filename="ERverse_Daily_Report.csv"');
  res.send(buildCsv());
}));

app.get('/api/events', wrap((req, res) => {
  res.json({ events: state.events.slice(0, 50) });
}));

/** Demo helper: clears the board (patients and events only, resources stay). */
app.post('/api/reset', wrap((req, res) => {
  state.patients = [];
  state.events = [];
  state.nextPatientId = 1000;
  state.doctors.forEach((d) => { d.available = true; d.currentPatientId = null; });
  state.beds.forEach((b) => { b.occupied = false; b.patientId = null; b.patientName = null; });
  logEvent('RESET', null, 'Board cleared for a fresh demonstration');
  persist();
  res.json({ message: 'ED board cleared - resources released, patients removed.' });
}));

/** Demo helper: the surge shown in the viva. */
app.post('/api/demo-surge', wrap((req, res) => {
  const surge = [
    { name: 'J. Kumar', age: 64, symptoms: 'Chest pain radiating to left arm', priority: 'CRITICAL', heartRate: 128, spo2: 89, bloodPressure: '90/60' },
    { name: 'A. Meera', age: 31, symptoms: 'High fever, dehydration', priority: 'HIGH', heartRate: 112, spo2: 96, bloodPressure: '110/70' },
    { name: 'R. Vasan', age: 45, symptoms: 'Forearm laceration, bleeding controlled', priority: 'MEDIUM' },
    { name: 'S. Iyer', age: 22, symptoms: 'Ankle sprain', priority: 'LOW' },
    { name: 'K. Naveen', age: 58, symptoms: 'Sudden breathlessness, SpO2 falling', priority: 'MEDIUM', heartRate: 118, spo2: 91, bloodPressure: '140/85' },
  ];
  surge.forEach(registerPatient);
  const promoted = vitalsEscalationSweep();
  res.json({
    registered: surge.length,
    escalated: promoted.map((p) => `${p.patient.name} ${p.previous} -> ${p.priority}`),
    message: `Demo surge loaded: ${surge.length} patients registered`,
  });
}));

app.use((req, res) => {
  res.status(404).json({ error: 'NOT_FOUND', message: `No route for ${req.method} ${req.path}` });
});

const restored = loadState();
const server = app.listen(PORT, HOST, () => {
  console.log('=================================================================');
  console.log('  ERverse REST API + dashboard  -  mirroring the Java engine');
  console.log('=================================================================');
  console.log(`  Dashboard : http://localhost:${PORT}/`);
  console.log(`  Health    : http://localhost:${PORT}/api/health`);
  console.log(`  State file: ${DATA_FILE}`);
  console.log(`  Session   : ${restored ? 'restored from disk' : 'fresh ED roster seeded'}`);
  console.log('=================================================================');
});

module.exports = { app, server, comparePatients, buildDashboard };

// Graceful shutdown so the JSON state is never left half-written.
['SIGINT', 'SIGTERM'].forEach((signal) => {
  process.on(signal, () => {
    try {
      fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
      fs.writeFileSync(DATA_FILE, JSON.stringify(state, null, 2));
    } catch (err) {
      console.error('[ERverse][API] Final persist failed:', err.message);
    }
    server.close(() => process.exit(0));
  });
});
