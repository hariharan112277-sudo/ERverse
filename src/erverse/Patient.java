package erverse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a patient entering the Emergency Department.
 *
 * <p>Implements {@link Comparable} so a {@code java.util.PriorityQueue} can
 * maintain the triage order without any external comparator. Ordering is
 * severity rank first, arrival timestamp second - the rule described in
 * Chapter 5.2.1 of the PBL report.</p>
 */
public class Patient implements Comparable<Patient> {

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_IN_TREATMENT = "IN_TREATMENT";
    public static final String STATUS_DISCHARGED = "DISCHARGED";

    /** Sentinel used when a vital sign was not recorded at triage. */
    public static final int VITAL_NOT_RECORDED = 0;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Monotonic identifier source. The Week 4 race condition described in
     * Section 3.6.1 is closed on two levels: this counter is atomic, and every
     * caller in {@link TriageSystem} is {@code synchronized}.
     */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1000);

    private final int patientId;
    private final String name;
    private final int age;
    private final String symptoms;
    private final LocalDateTime arrivalTime;

    /** Mutable: trained staff may reassess a waiting patient (Section 8.4). */
    private Priority priority;

    private int heartRate;          // bpm
    private int spo2;               // %
    private String bloodPressure;   // "120/80"

    private String status;
    private String assignedDoctorId;
    private String assignedDoctorName;
    private String assignedBedId;

    private final List<String> auditTrail = new ArrayList<>();

    /** Convenience constructor: vitals not recorded at triage. */
    public Patient(String name, int age, String symptoms, Priority priority) {
        this(name, age, symptoms, priority, VITAL_NOT_RECORDED, VITAL_NOT_RECORDED, "-");
    }

    public Patient(String name, int age, String symptoms, Priority priority,
                   int heartRate, int spo2, String bloodPressure) {
        this.patientId = NEXT_ID.incrementAndGet();
        this.name = name;
        this.age = age;
        this.symptoms = symptoms;
        this.priority = Objects.requireNonNull(priority, "priority");
        this.arrivalTime = LocalDateTime.now();
        this.heartRate = heartRate;
        this.spo2 = spo2;
        this.bloodPressure = (bloodPressure == null || bloodPressure.isBlank()) ? "-" : bloodPressure;
        this.status = STATUS_WAITING;
        note("Registered at triage with priority " + priority);
    }

    private Patient(int patientId, String name, int age, String symptoms, Priority priority,
                    LocalDateTime arrivalTime, int heartRate, int spo2, String bloodPressure,
                    String status, String assignedDoctorId, String assignedDoctorName, String assignedBedId) {
        this.patientId = patientId;
        this.name = name;
        this.age = age;
        this.symptoms = symptoms;
        this.priority = priority;
        this.arrivalTime = arrivalTime;
        this.heartRate = heartRate;
        this.spo2 = spo2;
        this.bloodPressure = bloodPressure;
        this.status = status;
        this.assignedDoctorId = assignedDoctorId;
        this.assignedDoctorName = assignedDoctorName;
        this.assignedBedId = assignedBedId;
        NEXT_ID.updateAndGet(current -> Math.max(current, patientId + 1));
    }

    /**
     * Rebuilds a patient from persisted storage (DatabaseManager) so that the
     * queue survives an application restart.
     */
    public static Patient restore(int patientId, String name, int age, String symptoms, Priority priority,
                                  LocalDateTime arrivalTime, int heartRate, int spo2, String bloodPressure,
                                  String status, String assignedDoctorId, String assignedDoctorName,
                                  String assignedBedId) {
        return new Patient(patientId, name, age, symptoms, priority, arrivalTime, heartRate, spo2,
                bloodPressure, status, assignedDoctorId, assignedDoctorName, assignedBedId);
    }

    // ------------------------------------------------------------------
    // Triage ordering
    // ------------------------------------------------------------------

    /**
     * Natural ordering used by the PriorityQueue: lower severity rank first,
     * ties broken by earliest arrival time (FIFO within the same severity band)
     * and finally by patient id, which makes the order fully deterministic when
     * two patients are registered within the same clock tick.
     */
    @Override
    public int compareTo(Patient other) {
        int diff = Integer.compare(this.priority.getRank(), other.priority.getRank());
        if (diff != 0) {
            return diff;
        }
        diff = this.arrivalTime.compareTo(other.arrivalTime);
        if (diff != 0) {
            return diff;
        }
        return Integer.compare(this.patientId, other.patientId);
    }

    /** Reassessment by clinical staff: may move the patient up or down the queue. */
    public void reassess(Priority newPriority, String reason) {
        if (newPriority == null || newPriority == this.priority) {
            return;
        }
        Priority previous = this.priority;
        this.priority = newPriority;
        note("Priority reassessed " + previous + " -> " + newPriority
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
    }

    /**
     * True when the recorded vitals suggest the patient deserves a higher band
     * than the one assigned at the desk (Section 6.4 - the limitation that
     * vitals are not yet used to escalate a waiting patient).
     */
    public boolean vitalsSuggestEscalation() {
        boolean lowOxygen = spo2 != VITAL_NOT_RECORDED && spo2 < 92;
        boolean tachycardic = heartRate != VITAL_NOT_RECORDED && heartRate > 130;
        boolean bradycardic = heartRate != VITAL_NOT_RECORDED && heartRate < 40;
        return lowOxygen || tachycardic || bradycardic;
    }

    /** The band one step more urgent than this patient's current band, or null. */
    public Priority nextMoreUrgentBand() {
        for (Priority p : Priority.values()) {
            if (p.getRank() == priority.getRank() - 1) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public int getPatientId() { return patientId; }
    public String getName() { return name; }
    public int getAge() { return age; }
    public String getSymptoms() { return symptoms; }
    public Priority getPriority() { return priority; }
    public LocalDateTime getArrivalTime() { return arrivalTime; }
    public String getStatus() { return status; }
    public String getAssignedDoctor() { return assignedDoctorId; }
    public String getAssignedDoctorName() { return assignedDoctorName; }
    public String getAssignedBed() { return assignedBedId; }
    public int getHeartRate() { return heartRate; }
    public int getSpo2() { return spo2; }
    public String getBloodPressure() { return bloodPressure; }

    public void setStatus(String status) { this.status = status; }

    public void assignDoctor(String doctorId, String doctorName) {
        this.assignedDoctorId = doctorId;
        this.assignedDoctorName = doctorName;
    }

    public void assignBed(String bedId) { this.assignedBedId = bedId; }

    public void releaseResources() {
        this.assignedDoctorId = null;
        this.assignedDoctorName = null;
        this.assignedBedId = null;
    }

    public final void note(String event) {
        auditTrail.add(LocalDateTime.now().format(STAMP) + "  " + event);
    }

    public List<String> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    /** Vitals rendered for the console / dashboard, e.g. {@code 88 bpm, 97% SpO2, 120/80}. */
    public String vitalsSummary() {
        String hr = heartRate == VITAL_NOT_RECORDED ? "HR n/r" : "HR " + heartRate;
        String ox = spo2 == VITAL_NOT_RECORDED ? "SpO2 n/r" : "SpO2 " + spo2 + "%";
        String bp = "-".equals(bloodPressure) ? "BP n/r" : "BP " + bloodPressure;
        return hr + ", " + ox + ", " + bp;
    }

    public String arrivalClock() { return arrivalTime.format(CLOCK); }

    public String arrivalStamp() { return arrivalTime.format(STAMP); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Patient)) return false;
        return patientId == ((Patient) o).patientId;
    }

    @Override
    public int hashCode() { return Integer.hashCode(patientId); }

    @Override
    public String toString() {
        return String.format("[ID:%d] %-15s Age:%-3d Priority:%-8s Symptoms:%-25s Arrived:%s Status:%s",
                patientId, name, age, priority, symptoms, arrivalClock(), status);
    }
}
