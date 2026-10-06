package erverse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** TC-04 - the daily report export used by the dashboard's "Export CSV" button. */
class ReportExportTest {

    private TriageSystem seeded() {
        TriageSystem s = new TriageSystem();
        s.addDoctor(new Doctor("D01", "Anitha Raj", "Trauma"));
        s.addBed(new Bed("BED-101", Bed.BedType.ICU));
        s.addBed(new Bed("BED-102", Bed.BedType.TRAUMA));
        return s;
    }

    @Test
    @DisplayName("TC-04: exporting produces ERverse_Daily_Report.csv with one row per patient")
    void csvExportWritesEveryPatient(@TempDir Path tmp) throws Exception {
        TriageSystem system = seeded();
        system.registerPatient("J. Kumar", 64, "Cardiac arrest", Priority.CRITICAL, 128, 89, "90/60");
        system.registerPatient("A. Meera", 31, "Chest pain", Priority.HIGH);
        system.treatNextPatient();

        Path csv = tmp.resolve("ERverse_Daily_Report.csv");
        int rows = system.exportDailyReportCsv(csv);

        assertTrue(Files.exists(csv));
        assertEquals(2, rows);
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals("patient_id,name,age,priority,status,arrival_time,heart_rate,spo2,blood_pressure,"
                + "assigned_doctor,assigned_bed,symptoms", lines.get(0));
        assertTrue(lines.get(1).contains("J. Kumar"));
        assertTrue(lines.get(1).contains("IN_TREATMENT"));
        assertTrue(lines.get(1).contains("128"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("summary,total=2")));
    }

    @Test
    @DisplayName("Free-text fields with commas and quotes cannot break the CSV layout")
    void csvEscapesHostileText(@TempDir Path tmp) throws Exception {
        TriageSystem system = seeded();
        system.registerPatient("Smith, John \"Jack\"", 44, "pain, \"sharp\"", Priority.MEDIUM);

        Path csv = tmp.resolve("hostile.csv");
        system.exportDailyReportCsv(csv);

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "header + 1 patient + summary");
        assertTrue(lines.get(1).contains("\"Smith, John \"\"Jack\"\"\""));
        assertEquals(12, countCsvFields(lines.get(1)), "the row must still have 12 columns");
    }

    @Test
    @DisplayName("Console report summarises queue, staffing and per-band counts")
    void textReportContainsQueueAndMetrics() throws Exception {
        TriageSystem system = seeded();
        system.registerPatient("Critical", 60, "arrest", Priority.CRITICAL);
        system.registerPatient("Low", 20, "sprain", Priority.LOW);

        String report = system.dailyReport();

        assertTrue(report.contains("DAILY EMERGENCY REPORT"));
        assertTrue(report.contains("Total patients registered : 2"));
        assertTrue(report.contains("Waiting                   : 2"));
        assertTrue(report.contains("CRITICAL=1"));
        assertTrue(report.contains("LOW=1"));
        assertTrue(report.contains("Waiting queue (treatment order)"));
        assertTrue(report.indexOf("Critical") < report.indexOf("Low"), "queue printed in triage order");
    }

    /** Counts fields in one CSV row, honouring quoted sections. */
    private static int countCsvFields(String line) {
        int fields = 1;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields++;
            }
        }
        return fields;
    }
}
