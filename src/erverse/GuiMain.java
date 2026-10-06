package erverse;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Clean Java Swing GUI for ERverse Triage and Resource Management.
 * Runs natively in the standard Java JDK without external dependencies.
 */
public class GuiMain extends JFrame {

    private final TriageSystem triageSystem;
    private final DefaultTableModel queueTableModel;
    private final DefaultTableModel doctorsTableModel;
    private final DefaultTableModel bedsTableModel;
    private final JLabel statusLabel;

    public GuiMain() {
        super("ERverse — Emergency Room Triage & Resource Management");

        // Initialize Database and Triage System
        DatabaseManager db = new DatabaseManager("data/erverse.db");
        db.open();
        triageSystem = new TriageSystem(db);
        db.loadInto(triageSystem);

        if (!triageSystem.hasResources()) {
            seedDefaultRoster(triageSystem);
        }

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1024, 680);
        setLocationRelativeTo(null);

        // Styling & Layout
        setLayout(new BorderLayout(10, 10));

        // Header Panel
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(24, 43, 73));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        JLabel titleLabel = new JLabel("ERverse — Emergency Room Dashboard");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        titleLabel.setForeground(Color.WHITE);
        headerPanel.add(titleLabel, BorderLayout.WEST);

        statusLabel = new JLabel("System Ready | Queue: " + triageSystem.waitingCount() + " patient(s)");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        statusLabel.setForeground(new Color(180, 210, 255));
        headerPanel.add(statusLabel, BorderLayout.EAST);

        add(headerPanel, BorderLayout.NORTH);

        // Center Tabbed View
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("Segoe UI", Font.BOLD, 13));

        // Tab 1: Queue Table
        String[] queueCols = {"ID", "Name", "Age", "Priority", "Status", "Symptoms", "Vitals"};
        queueTableModel = new DefaultTableModel(queueCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable queueTable = new JTable(queueTableModel);
        queueTable.setRowHeight(26);
        queueTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        tabbedPane.addTab("Patient Waiting Queue", new JScrollPane(queueTable));

        // Tab 2: Doctors & Beds
        JPanel resourcePanel = new JPanel(new GridLayout(1, 2, 10, 10));

        String[] docCols = {"Doctor ID", "Name", "Specialization", "Available"};
        doctorsTableModel = new DefaultTableModel(docCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable doctorsTable = new JTable(doctorsTableModel);
        doctorsTable.setRowHeight(24);
        resourcePanel.add(new JScrollPane(doctorsTable));

        String[] bedCols = {"Bed ID", "Type", "Occupied By"};
        bedsTableModel = new DefaultTableModel(bedCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable bedsTable = new JTable(bedsTableModel);
        bedsTable.setRowHeight(24);
        resourcePanel.add(new JScrollPane(bedsTable));

        tabbedPane.addTab("Hospital Resources", resourcePanel);

        add(tabbedPane, BorderLayout.CENTER);

        // Action Toolbar / Buttons Panel
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
        actionPanel.setBackground(new Color(240, 243, 248));

        JButton btnRegister = createButton("Register Patient", new Color(37, 99, 235));
        JButton btnTreat = createButton("Treat Next Patient", new Color(16, 185, 129));
        JButton btnDemo = createButton("Load Demo Surge", new Color(217, 119, 6));
        JButton btnExport = createButton("Export CSV Report", new Color(107, 114, 128));

        actionPanel.add(btnRegister);
        actionPanel.add(btnTreat);
        actionPanel.add(btnDemo);
        actionPanel.add(btnExport);

        add(actionPanel, BorderLayout.SOUTH);

        // Listeners
        btnRegister.addActionListener(e -> showRegisterDialog());
        btnTreat.addActionListener(e -> handleTreatNext());
        btnDemo.addActionListener(e -> handleLoadDemo());
        btnExport.addActionListener(e -> handleExportCsv());

        refreshData();
    }

    private JButton createButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        return btn;
    }

    private void showRegisterDialog() {
        JTextField nameField = new JTextField(15);
        JTextField ageField = new JTextField(5);
        JTextField symptomsField = new JTextField(20);
        JComboBox<Priority> priorityBox = new JComboBox<>(Priority.values());

        JPanel panel = new JPanel(new GridLayout(4, 2, 8, 8));
        panel.add(new JLabel("Patient Name:"));
        panel.add(nameField);
        panel.add(new JLabel("Age:"));
        panel.add(ageField);
        panel.add(new JLabel("Symptoms:"));
        panel.add(symptomsField);
        panel.add(new JLabel("Priority Level:"));
        panel.add(priorityBox);

        int result = JOptionPane.showConfirmDialog(this, panel, "Register New Patient",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            try {
                String name = nameField.getText();
                int age = Integer.parseInt(ageField.getText().trim());
                String symptoms = symptomsField.getText();
                Priority p = (Priority) priorityBox.getSelectedItem();

                Patient created = triageSystem.registerPatient(name, age, symptoms, p);
                JOptionPane.showMessageDialog(this, "Registered " + created.getName() + " (#" + created.getPatientId() + ") as " + p);
                refreshData();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Registration Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void handleTreatNext() {
        try {
            String result = triageSystem.treatNextPatient();
            JOptionPane.showMessageDialog(this, result, "Treatment Dispatch", JOptionPane.INFORMATION_MESSAGE);
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Dispatch Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void handleLoadDemo() {
        try {
            triageSystem.registerPatient("J. Kumar", 62, "Severe chest pain, diaphoresis", Priority.CRITICAL, 128, 88, "165/100");
            triageSystem.registerPatient("A. Patel", 29, "Fractured wrist from fall", Priority.MEDIUM, 82, 98, "122/78");
            triageSystem.registerPatient("R. Sharma", 45, "Acute dyspnea, wheezing", Priority.HIGH, 110, 91, "140/90");
            triageSystem.registerPatient("S. Iyer", 19, "Minor superficial laceration", Priority.LOW, 72, 99, "118/75");
            JOptionPane.showMessageDialog(this, "Loaded 4 demo surge patients successfully!");
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
        }
    }

    private void handleExportCsv() {
        try {
            Path path = Paths.get("data", "ERverse_Daily_Report.csv");
            int rows = triageSystem.exportDailyReportCsv(path);
            JOptionPane.showMessageDialog(this, "Exported " + rows + " record(s) to:\n" + path.toAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage());
        }
    }

    private void refreshData() {
        // Queue Table
        queueTableModel.setRowCount(0);
        List<Patient> waiting = triageSystem.getWaitingQueueSorted();
        for (Patient p : waiting) {
            queueTableModel.addRow(new Object[]{
                    p.getPatientId(), p.getName(), p.getAge(), p.getPriority(),
                    p.getStatus(), p.getSymptoms(), p.vitalsSummary()
            });
        }

        // Doctors
        doctorsTableModel.setRowCount(0);
        for (Doctor d : triageSystem.getDoctors()) {
            doctorsTableModel.addRow(new Object[]{
                    d.getDoctorId(), d.getName(), d.getSpecialization(), d.isAvailable() ? "AVAILABLE" : "BUSY"
            });
        }

        // Beds
        bedsTableModel.setRowCount(0);
        for (Bed b : triageSystem.getBeds()) {
            bedsTableModel.addRow(new Object[]{
                    b.getBedId(), b.getType().getLabel(), b.isOccupied() ? (b.getOccupiedByPatientName() != null ? b.getOccupiedByPatientName() : "Patient #" + b.getOccupiedByPatientId()) : "FREE"
            });
        }

        statusLabel.setText("Waiting Queue: " + waiting.size() + " patient(s) | Available Doctors: " +
                triageSystem.countAvailableDoctors() + " | Free Beds: " + triageSystem.countFreeBeds());
    }

    private static void seedDefaultRoster(TriageSystem ts) {
        ts.addDoctor(new Doctor("DOC-101", "Ananya Roy", "Trauma"));
        ts.addDoctor(new Doctor("DOC-102", "Vikram Seth", "Cardiology"));
        ts.addDoctor(new Doctor("DOC-103", "Meera Nair", "General"));
        ts.addBed(new Bed("BED-101", Bed.BedType.ICU));
        ts.addBed(new Bed("BED-102", Bed.BedType.TRAUMA));
        ts.addBed(new Bed("BED-103", Bed.BedType.GENERAL));
        ts.addBed(new Bed("BED-104", Bed.BedType.GENERAL));
        ts.addAmbulance(new Ambulance("AMB-01", 10));
        ts.addAmbulance(new Ambulance("AMB-02", 15));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new GuiMain().setVisible(true);
        });
    }
}
