package erverse;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

/**
 * Core engine of ERverse. Wraps a {@code java.util.PriorityQueue<Patient>} so
 * that the most severe patient is always served first, and keeps doctors, beds
 * and ambulances consistent with that queue.
 *
 * <p>Thread safety: {@code java.util.PriorityQueue} is not thread-safe, so -
 * exactly as decided in the Week 5 review - every state-changing method of this
 * class is {@code synchronized}, which makes the instance monitor the single
 * lock that protects the queue, the resource lists and the persistence calls
 * made from them.</p>
 *
 * <p>Defect fix versus the report: Section 5.2.3 shows {@code treatNextPatient()}
 * polling the patient off the heap <em>before</em> checking that a doctor and bed
 * exist. If either resource is exhausted the patient would vanish from the queue.
 * This implementation peeks first, secures both resources, and only then polls,
 * so a failed dispatch leaves the patient exactly where they were.</p>
 */
public class TriageSystem {

    private static final DateTimeFormatter CSV_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_QUEUE_PREVIEW = 15;

    private final PriorityQueue<Patient> waitingQueue = new PriorityQueue<>();
    private final List<Patient> allPatients = new ArrayList<>();
    private final List<Doctor> doctors = new ArrayList<>();
    private final List<Bed> beds = new ArrayList<>();
    private final List<Ambulance> ambulances = new ArrayList<>();
    private final List<String> eventLog = new ArrayList<>();

    private final DatabaseManager db;

    /** In-memory only; used by the console demo when no database path is given. */
    public TriageSystem() {
        this(DatabaseManager.disabled());
    }

    public TriageSystem(DatabaseManager db) {
        this.db = db == null ? DatabaseManager.disabled() : db;
    }

    // ==================================================================
    // Registration
    // ==================================================================

    public synchronized Patient registerPatient(String name, int age, String symptoms, Priority priority)
            throws InvalidPatientDataException {
        return registerPatient(name, age, symptoms, priority,
                Patient.VITAL_NOT_RECORDED, Patient.VITAL_NOT_RECORDED, "-");
    }

    /**
     * Registers a patient and inserts them into the severity-ordered queue.
     * The method is {@code synchronized}: it is one of the three boundaries
     * that closed the duplicate-patientId race condition (Section 3.6.1).
     */
    public synchronized Patient registerPatient(String name, int age, String symptoms, Priority priority,
                                                int heartRate, int spo2, String bloodPressure)
            throws InvalidPatientDataException {

        validate(name, age, heartRate, spo2, bloodPressure);
        if (priority == null) {
            throw new InvalidPatientDataException("Severity is required (CRITICAL/HIGH/MEDIUM/LOW)");
        }

        Patient patient = new Patient(name.trim(), age, symptoms == null ? "" : symptoms.trim(),
                priority, heartRate, spo2, bloodPressure);
        waitingQueue.add(patient);
        allPatients.add(patient);

        db.savePatient(patient);
        db.logEvent("REGISTER", patient.getPatientId(),
                "priority=" + priority + " symptoms=" + patient.getSymptoms());
        log("Registered " + patient.getName() + " (#" + patient.getPatientId() + ") as " + priority);
        return patient;
    }

    private void validate(String name, int age, int heartRate, int spo2, String bloodPressure)
            throws InvalidPatientDataException {
        if (name == null || name.isBlank()) {
            throw new InvalidPatientDataException("Patient name is required");
        }
        if (age <= 0 || age > 120) {
            throw new InvalidPatientDataException("Age must be between 1 and 120 (got " + age + ")");
        }
        if (heartRate != Patient.VITAL_NOT_RECORDED && (heartRate < 20 || heartRate > 250)) {
            throw new InvalidPatientDataException("Heart rate out of range (20-250 bpm)");
        }
        if (spo2 != Patient.VITAL_NOT_RECORDED && (spo2 < 0 || spo2 > 100)) {
            throw new InvalidPatientDataException("SpO2 out of range (0-100 %)");
        }
        if (bloodPressure != null && !bloodPressure.isBlank() && !"-".equals(bloodPressure.trim())
                && !bloodPressure.trim().matches("\\d{2,3}/\\d{2,3}")) {
            throw new InvalidPatientDataException("Blood pressure must look like 120/80");
        }
    }

