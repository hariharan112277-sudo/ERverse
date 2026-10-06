package erverse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-01 - the central claim of the project: severity, not arrival order,
 * decides who is treated next (Section 6.5.1).
 */
class TriageOrderingTest {

    private TriageSystem freshSystem() {
        TriageSystem system = new TriageSystem();
        system.addDoctor(new Doctor("D01", "Anitha Raj", "Trauma"));
        system.addBed(new Bed("BED-101", Bed.BedType.ICU));
        return system;
    }

    @Test
    @DisplayName("TC-01: a CRITICAL arrival overtakes an earlier LOW arrival")
    void criticalOvertakesEarlierLow() throws Exception {
        TriageSystem system = freshSystem();
        Patient low = system.registerPatient("R. Vasan", 45, "Ankle sprain", Priority.LOW);
        Patient critical = system.registerPatient("J. Kumar", 64, "Cardiac arrest", Priority.CRITICAL);

        List<Patient> ordered = system.getWaitingQueueSorted();

        assertEquals(2, ordered.size());
        assertEquals(critical.getPatientId(), ordered.get(0).getPatientId(),
                "critical patient must be first in the treatment order");
        assertEquals(low.getPatientId(), ordered.get(1).getPatientId());
        assertEquals(critical.getPatientId(), system.peekNextPatient().orElseThrow().getPatientId());
        assertEquals(1, system.queuePosition(critical.getPatientId()));
    }

    @Test
    @DisplayName("Equal severity falls back to arrival order (FIFO inside a band)")
    void equalSeverityIsFirstComeFirstServed() throws Exception {
        TriageSystem system = freshSystem();
        Patient first  = system.registerPatient("First", 30, "chest pain", Priority.HIGH);
        Thread.sleep(5);                       // make the arrival stamps differ
        Patient second = system.registerPatient("Second", 31, "chest pain", Priority.HIGH);

        List<Patient> ordered = system.getWaitingQueueSorted();
        assertEquals(first.getPatientId(), ordered.get(0).getPatientId());
        assertEquals(second.getPatientId(), ordered.get(1).getPatientId());
    }

    @Test
    @DisplayName("Ordering is deterministic even for identical arrival stamps (id tie-break)")
    void identicalTimestampsStillOrderDeterministically() {
        List<Patient> patients = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            patients.add(new Patient("P" + i, 40, "test", Priority.HIGH));
        }
        PriorityQueue<Patient> heap = new PriorityQueue<>(patients);
        List<Patient> drained = new ArrayList<>();
        while (!heap.isEmpty()) {
            drained.add(heap.poll());
        }
        for (int i = 1; i < drained.size(); i++) {
            assertTrue(drained.get(i - 1).getPatientId() < drained.get(i).getPatientId(),
                    "same severity + same timestamp must be ordered by ascending patient id");
        }
    }

    @Test
    @DisplayName("Heap invariant survives a 500-patient surge: poll never returns a less severe patient")
    void heapOrderingHoldsUnderLoad() throws Exception {
        TriageSystem system = freshSystem();
        Random rnd = new Random(42);
        List<Patient> registered = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Priority p = Priority.values()[rnd.nextInt(Priority.values().length)];
            registered.add(system.registerPatient("Patient " + i, 20 + rnd.nextInt(70), "surge", p));
        }
        assertEquals(500, system.waitingCount());

        List<Patient> ordered = system.getWaitingQueueSorted();
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ordered.get(i - 1).compareTo(ordered.get(i)) <= 0,
                    "heap order violated between positions " + (i - 1) + " and " + i);
        }
        // Repeated drains must reproduce the same sequence.
        PriorityQueue<Patient> heap = new PriorityQueue<>(registered);
        Patient previous = heap.poll();
        while (!heap.isEmpty()) {
            Patient current = heap.poll();
            assertTrue(previous.compareTo(current) <= 0);
            previous = current;
        }
    }

    @Test
    @DisplayName("Queue view is a copy: mutating it cannot corrupt the engine")
    void queueViewIsDefensiveCopy() throws Exception {
        TriageSystem system = freshSystem();
        system.registerPatient("A", 30, "x", Priority.LOW);
        List<Patient> view = system.getWaitingQueueSorted();
        view.clear();
        assertEquals(1, system.waitingCount(), "clearing the returned list must not empty the queue");
    }
}
