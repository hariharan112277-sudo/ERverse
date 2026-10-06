package erverse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-03 - discharge releases resources, and reassessment re-heapifies the queue
 * (including the flaky double-discharge case noted in the Week 10 log).
 */
class DischargeAndReassessmentTest {

    private TriageSystem system;

    private TriageSystem seeded() {
        TriageSystem s = new TriageSystem();
        s.addDoctor(new Doctor("D01", "Anitha Raj", "Trauma"));
        s.addBed(new Bed("BED-101", Bed.BedType.ICU));
        s.addBed(new Bed("BED-102", Bed.BedType.TRAUMA));
        return s;
    }

    @Test
    @DisplayName("TC-03: discharge frees the assigned bed and doctor")
    void dischargeReleasesResources() throws Exception {
        system = seeded();
        Patient p = system.registerPatient("J. Kumar", 64, "Cardiac arrest", Priority.CRITICAL);
        system.treatNextPatient();
        String doctorId = system.findPatient(p.getPatientId()).orElseThrow().getAssignedDoctor();
        String bedId = system.findPatient(p.getPatientId()).orElseThrow().getAssignedBed();

        system.dischargePatient(p.getPatientId());

        assertEquals(Patient.STATUS_DISCHARGED, system.findPatient(p.getPatientId()).orElseThrow().getStatus());
        Bed bed = system.getBeds().stream().filter(b -> b.getBedId().equals(bedId)).findFirst().orElseThrow();
        assertFalse(bed.isOccupied(), "bed " + bedId + " must return to FREE");
        assertNull(bed.getOccupiedByPatientId());
        Doctor doctor = system.getDoctors().stream()
                .filter(d -> d.getDoctorId().equals(doctorId)).findFirst().orElseThrow();
        assertTrue(doctor.isAvailable(), "doctor " + doctorId + " must return to AVAILABLE");
        assertNull(doctor.getCurrentPatientId());
        assertEquals(0, system.getInTreatment().size());
        assertEquals(2, system.countFreeBeds());
    }

    @Test
    @DisplayName("A second discharge (double click) fails loudly instead of corrupting state")
    void doubleDischargeIsRejected() throws Exception {
        system = seeded();
        Patient p = system.registerPatient("A. Meera", 31, "Chest pain", Priority.HIGH);
        system.treatNextPatient();
        system.dischargePatient(p.getPatientId());

        TriageException ex = assertThrows(TriageException.class,
                () -> system.dischargePatient(p.getPatientId()));
        assertTrue(ex.getMessage().contains("already discharged"));
        assertEquals(2, system.countFreeBeds(), "both beds are free and stay free");
        assertEquals(1, system.countAvailableDoctors());
    }

    @Test
    @DisplayName("Discharging an unknown id raises a TriageException")
    void unknownPatientDischarge() {
        system = seeded();
        assertThrows(TriageException.class, () -> system.dischargePatient(999999));
    }

    @Test
    @DisplayName("Reassessment promotes a waiting patient to the head of the queue")
    void reassessmentReordersQueue() throws Exception {
        system = seeded();
        Patient low = system.registerPatient("Low", 20, "sprain", Priority.LOW);
        Patient medium = system.registerPatient("Medium", 40, "fever", Priority.MEDIUM);
        assertEquals(2, system.queuePosition(low.getPatientId()));

        String message = system.reassessPatient(low.getPatientId(), Priority.CRITICAL);

        assertEquals(Priority.CRITICAL, system.findPatient(low.getPatientId()).orElseThrow().getPriority());
        assertEquals(1, system.queuePosition(low.getPatientId()));
        assertEquals(2, system.queuePosition(medium.getPatientId()));
        assertEquals(low.getPatientId(), system.peekNextPatient().orElseThrow().getPatientId());
        assertTrue(message.contains("LOW -> CRITICAL"));
    }

    @Test
    @DisplayName("Reassessing downwards also re-heapifies correctly")
    void downwardReassessmentReordersQueue() throws Exception {
        system = seeded();
        Patient low = system.registerPatient("Low", 20, "sprain", Priority.LOW);
        Patient critical = system.registerPatient("Critical", 60, "arrest", Priority.CRITICAL);
        assertEquals(1, system.queuePosition(critical.getPatientId()));

        // Over-triage corrected at the bedside: both patients are now LOW, so the
        // earlier arrival (registered first) takes the head of the queue again.
        system.reassessPatient(critical.getPatientId(), Priority.LOW);

        assertEquals(2, system.queuePosition(critical.getPatientId()));
        assertEquals(1, system.queuePosition(low.getPatientId()));
        assertEquals(low.getPatientId(), system.peekNextPatient().orElseThrow().getPatientId());
    }

    @Test
    @DisplayName("Vitals escalation sweep promotes only patients whose vitals demand it")
    void vitalsEscalationPromotesAtRiskPatients() throws Exception {
        system = seeded();
        Patient hypoxic = system.registerPatient("K. Naveen", 58, "breathlessness",
                Priority.MEDIUM, 118, 91, "140/85");
        Patient stable = system.registerPatient("Stable", 30, "mild fever",
                Priority.LOW, 78, 98, "120/80");

        var promoted = system.applyVitalsEscalation();

        assertEquals(1, promoted.size());
        assertEquals(Priority.HIGH, system.findPatient(hypoxic.getPatientId()).orElseThrow().getPriority());
        assertEquals(Priority.LOW, system.findPatient(stable.getPatientId()).orElseThrow().getPriority());
        assertFalse(hypoxic.vitalsSuggestEscalation() == false, "vitals flag must reflect the reading");
    }

    @Test
    @DisplayName("Registration validation rejects bad data with a specific exception")
    void registrationValidation() {
        system = seeded();
        assertThrows(InvalidPatientDataException.class,
                () -> system.registerPatient("   ", 30, "x", Priority.LOW));
        assertThrows(InvalidPatientDataException.class,
                () -> system.registerPatient("Age Check", 0, "x", Priority.LOW));
        assertThrows(InvalidPatientDataException.class,
                () -> system.registerPatient("Age Check", 130, "x", Priority.LOW));
        assertThrows(InvalidPatientDataException.class,
                () -> system.registerPatient("Vitals Check", 30, "x", Priority.LOW, 400, 98, "120/80"));
        assertThrows(InvalidPatientDataException.class,
                () -> system.registerPatient("Vitals Check", 30, "x", Priority.LOW, 80, 140, "120/80"));
        assertThrows(InvalidPatientDataException.class,
                () -> system.registerPatient("BP Check", 30, "x", Priority.LOW, 80, 98, "high"));
        assertDoesNotThrow(() -> system.registerPatient("Deliberately long, comma name", 30, "x", Priority.LOW));
    }
}
