package erverse;

import java.util.List;
import java.util.Scanner;

/**
 * ERverse : Intelligent Emergency Room Triage and Resource Management System
 * Console-based entry point. Demonstrates OOP design, PriorityQueue based
 * triage, and simple exception handling around user input.
 */
public class Main {

    private static final TriageSystem system = new TriageSystem();
    private static final Scanner sc = new Scanner(System.in);

    public static void main(String[] args) {
        seedResources();
        boolean running = true;
        printBanner();
        while (running) {
            printMenu();
            int choice = readInt("Enter choice: ");
            switch (choice) {
                case 1 -> registerPatientFlow();
                case 2 -> { System.out.println("\n" + system.treatNextPatient()); }
                case 3 -> viewWaitingQueue();
                case 4 -> viewDoctors();
                case 5 -> viewBeds();
                case 6 -> viewAmbulances();
                case 7 -> viewDailyReport();
                case 0 -> { running = false; System.out.println("Shutting down ERverse. Stay safe."); }
                default -> System.out.println("Invalid choice, try again.");
            }
        }
        sc.close();
    }

    private static void seedResources() {
        system.addDoctor(new Doctor("D01", "Anitha Raj", "Trauma"));
        system.addDoctor(new Doctor("D02", "Vikram Shah", "Cardiology"));
        system.addDoctor(new Doctor("D03", "Priya Menon", "General"));

        system.addBed(new Bed("B01"));
        system.addBed(new Bed("B02"));
        system.addBed(new Bed("B03"));
        system.addBed(new Bed("B04"));

        system.addAmbulance(new Ambulance("AMB-101", 8));
        system.addAmbulance(new Ambulance("AMB-102", 15));
    }

    private static void printBanner() {
        System.out.println("=================================================================");
        System.out.println("   ERverse : Intelligent Emergency Room Triage & Resource System ");
        System.out.println("=================================================================");
    }

    private static void printMenu() {
        System.out.println("\n----------------------- MAIN MENU ------------------------------");
        System.out.println(" 1. Register New Patient");
        System.out.println(" 2. Treat Next Patient (Priority Queue)");
        System.out.println(" 3. View Waiting Queue");
        System.out.println(" 4. View Doctor Availability");
        System.out.println(" 5. View Bed Occupancy");
        System.out.println(" 6. View Ambulance Status");
        System.out.println(" 7. View Daily Emergency Report");
        System.out.println(" 0. Exit");
        System.out.println("------------------------------------------------------------------");
    }

    private static void registerPatientFlow() {
        try {
            System.out.print("Patient name: ");
            String name = sc.nextLine().trim();
            int age = readInt("Age: ");
            System.out.print("Symptoms: ");
            String symptoms = sc.nextLine().trim();
            Priority priority = choosePriority();
            Patient p = system.registerPatient(name, age, symptoms, priority);
            System.out.println("Registered -> " + p);
        } catch (Exception e) {
            System.out.println("Error while registering patient: " + e.getMessage());
        }
    }

    private static Priority choosePriority() {
        System.out.println("Select severity: 1-Critical 2-High 3-Medium 4-Low");
        int c = readInt("Choice: ");
        return switch (c) {
            case 1 -> Priority.CRITICAL;
            case 2 -> Priority.HIGH;
            case 3 -> Priority.MEDIUM;
            default -> Priority.LOW;
        };
    }

    private static void viewWaitingQueue() {
        System.out.println("\n--- Current Waiting Queue (highest priority first) ---");
        if (system.getWaitingQueue().isEmpty()) {
            System.out.println("Queue is empty.");
            return;
        }
        system.getWaitingQueue().stream()
                .sorted()
                .forEach(System.out::println);
    }

    private static void viewDoctors() {
        System.out.println("\n--- Doctor Availability ---");
        for (Doctor d : system.getDoctors()) System.out.println(d);
    }

    private static void viewBeds() {
        System.out.println("\n--- Bed Occupancy ---");
        for (Bed b : system.getBeds()) System.out.println(b);
    }

    private static void viewAmbulances() {
        System.out.println("\n--- Ambulance Status ---");
        for (Ambulance a : system.getAmbulances()) System.out.println(a);
    }

    private static void viewDailyReport() {
        List<Patient> all = system.getAllPatients();
        System.out.println("\n=================== DAILY EMERGENCY REPORT ===================");
        System.out.println("Total patients registered : " + all.size());
        System.out.println("Waiting                   : " + system.countByStatus("WAITING"));
        System.out.println("In treatment               : " + system.countByStatus("IN_TREATMENT"));
        System.out.println("Discharged                 : " + system.countByStatus("DISCHARGED"));
        System.out.println("----------------------------------------------------------------");
        for (Patient p : all) System.out.println(p);
        System.out.println("================================================================");
    }

    private static int readInt(String prompt) {
        while (true) {
            try {
                System.out.print(prompt);
                String line = sc.nextLine().trim();
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid number.");
            }
        }
    }
}
