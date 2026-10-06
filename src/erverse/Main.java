package erverse;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Scanner;

/**
 * ERverse console entry point.
 *
 * <pre>
 * javac -d bin src/erverse/*.java
 * java -cp "bin:lib/sqlite-jdbc-3.46.1.3.jar" erverse.Main
 * </pre>
 *
 * Every state-changing action goes through {@link TriageSystem}, whose methods
 * are synchronized and which raises the checked {@link TriageException}
 * family - this class is therefore obliged to report failures to staff instead
 * of letting them pass silently.
 */
public class Main {

    private static final Path DB_PATH = Paths.get("data", "erverse.db");
    private static final Path CSV_PATH = Paths.get("data", "ERverse_Daily_Report.csv");

    private static TriageSystem system;
    private static Scanner sc = new Scanner(System.in);

    /** Set when the input stream reaches EOF (Ctrl-D or a finished piped script). */
    private static boolean inputExhausted = false;

    public static void main(String[] args) {
        DatabaseManager db = new DatabaseManager(DB_PATH.toString());
        boolean persisted = db.open();
        system = new TriageSystem(db);

        try {
            int restored = db.loadInto(system);
            if (!system.hasResources()) {
                seedResources();
            } else {
                System.out.println("[ERverse] Restored session: " + restored + " patient record(s), "
                        + system.totalDoctors() + " doctor(s), " + system.totalBeds() + " bed(s), "
                        + system.totalAmbulances() + " ambulance(s) from " + DB_PATH);
            }
            System.out.println("[ERverse] Persistence: " + (persisted
                    ? "SQLite enabled -> " + db.getDatabaseFile()
                    : "in-memory only (driver missing) - queue will not survive a restart"));

            printBanner();
            boolean running = true;
            while (running) {
                printMenu();
                int choice = readInt("Enter choice: ");
                running = handle(choice);
            }
            System.out.println("Shutting down ERverse. Stay safe.");
        } finally {
            // Always release the JDBC connection, even if an unexpected failure escapes.
            db.close();
            sc.close();
        }
    }

    private static boolean handle(int choice) {
        switch (choice) {
            case 1:  registerPatientFlow(); return true;
            case 2:  treatNextPatientFlow(); return true;
            case 3:  viewWaitingQueue(); return true;
            case 4:  viewDoctors(); return true;
            case 5:  viewBeds(); return true;
            case 6:  ambulanceMenu(); return true;
            case 7:  reassessFlow(); return true;
            case 8:  dischargeFlow(); return true;
            case 9:  vitalsEscalationFlow(); return true;
            case 10: System.out.println("\n" + system.dailyReport()); return true;
            case 11: exportCsvFlow(); return true;
            case 12: viewAuditEvents(); return true;
            case 13: loadDemoScenario(); return true;
            case 0:  return false;
            default: System.out.println("Invalid choice, try again."); return true;
        }
    }

    // ------------------------------------------------------------ seeding

    private static void seedResources() {
        system.addDoctor(new Doctor("D01", "Anitha Raj", "Trauma"));
        system.addDoctor(new Doctor("D02", "Vikram Shah", "Cardiology"));
        system.addDoctor(new Doctor("D03", "Priya Menon", "General"));

        system.addBed(new Bed("BED-101", Bed.BedType.ICU));
        system.addBed(new Bed("BED-102", Bed.BedType.TRAUMA));
        system.addBed(new Bed("BED-103", Bed.BedType.GENERAL));
        system.addBed(new Bed("BED-104", Bed.BedType.GENERAL));

        system.addAmbulance(new Ambulance("AMB-101", 8));
        system.addAmbulance(new Ambulance("AMB-102", 15));
        System.out.println("[ERverse] Seeded a fresh ED roster: 3 doctors, 4 beds (1 ICU / 1 trauma / 2 general),"
                + " 2 ambulances.");
    }

    /** Menu 13: a ready-made surge for the viva demonstration. */
    private static void loadDemoScenario() {
        try {
            system.registerPatient("J. Kumar", 64, "Chest pain, radiating to left arm",
                    Priority.CRITICAL, 128, 89, "90/60");
            system.registerPatient("A. Meera", 31, "High fever, dehydration", Priority.HIGH, 112, 96, "110/70");
            system.registerPatient("R. Vasan", 45, "Forearm laceration, bleeding controlled", Priority.MEDIUM);
            system.registerPatient("S. Iyer", 22, "Ankle sprain", Priority.LOW);
            system.registerPatient("K. Naveen", 58, "Sudden breathlessness, SpO2 falling",
                    Priority.MEDIUM, 118, 91, "140/85");
            System.out.println("\nDemo surge loaded: 5 patients registered (1 CRITICAL).");
            noticeVitalsCandidates();
            viewWaitingQueue();
        } catch (InvalidPatientDataException e) {
            System.out.println("Demo load failed: " + e.getMessage());
        }
    }

