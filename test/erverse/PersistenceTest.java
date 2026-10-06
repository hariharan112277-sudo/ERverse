package erverse;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JDBC/SQLite persistence (Chapter 2.11, Table 5.1). Verifies that a session
 * can be closed and rebuilt - the queue, the resource state and the audit trail
 * all survive a restart.
 */
class PersistenceTest {

    @Test
    @DisplayName("Registered patients and resources are restored after a restart")
    void stateSurvivesRestart(@TempDir Path tmp) throws Exception {
        Path dbFile = tmp.resolve("erverse.db");

        int criticalId;
        int waitingId;
        try (DatabaseManager db = new DatabaseManager(dbFile.toString())) {
            Assumptions.assumeTrue(db.open(), "SQLite driver not on the classpath - skipping");
            TriageSystem system = new TriageSystem(db);
            system.addDoctor(new Doctor("D01", "Anitha Raj", "Trauma"));
            system.addDoctor(new Doctor("D02", "Vikram Shah", "Cardiology"));
            system.addBed(new Bed("BED-101", Bed.BedType.ICU));
            system.addBed(new Bed("BED-102", Bed.BedType.TRAUMA));
            system.addAmbulance(new Ambulance("AMB-101", 8));

            Patient critical = system.registerPatient("J. Kumar", 64, "Cardiac arrest",
                    Priority.CRITICAL, 128, 89, "90/60");
            Patient waiting = system.registerPatient("S. Iyer", 22, "Ankle sprain", Priority.LOW);
            system.treatNextPatient();                 // critical patient now IN_TREATMENT

            criticalId = critical.getPatientId();
            waitingId = waiting.getPatientId();
            assertTrue(db.countRows("patients") >= 2);
            assertTrue(db.countRows("triage_events") >= 3, "register/register/treat audit rows expected");
        }

        // ---- second session, same file ----
        try (DatabaseManager db = new DatabaseManager(dbFile.toString())) {
            assertTrue(db.open());
            TriageSystem restored = new TriageSystem(db);
            int restoredRows = db.loadInto(restored);

            assertEquals(2, restoredRows);
            assertEquals(1, restored.waitingCount(), "only the waiting patient returns to the queue");
            assertEquals(2, restored.totalDoctors());
            assertEquals(2, restored.totalBeds());
            assertEquals(1, restored.totalAmbulances());

            Patient waiting = restored.findPatient(waitingId).orElseThrow();
            assertEquals(Priority.LOW, waiting.getPriority());
            assertEquals(Patient.STATUS_WAITING, waiting.getStatus());

            Patient critical = restored.findPatient(criticalId).orElseThrow();
            assertEquals(Patient.STATUS_IN_TREATMENT, critical.getStatus());
            assertEquals("D01", critical.getAssignedDoctor());
            assertEquals("BED-101", critical.getAssignedBed());
            assertEquals(128, critical.getHeartRate());
            assertEquals("90/60", critical.getBloodPressure());
            assertFalse(restored.getDoctors().get(0).isAvailable(), "busy doctor stays busy after restart");
            assertTrue(restored.getBeds().get(0).isOccupied(), "occupied bed stays occupied after restart");

            // Ids continue from the restored maximum - no reuse, no duplicates.
            Patient next = restored.registerPatient("New Arrival", 33, "migraine", Priority.MEDIUM);
            assertTrue(next.getPatientId() > criticalId && next.getPatientId() > waitingId);
            assertEquals(2, restored.waitingCount());
        }
    }

    @Test
    @DisplayName("Every state change is written through prepared statements (audit trail present)")
    void auditTrailRecordsWorkflow(@TempDir Path tmp) throws Exception {
        try (DatabaseManager db = new DatabaseManager(tmp.resolve("audit.db").toString())) {
            Assumptions.assumeTrue(db.open());
            TriageSystem system = new TriageSystem(db);
            system.addDoctor(new Doctor("D01", "Anitha Raj", "Trauma"));
            system.addBed(new Bed("BED-101", Bed.BedType.ICU));

            Patient p = system.registerPatient("A. Meera", 31, "Chest pain", Priority.HIGH);
            system.treatNextPatient();
            system.reassessPatient(p.getPatientId(), Priority.CRITICAL);
            system.dischargePatient(p.getPatientId());

            List<String> events = db.recentEvents(20);
            assertTrue(events.stream().anyMatch(e -> e.contains("REGISTER")));
            assertTrue(events.stream().anyMatch(e -> e.contains("TREAT")));
            assertTrue(events.stream().anyMatch(e -> e.contains("REASSESS")));
            assertTrue(events.stream().anyMatch(e -> e.contains("DISCHARGE")));

            assertEquals(1, db.loadInto(new TriageSystem()),
                    "the discharged patient is still persisted as history");
            Patient reloaded = system.findPatient(p.getPatientId()).orElseThrow();
            assertEquals(Patient.STATUS_DISCHARGED, reloaded.getStatus());
            assertEquals(Priority.CRITICAL, reloaded.getPriority(),
                    "the reassessed priority is what gets stored");
        }
    }

    @Test
    @DisplayName("Injection attempt in a free-text field is stored literally, not executed")
    void preparedStatementsNeutraliseInjection(@TempDir Path tmp) throws Exception {
        try (DatabaseManager db = new DatabaseManager(tmp.resolve("inject.db").toString())) {
            Assumptions.assumeTrue(db.open());
            TriageSystem system = new TriageSystem(db);
            String attack = "Robert'); DROP TABLE patients;--";
            system.registerPatient(attack, 44, "test", Priority.LOW);

            assertTrue(db.countRows("patients") == 1, "the table must still exist and hold the row");
            String name = system.getAllPatients().get(0).getName();
            assertEquals(attack, name, "the payload is stored as data, not interpreted");
        }
    }
}