    // ==================================================================
    // Treatment dispatch
    // ==================================================================

    /**
     * Peeks the heap root, secures a doctor and a bed, and only then removes the
     * patient from the queue. If a resource is unavailable a specific checked
     * exception is raised and the patient stays queued (Section 5.2.3, corrected).
     */
    public synchronized String treatNextPatient()
            throws NoDoctorsAvailableException, NoBedsAvailableException {

        Patient next = waitingQueue.peek();
        if (next == null) {
            return "No patients waiting.";
        }

        Optional<Doctor> doctorOpt = selectDoctor(next.getPriority());
        if (doctorOpt.isEmpty()) {
            throw new NoDoctorsAvailableException("No doctor available for " + next.getName()
                    + " (Priority " + next.getPriority() + "). Patient remains #1 in the queue.");
        }
        Optional<Bed> bedOpt = selectBed(next.getPriority());
        if (bedOpt.isEmpty()) {
            throw new NoBedsAvailableException("No bed of a suitable type free for " + next.getName()
                    + " (Priority " + next.getPriority() + "). Patient remains #1 in the queue.");
        }

        // Both resources are secured - now the dequeue is safe.
        waitingQueue.poll();
        Doctor doctor = doctorOpt.get();
        Bed bed = bedOpt.get();

        doctor.assignTo(next.getPatientId());
        bed.occupy(next.getPatientId(), next.getName());
        next.assignDoctor(doctor.getDoctorId(), doctor.getName());
        next.assignBed(bed.getBedId());
        next.setStatus(Patient.STATUS_IN_TREATMENT);
        next.note("Treatment started - Dr. " + doctor.getName() + " (" + doctor.getDoctorId()
                + "), bed " + bed.getBedId() + " (" + bed.getType().name() + ")");

        db.savePatient(next);
        db.updateDoctor(doctor);
        db.updateBed(bed);
        db.logEvent("TREAT", next.getPatientId(),
                "doctor=" + doctor.getDoctorId() + " bed=" + bed.getBedId());

        StringBuilder sb = new StringBuilder();
        sb.append("Now treating: ").append(next.getName())
          .append(" (Priority: ").append(next.getPriority()).append(")\n");
        sb.append("  Assigned Doctor: Dr. ").append(doctor.getName())
          .append(" [").append(doctor.getDoctorId()).append("] (").append(doctor.getSpecialization()).append(")\n");
        sb.append("  Assigned Bed: ").append(bed.getBedId())
          .append(" (").append(bed.getType().getLabel()).append(")\n");
        if (doctorSpecFallback(next.getPriority(), doctor)) {
            sb.append("  NOTE: no ").append(preferredSpecializations(next.getPriority()))
              .append(" specialist was free - assigned the first available doctor.\n");
        }
        log("Treated " + next.getName() + " (#" + next.getPatientId() + ")");
        return sb.toString();
    }

    /** Preferred specialisations per severity band; used for the reporting note only. */
    private List<String> preferredSpecializations(Priority priority) {
        switch (priority) {
            case CRITICAL: return List.of("Trauma", "Emergency", "Cardiology");
            case HIGH:     return List.of("Cardiology", "Emergency", "General");
            default:       return List.of("General", "Emergency");
        }
    }

    private boolean doctorSpecFallback(Priority priority, Doctor doctor) {
        return preferredSpecializations(priority).stream()
                .noneMatch(doctor::hasSpecialization);
    }

    /**
     * Resource allocation - two passes: a doctor whose specialisation matches the
     * severity band, otherwise the first available doctor (a busy ED should never
     * hold a critical patient back for a paperwork-perfect match).
     */
    private Optional<Doctor> selectDoctor(Priority priority) {
        for (String wanted : preferredSpecializations(priority)) {
            for (Doctor d : doctors) {
                if (d.isAvailable() && d.hasSpecialization(wanted)) {
                    return Optional.of(d);
                }
            }
        }
        return doctors.stream().filter(Doctor::isAvailable).findFirst();
    }