    private static void noticeVitalsCandidates() {
        List<String> promoted = system.applyVitalsEscalation();
        if (!promoted.isEmpty()) {
            System.out.println("Vitals escalation applied automatically:");
            promoted.forEach(m -> System.out.println("  " + m));
        }
    }

    // ------------------------------------------------------------ flows

    private static void registerPatientFlow() {
        try {
            System.out.print("Patient name: ");
            String name = nextLine().trim();
            int age = readInt("Age: ");
            System.out.print("Symptoms: ");
            String symptoms = nextLine().trim();
            Priority priority = choosePriority();
            if (priority == null) {
                return;                        // input stream closed mid-registration
            }

            System.out.print("Vitals - heart rate bpm (blank if not recorded): ");
            int hr = readOptionalInt();
            System.out.print("Vitals - SpO2 % (blank if not recorded): ");
            int spo2 = readOptionalInt();
            System.out.print("Vitals - blood pressure e.g. 120/80 (blank if not recorded): ");
            String bp = nextLine().trim();
            if (bp.isEmpty()) {
                bp = "-";
            }

            Patient p = system.registerPatient(name, age, symptoms, priority, hr, spo2, bp);
            System.out.println("Registered -> " + p);
            System.out.println("  Vitals: " + p.vitalsSummary()
                    + " | queue position: " + system.queuePosition(p.getPatientId()));
            if (p.vitalsSuggestEscalation() && p.getPriority() != Priority.CRITICAL) {
                System.out.println("  WARNING: recorded vitals suggest a higher band than "
                        + p.getPriority() + " - consider reassessment (menu 7) or run menu 9.");
            }
        } catch (TriageException e) {
            System.out.println("Registration rejected: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Error while registering patient: " + e.getMessage());
        }
    }

    private static void treatNextPatientFlow() {
        try {
            System.out.println("\n" + system.treatNextPatient());
        } catch (NoDoctorsAvailableException e) {
            System.out.println("Cannot treat: " + e.getMessage());
            System.out.println("  -> Discharge a treated patient (menu 8) to free a doctor.");
        } catch (NoBedsAvailableException e) {
            System.out.println("Cannot treat: " + e.getMessage());
            System.out.println("  -> Discharge a treated patient (menu 8) to free a bed.");
        }
    }

    private static void reassessFlow() {
        int id = readInt("Patient id to reassess: ");
        Priority p = choosePriority();
        if (p == null) {
            return;
        }
        try {
            System.out.println(system.reassessPatient(id, p));
        } catch (TriageException e) {
            System.out.println("Reassessment failed: " + e.getMessage());
        }
    }

    private static void dischargeFlow() {
        int id = readInt("Patient id to discharge: ");
        try {
            System.out.println(system.dischargePatient(id));
        } catch (TriageException e) {
            System.out.println("Discharge failed: " + e.getMessage());
        }
    }

    private static void vitalsEscalationFlow() {
        List<String> promoted = system.applyVitalsEscalation();
        if (promoted.isEmpty()) {
            System.out.println("No waiting patient's vitals require escalation.");
        } else {
            System.out.println("Escalated " + promoted.size() + " patient(s):");
            promoted.forEach(m -> System.out.println("  " + m));
        }
    }

    private static void exportCsvFlow() {
        try {
            int rows = system.exportDailyReportCsv(CSV_PATH);
            System.out.println("CSV written: " + CSV_PATH.toAbsolutePath() + " (" + rows + " patient rows)");
        } catch (IOException e) {
            System.out.println("CSV export failed: " + e.getMessage());
        }
    }

    private static void viewAuditEvents() {
        System.out.println("\n--- Recent audit events ---");
        List<String> events = system.getEventLog();
        if (events.isEmpty()) {
            System.out.println("No events recorded in this session.");
            return;
        }
        int from = Math.max(0, events.size() - 15);
        for (int i = from; i < events.size(); i++) {
            System.out.println("  " + events.get(i));
        }
    }

