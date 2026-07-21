# ERverse — Intelligent Emergency Room Triage and Resource Management System

Console-based Java application (CS5304 – Java Programming, PBL Review-II).

## Package layout
```
src/erverse/
  Priority.java      enum: CRITICAL, HIGH, MEDIUM, LOW
  Patient.java        model, implements Comparable<Patient>
  Doctor.java          resource model
  Bed.java              resource model
  Ambulance.java   resource model
  TriageSystem.java  core engine — wraps java.util.PriorityQueue<Patient>
  Main.java            console menu / entry point
```

## Build & Run
Requires JDK 17+.
```
javac -d bin src/erverse/*.java
java -cp bin erverse.Main
```

## Current implementation status (~30%)
- Patient registration, severity selection, PriorityQueue-based triage ordering
- Doctor / bed auto-assignment on "treat next patient"
- Live views: waiting queue, doctors, beds, ambulances
- Daily emergency report

## Planned next
- JDBC + MySQL persistence
- Multithreading for concurrent updates
- Secure staff login, admin dashboard
- JUnit test suite
