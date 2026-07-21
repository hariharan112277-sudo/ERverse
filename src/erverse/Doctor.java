package erverse;

public class Doctor {
    private final String doctorId;
    private final String name;
    private final String specialization;
    private boolean available;

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
    public void setAvailable(boolean available) { this.available = available; }

    @Override
    public String toString() {
        return String.format("[%s] Dr. %-15s (%-12s) - %s",
                doctorId, name, specialization, available ? "AVAILABLE" : "BUSY");
    }
}