    /**
     * Bed allocation by acuity: critical patients take ICU first (and will take any
     * free bed rather than wait), high-acuity patients prefer a trauma bay, and the
     * lower bands keep out of ICU so it stays reserved for resuscitations.
     */
    private Optional<Bed> selectBed(Priority priority) {
        List<Bed.BedType> preference;
        switch (priority) {
            case CRITICAL: preference = List.of(Bed.BedType.ICU, Bed.BedType.TRAUMA, Bed.BedType.GENERAL); break;
            case HIGH:     preference = List.of(Bed.BedType.TRAUMA, Bed.BedType.GENERAL, Bed.BedType.ICU); break;
            default:       preference = List.of(Bed.BedType.GENERAL, Bed.BedType.TRAUMA); break;
        }
        for (Bed.BedType type : preference) {
            for (Bed b : beds) {
                if (!b.isOccupied() && b.getType() == type) {
                    return Optional.of(b);
                }
            }
        }
        return Optional.empty();
    }

    // ==================================================================
    // Discharge, reassessment, escalation
    // ==================================================================

    /**
     * Discharges a patient and releases the doctor and bed recorded on their own
     * record - the caller no longer has to remember who was assigned (TC-03).
     */
    public synchronized String dischargePatient(int patientId) throws TriageException {
        Patient patient = findPatientOrThrow(patientId);
        if (Patient.STATUS_DISCHARGED.equals(patient.getStatus())) {
            // Guards the "double-click" defect seen in the Week 10 test run.
            throw new TriageException("Patient #" + patientId + " (" + patient.getName()
                    + ") is already discharged.");
        }

        String doctorId = patient.getAssignedDoctor();
        String bedId = patient.getAssignedBed();

        if (doctorId != null) {
            doctors.stream().filter(d -> d.getDoctorId().equals(doctorId)).forEach(d -> {
                d.setAvailable(true);
                db.updateDoctor(d);
            });
        }
        if (bedId != null) {
            beds.stream().filter(b -> b.getBedId().equals(bedId)).forEach(b -> {
                b.release();
                db.updateBed(b);
            });
        }

        patient.setStatus(Patient.STATUS_DISCHARGED);
        patient.note("Discharged from " + (bedId == null ? "ED" : "bed " + bedId));
        db.savePatient(patient);
        db.logEvent("DISCHARGE", patientId, "doctor=" + doctorId + " bed=" + bedId);

        StringBuilder sb = new StringBuilder();
        sb.append("Discharged: ").append(patient.getName()).append(" (#").append(patientId).append(")\n");
        sb.append("  Doctor ").append(doctorId == null ? "-" : doctorId).append(" -> AVAILABLE\n");
        sb.append("  Bed ").append(bedId == null ? "-" : bedId).append(" -> FREE\n");
        log("Discharged " + patient.getName() + " (#" + patientId + ")");
        return sb.toString();
    }

    /**
     * Clinical reassessment of a waiting patient (Section 8.4: the queue is
     * decision support, so staff must be able to override it). Because the heap
     * ordering depends on the priority field, the patient is removed and
     * re-inserted so that the heap invariant is restored.
     */
    public synchronized String reassessPatient(int patientId, Priority newPriority) throws TriageException {
        Patient patient = findPatientOrThrow(patientId);
        if (newPriority == null) {
            throw new InvalidPatientDataException("New severity is required");
        }
        if (Patient.STATUS_DISCHARGED.equals(patient.getStatus())) {
            throw new TriageException("Patient #" + patientId + " has already been discharged.");
        }
        Priority previous = patient.getPriority();
        if (previous == newPriority) {
            return "Priority unchanged for " + patient.getName() + " (" + previous + ").";
        }

        boolean wasWaiting = Patient.STATUS_WAITING.equals(patient.getStatus());
        if (wasWaiting) {
            waitingQueue.remove(patient);          // re-heapify under the monitor
        }
        patient.reassess(newPriority, "staff reassessment");

        int position = -1;
        if (wasWaiting) {
            waitingQueue.add(patient);
            position = queuePosition(patientId);
        }
        db.savePatient(patient);
        db.logEvent("REASSESS", patientId, previous + " -> " + newPriority);

        StringBuilder sb = new StringBuilder();
        sb.append("Reassessed: ").append(patient.getName()).append(" (#").append(patientId).append(")  ")
          .append(previous).append(" -> ").append(newPriority).append("\n");
        if (position > 0) {
            sb.append("  New queue position: ").append(position).append("\n");
        } else {
            sb.append("  Patient is already in treatment; queue order unaffected.\n");
        }
        log("Reassessed " + patient.getName() + " to " + newPriority);
        return sb.toString();
    }

