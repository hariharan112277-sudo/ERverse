package erverse;

/**
 * Represents an attending physician. Availability is the flag consulted by
 * {@link TriageSystem#treatNextPatient()}; {@code currentPatientId} lets the
 * roster show who each doctor is treating.
 */
public class Doctor {

    private final String doctorId;
    private final String name;
    private final String specialization;
    private boolean available;
    private Integer currentPatientId;

    public Doctor(String doctorId, String name, String specialization) {
        this.doctorId = doctorId;
        this.name = name;
        this.specialization = specialization;
        this.available = true;
    }

    public String getDoctorId() { return doctorId; }

    public String getName() { return name; }

    public String getSpecialization() { return specialization; }

    public boolean isAvailable() { return available; }

    public void setAvailable(boolean available) {
        this.available = available;
        if (available) {
            this.currentPatientId = null;
        }
    }

    public Integer getCurrentPatientId() { return currentPatientId; }

    public void assignTo(int patientId) {
        this.available = false;
        this.currentPatientId = patientId;
    }

    public boolean hasSpecialization(String specializationWanted) {
        return specialization != null
                && specialization.equalsIgnoreCase(specializationWanted.trim());
    }

    @Override
    public String toString() {
        return String.format("[%s] Dr. %-15s (%-12s) - %s",
                doctorId, name, specialization,
                available ? "AVAILABLE"
                          : "BUSY" + (currentPatientId == null ? "" : " with Patient #" + currentPatientId));
    }
}
