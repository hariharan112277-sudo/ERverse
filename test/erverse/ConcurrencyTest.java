package erverse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency testing (Section 6.5.3). This is the test that exposed the
 * Week 4 duplicate-patientId race condition; it is kept in the suite as a
 * regression guard for the synchronized boundaries in {@link TriageSystem}.
 */
class ConcurrencyTest {

    @Test
    @DisplayName("2000 registrations from 8 simultaneous 'nurses' produce 2000 unique ids")
    void concurrentRegistrationDoesNotDuplicateIds() throws Exception {
        final int threads = 8;
        final int perThread = 250;
        TriageSystem system = new TriageSystem();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        Set<Integer> ids = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger duplicates = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                final int nurse = t;
                pool.submit(() -> {
                    try {
                        startLine.await();
                        for (int i = 0; i < perThread; i++) {
                            Priority priority = Priority.values()[(nurse + i) % Priority.values().length];
                            Patient p = system.registerPatient("Nurse" + nurse + "-P" + i,
                                    20 + (i % 60), "simulated", priority);
                            if (!ids.add(p.getPatientId())) {
                                duplicates.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startLine.countDown();                    // release all nurses at once
            assertTrue(done.await(60, TimeUnit.SECONDS), "registration threads did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failures.get(), "no registration may fail");
        assertEquals(0, duplicates.get(), "duplicate patient identifiers detected");
        assertEquals(threads * perThread, ids.size());
        assertEquals(threads * perThread, system.getAllPatients().size());
        assertEquals(threads * perThread, system.waitingCount());

        List<Patient> ordered = system.getWaitingQueueSorted();
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ordered.get(i - 1).compareTo(ordered.get(i)) <= 0,
                    "the heap must remain correctly ordered under concurrent insertion");
        }
    }

    @Test
    @DisplayName("Concurrent treat/discharge keeps bed, doctor and patient state consistent")
    void concurrentTreatmentKeepsResourcesConsistent() throws Exception {
        final int patientCount = 60;
        TriageSystem system = new TriageSystem();
        for (int i = 1; i <= 4; i++) {
            system.addDoctor(new Doctor("D0" + i, "Doctor " + i, i <= 2 ? "Trauma" : "General"));
        }
        for (int i = 0; i < 6; i++) {
            system.addBed(new Bed("BED-" + (101 + i), i < 2 ? Bed.BedType.ICU : Bed.BedType.GENERAL));
        }
        for (int i = 0; i < patientCount; i++) {
            system.registerPatient("Patient " + i, 30, "load", Priority.values()[i % 4]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(4);
        try {
            for (int t = 0; t < 4; t++) {
                pool.submit(() -> {
                    try {
                        while (true) {
                            String outcome;
                            try {
                                outcome = system.treatNextPatient();
                            } catch (TriageException busy) {
                                return;                       // ED is saturated - stop this worker
                            }
                            if (outcome.startsWith("No patients")) {
                                return;
                            }
                            // Simulate a short treatment, then discharge the patient we just moved.
                            List<Patient> inTreatment = system.getInTreatment();
                            if (!inTreatment.isEmpty()) {
                                try {
                                    system.dischargePatient(inTreatment.get(0).getPatientId());
                                } catch (TriageException ignored) {
                                    // another worker discharged the same patient first - acceptable
                                }
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS), "treatment workers did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        // Invariant 1: every occupied bed refers to a patient who is still in treatment.
        for (Bed b : system.getBeds()) {
            if (b.isOccupied()) {
                Patient p = system.findPatient(b.getOccupiedByPatientId()).orElseThrow(
                        () -> new AssertionError("bed " + b.getBedId() + " holds unknown patient"));
                assertEquals(Patient.STATUS_IN_TREATMENT, p.getStatus());
                assertEquals(b.getBedId(), p.getAssignedBed());
            }
        }
        // Invariant 2: every busy doctor refers to the patient they are recorded against.
        for (Doctor d : system.getDoctors()) {
            if (!d.isAvailable()) {
                Patient p = system.findPatient(d.getCurrentPatientId()).orElseThrow(
                        () -> new AssertionError("doctor " + d.getDoctorId() + " busy for unknown patient"));
                assertEquals(Patient.STATUS_IN_TREATMENT, p.getStatus());
                assertEquals(d.getDoctorId(), p.getAssignedDoctor());
            }
        }
        // Invariant 3: occupied beds == patients in treatment == busy doctors.
        long occupied = system.getBeds().stream().filter(Bed::isOccupied).count();
        assertEquals(system.getInTreatment().size(), occupied);
        assertEquals(system.totalDoctors() - system.countAvailableDoctors(), system.getInTreatment().size());
        // Invariant 4: nobody was lost - waiting + in treatment + discharged == everything registered.
        long accounted = system.countByStatus(Patient.STATUS_WAITING)
                + system.countByStatus(Patient.STATUS_IN_TREATMENT)
                + system.countByStatus(Patient.STATUS_DISCHARGED);
        assertEquals(patientCount, accounted);
    }
}