    /**
     * Opt-in escalation from recorded vitals - the answer to limitation 6.4: a
     * waiting patient whose SpO2 drops below 92 % or whose heart rate leaves the
     * safe band is promoted one severity step.
     *
     * @return one human-readable line per patient promoted
     */
    public synchronized List<String> applyVitalsEscalation() {
        List<String> promoted = new ArrayList<>();
        List<Patient> waiting = new ArrayList<>(waitingQueue);
        for (Patient p : waiting) {
            if (!p.vitalsSuggestEscalation()) {
                continue;
            }
            Priority next = p.nextMoreUrgentBand();
            if (next == null) {
                continue;   // already CRITICAL
            }
            Priority previous = p.getPriority();
            waitingQueue.remove(p);
            p.reassess(next, "vitals escalation: " + p.vitalsSummary());
            waitingQueue.add(p);
            db.savePatient(p);
            db.logEvent("ESCALATE", p.getPatientId(), previous + " -> " + next + " (" + p.vitalsSummary() + ")");
            promoted.add(String.format("Patient #%d %s %s -> %s  (%s)",
                    p.getPatientId(), p.getName(), previous, next, p.vitalsSummary()));
            log("Vitals escalation: " + p.getName() + " -> " + next);
        }
        return promoted;
    }

    // ==================================================================
    // Ambulances
    // ==================================================================

    /** Advances every en-route unit by one simulated minute. */
    public synchronized List<String> tickAmbulances() {
        List<String> arrived = new ArrayList<>();
        for (Ambulance a : ambulances) {
            boolean changed = a.tick();
            db.updateAmbulance(a);
            if (changed) {
                arrived.add("Ambulance " + a.getAmbulanceId() + " has ARRIVED at the ED"
                        + (a.getPatientOnBoard() == null ? "" : " carrying " + a.getPatientOnBoard()
                        + " (" + a.getPatientPriority() + ") - prepare bed"));
                db.logEvent("AMBULANCE_ARRIVED", null, a.getAmbulanceId());
            }
        }
        arrived.forEach(this::log);
        return arrived;
    }

    public synchronized String dispatchAmbulance(String ambulanceId, int etaMinutes)
            throws TriageException {
        Ambulance a = ambulances.stream()
                .filter(x -> x.getAmbulanceId().equalsIgnoreCase(ambulanceId))
                .findFirst()
                .orElseThrow(() -> new TriageException("Unknown ambulance " + ambulanceId));
        a.dispatch(etaMinutes);
        db.updateAmbulance(a);
        db.logEvent("AMBULANCE_DISPATCH", null, ambulanceId + " eta=" + etaMinutes);
        return "Ambulance " + a.getAmbulanceId() + " dispatched, ETA " + a.getEtaMinutes() + " min.";
    }

    /** Pre-arrival notification (Section 8.3.2). */
    public synchronized String notifyIncomingPatient(String ambulanceId, String patientName, Priority priority)
            throws TriageException {
        Ambulance a = ambulances.stream()
                .filter(x -> x.getAmbulanceId().equalsIgnoreCase(ambulanceId))
                .findFirst()
                .orElseThrow(() -> new TriageException("Unknown ambulance " + ambulanceId));
        a.notifyPatientOnBoard(patientName, priority);
        db.updateAmbulance(a);
        db.logEvent("PRE_ARRIVAL", null, patientName + " via " + ambulanceId + " priority=" + priority);
        return "Pre-arrival logged: " + patientName + " (" + priority + ") inbound on "
                + a.getAmbulanceId() + ", ETA " + a.getEtaMinutes() + " min.";
    }

    // ==================================================================
    // Queries and reporting
    // ==================================================================

