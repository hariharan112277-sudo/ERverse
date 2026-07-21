package erverse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a patient entering the Emergency Department.
 */
public class Patient implements Comparable<Patient> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static int counter = 1000;

    private final int patientId;
    private final String name;
    private final int age;
    private final String symptoms;
    private final Priority priority;
    private final LocalDateTime arrivalTime;
    private String status; // WAITING, IN_TREATMENT, DISCHARGED
    private String assignedDoctor;
    private String assignedBed;

    public Patient(String name, int age, String symptoms, Priority priority) {
        this.patientId = ++counter;
        this.name = name;
        this.age = age;
        this.symptoms = symptoms;
        this.priority = priority;
        this.arrivalTime = LocalDateTime.now();
        this.status = "WAITING";
    }

    // Natural ordering used by the PriorityQueue: lower rank first,
    // ties broken by earliest arrival time (FIFO within same severity).
    @Override
    public int compareTo(Patient other) {
        int diff = this.priority.getRank() - other.priority.getRank();
        if (diff != 0) return diff;
        return this.arrivalTime.compareTo(other.arrivalTime);
    }

    public int getPatientId() { return patientId; }
    public String getName() { return name; }
    public int getAge() { return age; }
    public String getSymptoms() { return symptoms; }
    public Priority getPriority() { return priority; }
    public LocalDateTime getArrivalTime() { return arrivalTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAssignedDoctor() { return assignedDoctor; }
    public void setAssignedDoctor(String assignedDoctor) { this.assignedDoctor = assignedDoctor; }
    public String getAssignedBed() { return assignedBed; }
    public void setAssignedBed(String assignedBed) { this.assignedBed = assignedBed; }

    @Override
    public String toString() {
        return String.format("[ID:%d] %-15s Age:%-3d Priority:%-8s Symptoms:%-25s Arrived:%s Status:%s",
                patientId, name, age, priority, symptoms, arrivalTime.format(FMT), status);
    }
}
