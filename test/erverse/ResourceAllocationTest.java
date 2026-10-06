package erverse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** TC-02 - doctor and bed allocation during {@code treatNextPatient()}. */
class ResourceAllocationTest {

    private TriageSystem system;

    private TriageSystem seeded(int doctors, int icuBeds, int traumaBeds, int generalBeds) {
        TriageSystem s = new TriageSystem();
        for (int i = 1; i <= doctors; i++) {
            s.addDoctor(new Doctor("D0" + i, "Doctor " + i, i == 1 ? "Trauma" : "General"));
        }
        int n = 101;
        for (int i = 0; i < icuBeds; i++)     s.addBed(new Bed("BED-" + n++, Bed.BedType.ICU));
        for (int i = 0; i < traumaBeds; i++)  s.addBed(new Bed("BED-" + n++, Bed.BedType.TRAUMA));
        for (int i = 0; i < generalBeds; i++) s.addBed(new Bed("BED-" + n++, Bed.BedType.GENERAL));
        return s;
    }

    @Test
    @DisplayName("TC-02: treat-next assigns doctor + bed and sets IN_TREATMENT")
    void automaticalResourceAllocation() throws Exception {
        system = seeded(3, 1, 1, 2);
        Patient p = system.registerPatient("A. Meera", 31, "Chest pain", Priority.HIGH);

        String message = system.treatNextPatient();

        Patient treated = system.findPatient(p.getPatientId()).orElseThrow();
        assertEquals(Patient.STATUS_IN_TREATMENT, treated.getStatus());
        assertNotNull(treated.getAssignedDoctor());
        assertNotNull(treated.getAssignedBed());
        assertTrue(message.contains("Now treating: A. Meera"), "console summary should name the patient");

        assertEquals(0, system.waitingCount(), "treated patient leaves the waiting queue");
        assertEquals(1, system.getInTreatment().size());
        assertEquals(2, system.countAvailableDoctors(), "exactly one of the three doctors is consumed");
        Doctor used = system.getDoctors().stream()
                .filter(d -> d.getDoctorId().equals(treated.getAssignedDoctor()))
                .findFirst().orElseThrow();
        assertFalse(used.isAvailable());
        assertEquals(p.getPatientId(), used.getCurrentPatientId());

        Bed bed = system.getBeds().stream()
                .filter(b -> b.getBedId().equals(treated.getAssignedBed()))
                .findFirst().orElseThrow();
        assertTrue(bed.isOccupied());
        assertEquals(p.getPatientId(), bed.getOccupiedByPatientId());
    }

    @Test
    @DisplayName("CRITICAL patient is given the ICU bed, HIGH patient the trauma bay")
    void bedTypeMatchesAcuity() throws Exception {
        system = seeded(3, 1, 1, 1);
        Patient critical = system.registerPatient("Critical", 60, "arrest", Priority.CRITICAL);
        Patient high = system.registerPatient("High", 40, "fracture", Priority.HIGH);

        system.treatNextPatient();
        system.treatNextPatient();

        assertEquals("BED-101", system.findPatient(critical.getPatientId()).orElseThrow().getAssignedBed());
        assertEquals("BED-102", system.findPatient(high.getPatientId()).orElseThrow().getAssignedBed());
    }

    @Test
    @DisplayName("Trauma specialist is preferred for a critical patient")
    void specialtyIsPreferred() throws Exception {
        system = seeded(2, 1, 1, 1);
        Patient p = system.registerPatient("Critical", 60, "arrest", Priority.CRITICAL);
        system.treatNextPatient();
        assertEquals("D01", system.findPatient(p.getPatientId()).orElseThrow().getAssignedDoctor());
    }

    @Test
    @DisplayName("Doctor exhaustion raises NoDoctorsAvailableException and keeps the patient queued")
    void doctorExhaustionKeepsPatientQueued() throws Exception {
        system = seeded(1, 1, 1, 1);                    // one doctor only
        Patient first = system.registerPatient("First", 30, "fracture", Priority.HIGH);
        system.treatNextPatient();                      // consumes the only doctor

        Patient second = system.registerPatient("Second", 31, "arrest", Priority.CRITICAL);
        assertThrows(NoDoctorsAvailableException.class, system::treatNextPatient);

        assertEquals(1, system.waitingCount(), "the critical patient must NOT be dropped");
        assertEquals(second.getPatientId(), system.peekNextPatient().orElseThrow().getPatientId(),
                "a failed dispatch must leave the patient at the head of the heap");
        assertEquals(Patient.STATUS_WAITING, system.findPatient(second.getPatientId()).orElseThrow().getStatus());
        // the first patient is unaffected
        assertEquals(Patient.STATUS_IN_TREATMENT, system.findPatient(first.getPatientId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Bed exhaustion raises NoBedsAvailableException and keeps the patient queued")
    void bedExhaustionKeepsPatientQueued() throws Exception {
        system = seeded(3, 1, 0, 0);                    // one ICU bed, three doctors
        system.registerPatient("First", 30, "x", Priority.CRITICAL);
        system.treatNextPatient();

        Patient waiting = system.registerPatient("Second", 31, "x", Priority.CRITICAL);
        NoBedsAvailableException ex =
                assertThrows(NoBedsAvailableException.class, system::treatNextPatient);
        assertTrue(ex.getMessage().contains("Second"));
        assertEquals(1, system.waitingCount());
        assertEquals(waiting.getPatientId(), system.peekNextPatient().orElseThrow().getPatientId());
        assertEquals(2, system.countAvailableDoctors(),
                "a failed dispatch must not silently consume a doctor");
    }

    @Test
    @DisplayName("Lower-acuity patients never occupy the ICU while general beds exist")
    void icuIsReservedForCriticalPatients() throws Exception {
        system = seeded(2, 1, 0, 2);                    // 1 ICU bed, 2 general, no trauma bays
        Patient low = system.registerPatient("Low", 20, "sprain", Priority.LOW);
        Patient critical = system.registerPatient("Critical", 60, "arrest", Priority.CRITICAL);

        system.treatNextPatient();                      // critical first
        system.treatNextPatient();                      // then the low-acuity patient

        assertEquals("BED-101", system.findPatient(critical.getPatientId()).orElseThrow().getAssignedBed(),
                "the ICU bed belongs to the critical patient");
        String lowBed = system.findPatient(low.getPatientId()).orElseThrow().getAssignedBed();
        assertNotEquals("BED-101", lowBed, "LOW band must not take an ICU bed");
        assertEquals(Bed.BedType.GENERAL, system.getBeds().stream()
                .filter(b -> b.getBedId().equals(lowBed)).findFirst().orElseThrow().getType());
    }

    @Test
    @DisplayName("treat-next on an empty queue is a no-op message, not an exception")
    void emptyQueueReturnsMessage() throws Exception {
        system = seeded(1, 1, 0, 0);
        assertEquals("No patients waiting.", system.treatNextPatient());
    }
}