    /** Heap root - the next patient to be treated (O(1)). */
    public synchronized Optional<Patient> peekNextPatient() {
        return Optional.ofNullable(waitingQueue.peek());
    }

    /** Snapshot of the queue in treatment order (O(n log n), read-only copy). */
    public synchronized List<Patient> getWaitingQueueSorted() {
        List<Patient> copy = new ArrayList<>(waitingQueue);
        Collections.sort(copy);
        return copy;
    }

    public synchronized List<Doctor> getDoctors() { return new ArrayList<>(doctors); }

    public synchronized List<Bed> getBeds() { return new ArrayList<>(beds); }

    public synchronized List<Ambulance> getAmbulances() { return new ArrayList<>(ambulances); }

    public synchronized List<Patient> getAllPatients() { return new ArrayList<>(allPatients); }

    public synchronized List<Patient> getInTreatment() {
        return allPatients.stream()
                .filter(p -> Patient.STATUS_IN_TREATMENT.equals(p.getStatus()))
                .collect(Collectors.toList());
    }

    public synchronized int waitingCount() { return waitingQueue.size(); }

    public synchronized long countByStatus(String status) {
        return allPatients.stream().filter(p -> p.getStatus().equals(status)).count();
    }

    public synchronized long countWaitingByPriority(Priority priority) {
        return waitingQueue.stream().filter(p -> p.getPriority() == priority).count();
    }

    public synchronized long countFreeBeds() {
        return beds.stream().filter(b -> !b.isOccupied()).count();
    }

    public synchronized long countAvailableDoctors() {
        return doctors.stream().filter(Doctor::isAvailable).count();
    }

    public synchronized long countAmbulancesEnRoute() {
        return ambulances.stream().filter(a -> a.getStatusEnum() == Ambulance.Status.EN_ROUTE).count();
    }

