package erverse;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Core engine of ERverse. Wraps a java.util.PriorityQueue<Patient> so that
 * the most severe patient is always served first (Patient implements
 * Comparable using severity rank + arrival time). Also tracks doctors,
 * beds and ambulances so treatment can be simulated end to end.
 */
public class TriageSystem {

    private final PriorityQueue<Patient> waitingQueue = new PriorityQueue<>();
    private final List<Patient> allPatients = new ArrayList<>();
    private final List<Doctor> doctors = new ArrayList<>();
    private final List<Bed> beds = new ArrayList<>();
    private final List<Ambulance> ambulances = new ArrayList<>();

    public Patient registerPatient(String name, int age, String symptoms, Priority priority) {
        Patient p = new Patient(name, age, symptoms, priority);
        waitingQueue.add(p);
        allPatients.add(p);
        return p;
    }

    /** Pulls the highest-priority waiting patient and assigns the first free doctor + bed. */
    public String treatNextPatient() {
        Patient next = waitingQueue.poll();
        if (next == null) {
            return "No patients waiting.";
        }
        Doctor doctor = findAvailableDoctor();
        Bed bed = findFreeBed();

        StringBuilder sb = new StringBuilder();
        sb.append("Now treating: ").append(next.getName())
          .append(" (Priority: ").append(next.getPriority()).append(")\n");

        if (doctor != null) {
            doctor.setAvailable(false);
            next.setAssignedDoctor(doctor.getName());
            sb.append("  Assigned Doctor: Dr. ").append(doctor.getName()).append("\n");
        } else {
            sb.append("  No doctor currently available - patient held at bedside.\n");
        }

        if (bed != null) {
            bed.occupy(next.getPatientId());
            next.setAssignedBed(bed.getBedId());
            sb.append("  Assigned Bed: ").append(bed.getBedId()).append("\n");
        } else {
            sb.append("  No bed currently free.\n");
        }

        next.setStatus("IN_TREATMENT");
        return sb.toString();
    }

    public void dischargePatient(int patientId, String doctorName, String bedId) {
        for (Patient p : allPatients) {
            if (p.getPatientId() == patientId) {
                p.setStatus("DISCHARGED");
            }
        }
        for (Doctor d : doctors) {
            if (d.getName().equalsIgnoreCase(doctorName)) d.setAvailable(true);
        }
        for (Bed b : beds) {
            if (b.getBedId().equalsIgnoreCase(bedId)) b.release();
        }
    }

    private Doctor findAvailableDoctor() {
        for (Doctor d : doctors) if (d.isAvailable()) return d;
        return null;
    }

    private Bed findFreeBed() {
        for (Bed b : beds) if (!b.isOccupied()) return b;
        return null;
    }

    public void addDoctor(Doctor d) { doctors.add(d); }
    public void addBed(Bed b) { beds.add(b); }
    public void addAmbulance(Ambulance a) { ambulances.add(a); }

    public List<Doctor> getDoctors() { return doctors; }
    public List<Bed> getBeds() { return beds; }
    public List<Ambulance> getAmbulances() { return ambulances; }
    public List<Patient> getAllPatients() { return allPatients; }
    public PriorityQueue<Patient> getWaitingQueue() { return waitingQueue; }

    public long countByStatus(String status) {
        return allPatients.stream().filter(p -> p.getStatus().equals(status)).count();
    }
}
