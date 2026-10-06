package erverse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC helper for ERverse (Chapter 2.11 and Table 5.1).
 *
 * <p>Stores the entire emergency-department state in a single SQLite file and
 * reloads it at start-up, so a queue survives the application being closed.
 * Every statement in this class is a {@link PreparedStatement}: the Week 7
 * review rejected an earlier draft that concatenated user text (patient name,
 * symptoms) straight into SQL.</p>
 *
 * <p>If the SQLite driver is not on the classpath the manager degrades to a
 * no-op instead of crashing the emergency workflow: the console keeps running
 * in memory and a single warning is printed. {@link #isEnabled()} reports which
 * mode is active and the JUnit suite asserts the persisted path when the driver
 * is present.</p>
 */
public class DatabaseManager implements AutoCloseable {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String jdbcUrl;
    private final Path databaseFile;
    private Connection connection;
    private boolean enabled;
    private boolean driverWarningPrinted;

    // ---------------------------------------------------------------- setup

    /** Opens (or creates) the SQLite database file at the given path. */
    public DatabaseManager(String databasePath) {
        Path path = Paths.get(databasePath);
        this.databaseFile = path.toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + this.databaseFile;
    }

    /** Private form used by {@link #inMemory()} / {@link #disabled()}. */
    private DatabaseManager(String jdbcUrl, Path databaseFile) {
        this.jdbcUrl = jdbcUrl;
        this.databaseFile = databaseFile;
    }

    /** Transient database used by the test suite - no file is created. */
    public static DatabaseManager inMemory() {
        return new DatabaseManager("jdbc:sqlite::memory:", null);
    }

    /** No-op persistence used by unit tests that do not care about storage. */
    public static DatabaseManager disabled() {
        DatabaseManager m = new DatabaseManager("jdbc:sqlite::memory:", null);
        m.enabled = false;
        return m;
    }

    /**
     * Connects and creates the schema.
     *
     * @return true when persistence is active, false when running in memory only
     */
    public boolean open() {
        if (jdbcUrl.endsWith(":memory:") && connection != null) {
            return enabled;
        }
        try {
            Class.forName("org.sqlite.JDBC");
            if (databaseFile != null && databaseFile.getParent() != null) {
                Files.createDirectories(databaseFile.getParent());
            }
            connection = DriverManager.getConnection(jdbcUrl);
            connection.setAutoCommit(true);
            createSchema();
            enabled = true;
        } catch (ClassNotFoundException e) {
            warnOnce("SQLite JDBC driver not found - running in memory only "
                    + "(add lib/sqlite-jdbc-*.jar to the classpath to enable persistence).");
            enabled = false;
        } catch (Exception e) {
            warnOnce("Could not open " + jdbcUrl + " (" + e.getMessage() + ") - running in memory only.");
            enabled = false;
        }
        return enabled;
    }

    public boolean isEnabled() { return enabled; }

    public Path getDatabaseFile() { return databaseFile; }

    private void warnOnce(String message) {
        if (!driverWarningPrinted) {
            System.out.println("[ERverse][DB] WARNING: " + message);
            driverWarningPrinted = true;
        }
    }

    private void createSchema() throws SQLException {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS patients (" +
                "patient_id INTEGER PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "age INTEGER NOT NULL," +
                "symptoms TEXT," +
                "priority TEXT NOT NULL," +
                "arrival_time TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "heart_rate INTEGER DEFAULT 0," +
                "spo2 INTEGER DEFAULT 0," +
                "blood_pressure TEXT DEFAULT '-'," +
                "assigned_doctor TEXT," +
                "assigned_doctor_name TEXT," +
                "assigned_bed TEXT)",

            "CREATE TABLE IF NOT EXISTS doctors (" +
                "doctor_id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "specialization TEXT," +
                "available INTEGER NOT NULL DEFAULT 1)",

            "CREATE TABLE IF NOT EXISTS beds (" +
                "bed_id TEXT PRIMARY KEY," +
                "bed_type TEXT NOT NULL DEFAULT 'GENERAL'," +
                "occupied INTEGER NOT NULL DEFAULT 0," +
                "patient_id INTEGER," +
                "patient_name TEXT)",

            "CREATE TABLE IF NOT EXISTS ambulances (" +
                "ambulance_id TEXT PRIMARY KEY," +
                "status TEXT NOT NULL," +
                "eta_minutes INTEGER NOT NULL DEFAULT 0," +
                "patient_on_board TEXT," +
                "patient_priority TEXT)",

            "CREATE TABLE IF NOT EXISTS triage_events (" +
                "event_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "logged_at TEXT NOT NULL," +
                "event TEXT NOT NULL," +
                "patient_id INTEGER," +
                "detail TEXT)",

            "CREATE INDEX IF NOT EXISTS idx_patients_status ON patients(status)",
            "CREATE INDEX IF NOT EXISTS idx_events_patient ON triage_events(patient_id)"
        };
        try (Statement st = connection.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
    }

    // ------------------------------------------------------------ persistence

    private static final String UPSERT_PATIENT =
            "INSERT INTO patients (patient_id, name, age, symptoms, priority, arrival_time, status," +
            " heart_rate, spo2, blood_pressure, assigned_doctor, assigned_doctor_name, assigned_bed)" +
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)" +
            " ON CONFLICT(patient_id) DO UPDATE SET name=excluded.name, age=excluded.age," +
            " symptoms=excluded.symptoms, priority=excluded.priority, arrival_time=excluded.arrival_time," +
            " status=excluded.status, heart_rate=excluded.heart_rate, spo2=excluded.spo2," +
            " blood_pressure=excluded.blood_pressure, assigned_doctor=excluded.assigned_doctor," +
            " assigned_doctor_name=excluded.assigned_doctor_name, assigned_bed=excluded.assigned_bed";

    /** INSERT ... ON CONFLICT: the same call covers a first save and every later update. */
    public void savePatient(Patient p) {
        if (!enabled) return;
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_PATIENT)) {
            ps.setInt(1, p.getPatientId());
            ps.setString(2, p.getName());
            ps.setInt(3, p.getAge());
            ps.setString(4, p.getSymptoms());
            ps.setString(5, p.getPriority().name());
            ps.setString(6, p.arrivalStamp());
            ps.setString(7, p.getStatus());
            ps.setInt(8, p.getHeartRate());
            ps.setInt(9, p.getSpo2());
            ps.setString(10, p.getBloodPressure());
            ps.setString(11, p.getAssignedDoctor());
            ps.setString(12, p.getAssignedDoctorName());
            ps.setString(13, p.getAssignedBed());
            ps.executeUpdate();
        } catch (SQLException e) {
            warnOnce("savePatient failed: " + e.getMessage());
        }
    }

    /** Alias kept for readability at call sites that update rather than insert. */
    public void updatePatient(Patient p) { savePatient(p); }

    public void saveDoctor(Doctor d) {
        if (!enabled) return;
        String sql = "INSERT INTO doctors (doctor_id, name, specialization, available) VALUES (?,?,?,?)" +
                " ON CONFLICT(doctor_id) DO UPDATE SET name=excluded.name," +
                " specialization=excluded.specialization, available=excluded.available";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, d.getDoctorId());
            ps.setString(2, d.getName());
            ps.setString(3, d.getSpecialization());
            ps.setInt(4, d.isAvailable() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            warnOnce("saveDoctor failed: " + e.getMessage());
        }
    }

    public void updateDoctor(Doctor d) { saveDoctor(d); }

    public void saveBed(Bed b) {
        if (!enabled) return;
        String sql = "INSERT INTO beds (bed_id, bed_type, occupied, patient_id, patient_name) VALUES (?,?,?,?,?)" +
                " ON CONFLICT(bed_id) DO UPDATE SET bed_type=excluded.bed_type," +
                " occupied=excluded.occupied, patient_id=excluded.patient_id," +
                " patient_name=excluded.patient_name";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, b.getBedId());
            ps.setString(2, b.getType().name());
            ps.setInt(3, b.isOccupied() ? 1 : 0);
            if (b.getOccupiedByPatientId() == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, b.getOccupiedByPatientId());
            }
            ps.setString(5, b.getOccupiedByPatientName());
            ps.executeUpdate();
        } catch (SQLException e) {
            warnOnce("saveBed failed: " + e.getMessage());
        }
    }

    public void updateBed(Bed b) { saveBed(b); }

    public void saveAmbulance(Ambulance a) {
        if (!enabled) return;
        String sql = "INSERT INTO ambulances (ambulance_id, status, eta_minutes, patient_on_board, patient_priority)" +
                " VALUES (?,?,?,?,?) ON CONFLICT(ambulance_id) DO UPDATE SET status=excluded.status," +
                " eta_minutes=excluded.eta_minutes, patient_on_board=excluded.patient_on_board," +
                " patient_priority=excluded.patient_priority";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, a.getAmbulanceId());
            ps.setString(2, a.getStatus());
            ps.setInt(3, a.getEtaMinutes());
            ps.setString(4, a.getPatientOnBoard());
            ps.setString(5, a.getPatientPriority() == null ? null : a.getPatientPriority().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            warnOnce("saveAmbulance failed: " + e.getMessage());
        }
    }

    public void updateAmbulance(Ambulance a) { saveAmbulance(a); }

    /** Audit trail entry: every registration, treatment and discharge is recorded. */
    public void logEvent(String event, Integer patientId, String detail) {
        if (!enabled) return;
        String sql = "INSERT INTO triage_events (logged_at, event, patient_id, detail) VALUES (?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().format(STAMP));
            ps.setString(2, event);
            if (patientId == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, patientId);
            }
            ps.setString(4, detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            warnOnce("logEvent failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- restore

    /**
     * Rebuilds the in-memory state from the last session. Waiting and
     * in-treatment patients are re-inserted into the priority queue, so the
     * heap ordering is re-established by {@link Patient#compareTo(Patient)}.
     *
     * @return number of patient rows restored
     */
    public int loadInto(TriageSystem system) {
        if (!enabled) return 0;
        int restored = 0;
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT doctor_id, name, specialization, available FROM doctors ORDER BY doctor_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Doctor d = new Doctor(rs.getString("doctor_id"), rs.getString("name"),
                            rs.getString("specialization"));
                    if (rs.getInt("available") == 0) {
                        d.setAvailable(false);
                    }
                    system.restoreDoctor(d);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT bed_id, bed_type, occupied, patient_id, patient_name FROM beds ORDER BY bed_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Bed b = new Bed(rs.getString("bed_id"), Bed.BedType.fromString(rs.getString("bed_type")));
                    if (rs.getInt("occupied") == 1) {
                        b.occupy(rs.getInt("patient_id"), rs.getString("patient_name"));
                    }
                    system.restoreBed(b);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT ambulance_id, status, eta_minutes, patient_on_board, patient_priority FROM ambulances");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Ambulance a = new Ambulance(rs.getString("ambulance_id"), rs.getInt("eta_minutes"));
                    a.setStatus(rs.getString("status"));
                    String onBoard = rs.getString("patient_on_board");
                    if (onBoard != null) {
                        Priority pr = null;
                        try {
                            pr = Priority.valueOf(rs.getString("patient_priority"));
                        } catch (RuntimeException ignored) {
                            // stored vitals/metadata from an older schema - safe to ignore
                        }
                        a.notifyPatientOnBoard(onBoard, pr);
                    }
                    system.restoreAmbulance(a);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT patient_id, name, age, symptoms, priority, arrival_time, status, heart_rate," +
                    " spo2, blood_pressure, assigned_doctor, assigned_doctor_name, assigned_bed FROM patients" +
                    " ORDER BY arrival_time");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Decision decision = readPatientRow(rs);
                    if (decision == null) {
                        continue;
                    }
                    system.restorePatient(decision.patient, decision.requeue);
                    restored++;
                }
            }
        } catch (SQLException e) {
            warnOnce("loadInto failed: " + e.getMessage());
        }
        return restored;
    }

    private static final class Decision {
        final Patient patient;
        final boolean requeue;
        Decision(Patient patient, boolean requeue) {
            this.patient = patient;
            this.requeue = requeue;
        }
    }

    private Decision readPatientRow(ResultSet rs) throws SQLException {
        Priority priority;
        try {
            priority = Priority.valueOf(rs.getString("priority"));
        } catch (RuntimeException e) {
            return null;   // row written by an older/incompatible version
        }
        String status = rs.getString("status");
        LocalDateTime arrival;
        try {
            arrival = LocalDateTime.parse(rs.getString("arrival_time"), STAMP);
        } catch (RuntimeException e) {
            arrival = LocalDateTime.now();
        }
        Patient p = Patient.restore(
                rs.getInt("patient_id"),
                rs.getString("name"),
                rs.getInt("age"),
                rs.getString("symptoms"),
                priority,
                arrival,
                rs.getInt("heart_rate"),
                rs.getInt("spo2"),
                rs.getString("blood_pressure"),
                status,
                rs.getString("assigned_doctor"),
                rs.getString("assigned_doctor_name"),
                rs.getString("assigned_bed"));
        boolean requeue = Patient.STATUS_WAITING.equals(status);
        return new Decision(p, requeue);
    }

    // ------------------------------------------------------------- helpers

    /** Row counts per table - used by the JUnit persistence test. */
    public int countRows(String table) {
        if (!enabled) return 0;
        String sql = "SELECT COUNT(*) FROM " + table;   // table name is code-controlled, not user input
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Most recent audit rows, newest first - shown by the console report. */
    public List<String> recentEvents(int limit) {
        List<String> out = new ArrayList<>();
        if (!enabled) return out;
        String sql = "SELECT logged_at, event, patient_id, detail FROM triage_events" +
                " ORDER BY event_id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(String.format("%s  %-12s patient=%s  %s",
                            rs.getString("logged_at"), rs.getString("event"),
                            rs.getString("patient_id") == null ? "-" : rs.getString("patient_id"),
                            rs.getString("detail")));
                }
            }
        } catch (SQLException e) {
            warnOnce("recentEvents failed: " + e.getMessage());
        }
        return out;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing useful to do while shutting down
            }
            connection = null;
        }
    }
}