    private static void ambulanceMenu() {
        boolean back = false;
        while (!back) {
            System.out.println("\n--- Ambulance control ---");
            System.out.println(" 1. View ambulance status");
            System.out.println(" 2. Advance one minute (tick ETAs)");
            System.out.println(" 3. Dispatch an available unit");
            System.out.println(" 4. Log an incoming patient (pre-arrival planning)");
            System.out.println(" 0. Back to main menu");
            int c = readInt("Choice: ");
            try {
                switch (c) {
                    case 1: viewAmbulances(); break;
                    case 2: {
                        List<String> arrived = system.tickAmbulances();
                        arrived.forEach(System.out::println);
                        if (arrived.isEmpty()) {
                            System.out.println("ETAs updated.");
                        }
                        viewAmbulances();
                        break;
                    }
                    case 3: {
                        System.out.print("Ambulance id (e.g. AMB-101): ");
                        String id = nextLine().trim();
                        int eta = readInt("ETA in minutes: ");
                        System.out.println(system.dispatchAmbulance(id, eta));
                        break;
                    }
                    case 4: {
                        System.out.print("Ambulance id: ");
                        String id = nextLine().trim();
                        System.out.print("Patient name on board: ");
                        String pname = nextLine().trim();
                        Priority pr = choosePriority();
                        if (pr == null || pname.isEmpty()) {
                            System.out.println("Pre-arrival log cancelled.");
                            break;
                        }
                        System.out.println(system.notifyIncomingPatient(id, pname, pr));
                        break;
                    }
                    case 0: back = true; break;
                    default: System.out.println("Invalid choice.");
                }
            } catch (TriageException e) {
                System.out.println("Ambulance operation failed: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ views

    private static void viewWaitingQueue() {
        System.out.println("\n--- Current Waiting Queue (highest priority first) ---");
        List<Patient> ordered = system.getWaitingQueueSorted();
        if (ordered.isEmpty()) {
            System.out.println("Queue is empty.");
            return;
        }
        System.out.println("Next to be treated: " + system.peekNextPatient().map(Patient::getName).orElse("-"));
        int i = 1;
        for (Patient p : ordered) {
            System.out.printf("  %2d. %s%n", i++, p);
        }
    }

    private static void viewDoctors() {
        System.out.println("\n--- Doctor Availability ---");
        for (Doctor d : system.getDoctors()) {
            System.out.println(d);
        }
    }

    private static void viewBeds() {
        System.out.println("\n--- Bed Occupancy ---");
        for (Bed b : system.getBeds()) {
            System.out.println(b);
        }
    }

    private static void viewAmbulances() {
        System.out.println("\n--- Ambulance Status ---");
        for (Ambulance a : system.getAmbulances()) {
            System.out.println(a);
        }
    }

    // ------------------------------------------------------------ input

    private static Priority choosePriority() {
        System.out.println("Select severity: " + Priority.menuLine());
        while (true) {
            System.out.print("Choice: ");
            String raw = nextLine().trim();
            if (raw.isEmpty()) {
                return null;               // EOF - caller unwinds and the app shuts down cleanly
            }
            try {
                return Priority.fromString(raw);
            } catch (InvalidPatientDataException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    private static int readInt(String prompt) {
        while (true) {
            try {
                System.out.print(prompt);
                String line = nextLine().trim();
                if (line.isEmpty()) {
                    // Empty line: either EOF (shut down) or a stray newline from piped input.
                    if (inputExhausted) {
                        return 0;          // 0 is the Exit command in every menu
                    }
                    continue;
                }
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid number.");
            }
        }
    }

    /** Reads an int but accepts an empty line as "not recorded" (0). */
    private static int readOptionalInt() {
        while (true) {
            String line = nextLine().trim();
            if (line.isEmpty()) {
                return Patient.VITAL_NOT_RECORDED;
            }
            try {
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.print("Please enter a valid number or leave blank: ");
            }
        }
    }

    /**
     * Reads one line, converting the end of the input stream (Ctrl-D, or a
     * piped script that has run out of commands) into a clean shutdown instead
     * of an uncaught {@link java.util.NoSuchElementException} stack trace.
     */
    private static String nextLine() {
        try {
            return sc.nextLine();
        } catch (NoSuchElementException e) {
            if (!inputExhausted) {
                System.out.println("\n[ERverse] Input stream closed - shutting down.");
                inputExhausted = true;
            }
            return "";
        }
    }

    // ------------------------------------------------------------ chrome

    private static void printBanner() {
        System.out.println("=================================================================");
        System.out.println("   ERverse : Intelligent Emergency Room Triage & Resource System ");
        System.out.println("   CS5304 Java Programming - PBL   |   console client             ");
        System.out.println("=================================================================");
    }

    private static void printMenu() {
        Optional<Patient> next = system.peekNextPatient();
        System.out.println("\n----------------------- MAIN MENU ------------------------------");
        System.out.println(" 1. Register New Patient");
        System.out.println(" 2. Treat Next Patient (Priority Queue)");
        System.out.println(" 3. View Waiting Queue");
        System.out.println(" 4. View Doctor Availability");
        System.out.println(" 5. View Bed Occupancy");
        System.out.println(" 6. Ambulance Control (ETA countdown / dispatch / pre-arrival)");
        System.out.println(" 7. Reassess a Patient's Priority");
        System.out.println(" 8. Discharge a Patient (release doctor + bed)");
        System.out.println(" 9. Run Vitals Escalation Sweep");
        System.out.println("10. View Daily Emergency Report");
        System.out.println("11. Export Daily Report to CSV");
        System.out.println("12. Show Recent Audit Events");
        System.out.println("13. Load Demo Surge (for demonstration)");
        System.out.println(" 0. Exit");
        System.out.printf("--- waiting:%d  next:%s  beds free:%d  doctors free:%d  en route:%d%n",
                system.waitingCount(),
                next.map(Patient::getName).orElse("-"),
                system.countFreeBeds(), system.countAvailableDoctors(), system.countAmbulancesEnRoute());
        System.out.println("------------------------------------------------------------------");
    }
}