    public synchronized int queuePosition(int patientId) {
        List<Patient> ordered = getWaitingQueueSorted();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getPatientId() == patientId) {
                return i + 1;
            }
        }
        return -1;
    }

    public synchronized Optional<Patient> findPatient(int patientId) {
        return allPatients.stream().filter(p -> p.getPatientId() == patientId).findFirst();
    }

    private Patient findPatientOrThrow(int patientId) throws TriageException {
        return findPatient(patientId)
                .orElseThrow(() -> new TriageException("No patient with id #" + patientId));
    }

    public synchronized List<String> getEventLog() { return new ArrayList<>(eventLog); }

    /** Full text report printed by the console (menu item 10). */
    public synchronized String dailyReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=================== DAILY EMERGENCY REPORT ===================\n");
        sb.append(String.format("Generated at              : %s%n", LocalDateTime.now().format(CSV_STAMP)));
        sb.append(String.format("Total patients registered : %d%n", allPatients.size()));
        sb.append(String.format("Waiting                   : %d%n", countByStatus(Patient.STATUS_WAITING)));
        sb.append(String.format("In treatment              : %d%n", countByStatus(Patient.STATUS_IN_TREATMENT)));
        sb.append(String.format("Discharged                : %d%n", countByStatus(Patient.STATUS_DISCHARGED)));
        sb.append("--------------------------------------------------------------\n");
        sb.append("Waiting by severity       : ");
        for (Priority p : Priority.values()) {
            sb.append(p).append("=").append(countWaitingByPriority(p)).append("  ");
        }
        sb.append("\nBeds                      : ").append(countFreeBeds()).append(" free / ")
          .append(beds.size()).append(" total\n");
        sb.append("Doctors                   : ").append(countAvailableDoctors()).append(" available / ")
          .append(doctors.size()).append(" total\n");
        sb.append("Ambulances en route       : ").append(countAmbulancesEnRoute()).append("\n");
        sb.append("--------------------------------------------------------------\n");

        List<Patient> ordered = getWaitingQueueSorted();
        sb.append("Waiting queue (treatment order):\n");
        if (ordered.isEmpty()) {
            sb.append("  (empty)\n");
        } else {
            int shown = Math.min(MAX_QUEUE_PREVIEW, ordered.size());
            for (int i = 0; i < shown; i++) {
                Patient p = ordered.get(i);
                sb.append(String.format("  %2d. %s%n", i + 1, p));
            }
            if (ordered.size() > shown) {
                sb.append("  ... ").append(ordered.size() - shown).append(" more\n");
            }
        }

        sb.append("\nIn treatment:\n");
        List<Patient> inTreatment = getInTreatment();
        if (inTreatment.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (Patient p : inTreatment) {
                sb.append(String.format("  %s | Dr. %s | bed %s | %s%n",
                        p.getName(), p.getAssignedDoctorName(), p.getAssignedBed(), p.vitalsSummary()));
            }
        }

        sb.append("\nDischarged today:\n");
        List<Patient> discharged = allPatients.stream()
                .filter(p -> Patient.STATUS_DISCHARGED.equals(p.getStatus()))
                .collect(Collectors.toList());
        if (discharged.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (Patient p : discharged) {
                sb.append("  ").append(p).append("\n");
            }
        }
        sb.append("==============================================================");
        return sb.toString();
    }

    /**
     * Writes the daily report as CSV (test case TC-04). Values are quoted so
     * free-text symptom fields cannot break the column layout.
     *
     * @return number of patient rows written
     */
    public synchronized int exportDailyReportCsv(Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        int rows = 0;
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write("patient_id,name,age,priority,status,arrival_time,heart_rate,spo2,blood_pressure,"
                    + "assigned_doctor,assigned_bed,symptoms");
            w.newLine();
            for (Patient p : allPatients) {
                w.write(csv(p.getPatientId()) + "," + csv(p.getName()) + "," + p.getAge() + ","
                        + csv(p.getPriority().name()) + "," + csv(p.getStatus()) + ","
                        + csv(p.arrivalStamp()) + ","
                        + (p.getHeartRate() == Patient.VITAL_NOT_RECORDED ? "" : p.getHeartRate()) + ","
                        + (p.getSpo2() == Patient.VITAL_NOT_RECORDED ? "" : p.getSpo2()) + ","
                        + csv(p.getBloodPressure()) + "," + csv(p.getAssignedDoctor()) + ","
                        + csv(p.getAssignedBed()) + "," + csv(p.getSymptoms()));
                w.newLine();
                rows++;
            }
            w.write("summary,total=" + allPatients.size()
                    + ",waiting=" + countByStatus(Patient.STATUS_WAITING)
                    + ",in_treatment=" + countByStatus(Patient.STATUS_IN_TREATMENT)
                    + ",discharged=" + countByStatus(Patient.STATUS_DISCHARGED)
                    + ",beds_free=" + countFreeBeds()
                    + ",doctors_available=" + countAvailableDoctors());
            w.newLine();
        }
        log("Exported daily report CSV (" + rows + " patients) -> " + target);
        db.logEvent("REPORT_EXPORT", null, target.toString());
        return rows;
    }

    private static String csv(int value) {
        return Integer.toString(value);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\"", "\"\"");
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v + "\"" : v;
    }

    // ==================================================================
    // Wiring from Main / DatabaseManager
    // ==================================================================

    public synchronized void addDoctor(Doctor d) {
        doctors.add(d);
        db.saveDoctor(d);
    }

    public synchronized void addBed(Bed b) {
        beds.add(b);
        db.saveBed(b);
    }

    public synchronized void addAmbulance(Ambulance a) {
        ambulances.add(a);
        db.saveAmbulance(a);
    }

    /** Used by DatabaseManager.loadInto - no write back to the database. */
    synchronized void restoreDoctor(Doctor d) { doctors.add(d); }

    synchronized void restoreBed(Bed b) { beds.add(b); }

    synchronized void restoreAmbulance(Ambulance a) { ambulances.add(a); }

    synchronized void restorePatient(Patient p, boolean requeue) {
        allPatients.add(p);
        if (requeue) {
            waitingQueue.add(p);
        }
    }

    public synchronized boolean hasResources() {
        return !doctors.isEmpty() && !beds.isEmpty();
    }

    public synchronized int totalDoctors() { return doctors.size(); }

    public synchronized int totalBeds() { return beds.size(); }

    public synchronized int totalAmbulances() { return ambulances.size(); }

    private void log(String message) {
        eventLog.add(LocalDateTime.now().format(CSV_STAMP) + "  " + message);
    }
}
