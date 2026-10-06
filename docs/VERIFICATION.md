# ERverse — Verification & Evaluation Evidence

This document supplies the evidence for the claims made in **Chapter 6 (Results and Discussion)**
and **Chapter 7 (Course Outcomes)** of the PBL report. Every number below was produced by code in this
repository; the raw output of each run is stored next to it in `docs/evidence/`.

| Evidence file | Produced by |
|---|---|
| `docs/evidence/junit-run.txt` | `./scripts/run-tests.sh` — 27 JUnit 5 tests |
| `docs/evidence/benchmark.txt` | `java -cp bin:testbin erverse.OrderingBenchmark` |
| `docs/evidence/console-session.txt` | scripted console session driving TC-01 → TC-04 |
| `docs/evidence/persistence-restart.txt` | console restarted against the existing SQLite file |
| `docs/evidence/rest-smoke-test.txt` | `node scripts/smoke-test.js` — 31 REST checks |
| `docs/dashboard.png`, `docs/dashboard-full.png` | headless Chromium capture of the live dashboard |

---

## 1. Table 6.1 — Algorithmic time-complexity analysis (measured)

The report's table gives the theoretical bounds. The benchmark harness
(`test/erverse/OrderingBenchmark.java`) confirms them empirically by timing *n* registrations
followed by *n* drains, against the "re-sorted ArrayList" baseline the report mentions. Best of five
runs after two warm-up passes, JDK 21.

| Operation | Implementation | Theoretical | Measured per operation |
|---|---|---|---|
| Register (enqueue) + Treat (dequeue) | `PriorityQueue.add()/poll()` | O(log n) | **167 ns at n=5 → 201 ns at n=500 → 443 ns at n=50 000** |
| Register + drain, baseline | `ArrayList` re-sorted on every insert | O(n log n) per op | 214 ns at n=5 → 1 193 ns at n=500 → **192 742 ns at n=50 000** |
| View highest priority | `PriorityQueue.peek()` | O(1) | direct `array[0]` read |
| Doctor allocation | linear scan | O(d) | d = number of doctors (3 at seed) |
| Bed allocation | linear scan | O(b) | b = number of beds (4 at seed) |

Raw benchmark output (`docs/evidence/benchmark.txt`):

```
queue size | PriorityQueue total | re-sorted ArrayList |     speed-up
-----------|--------------------|--------------------|-------------
         5 |           0.201 ms |           0.185 ms |       0.92x
        50 |           0.570 ms |           0.333 ms |       0.58x
       500 |           1.529 ms |           3.205 ms |       2.10x
      5000 |           8.307 ms |         114.297 ms |      13.76x
     50000 |          39.069 ms |       22523.681 ms |     576.50x
```

**Interpretation (report §6.3, refined).**

* The report's central claim is confirmed: the heap's per-operation cost is **near-flat** from n=5 to
  n=500 (167 ns → 201 ns, a growth of 1.2×) while the re-sorted list grows 5.6× over the same range.
  Treatment order therefore stays predictable during a surge.
* The benchmark also produced a more honest result than the report asserts: **for very small queues
  (n ≤ 50) the re-sorted ArrayList is actually faster in wall-clock time** — 0.185 ms vs 0.201 ms at
  n=5 — because the heap pays array-allocation and comparison overheads that a five-element list does
  not. The heap only becomes the better choice from roughly **n ≈ 500** onwards, and then dominates
  asymptotically (2.1× at 500, 576× at 50 000).
* **Justification for the chosen structure.** A real emergency department is exactly the regime where
  the heap wins: the interesting moment is the *surge*, when tens to hundreds of patients are waiting
  and the naive baseline is already 2–576× slower per operation. It also matters that the
  re-sorted-list cost grows with queue length, so the system degrades *precisely when the ED is
  busiest* — the failure mode the report set out to remove.
* The 50 000-patient run (22.5 s for the baseline versus 39 ms for the heap) is included to make the
  asymptotics visible; it is far beyond the workload the report's scope covers, and no design
  decision depends on it.

---

## 2. Table 6.2 — Functional test results matrix (actual values)

Executed against the final build; raw transcript in `docs/evidence/console-session.txt` and the
automated equivalents in `docs/evidence/junit-run.txt`.

| Test | Scenario | Input | Expected / **actual** outcome | Status |
|---|---|---|---|---|
| **TC-01** | Priority queue reordering | Patient #1001 (LOW) registered first; Patient #1002 (CRITICAL) second | `Next to be treated: Critical Case`; queue prints **#1002 then #1001**; `queue position: 1` for the critical patient | **PASS** |
| **TC-02** | Automatic resource allocation | `treatNextPatient()` | `Assigned Doctor: Dr. Anitha Raj [D01] (Trauma)`; `Assigned Bed: BED-101 (ICU / resuscitation)`; status → `IN_TREATMENT`; bed shows `OCCUPIED by Patient #1002` | **PASS** |
| **TC-03** | Discharge & resource release | `dischargePatient(1002)` | `Doctor D01 -> AVAILABLE`, `Bed BED-101 -> FREE`; bed grid shows all four beds `FREE` | **PASS** |
| **TC-04** | CSV report generation | Export from console (menu 11) / `GET /api/report.csv` | `data/ERverse_Daily_Report.csv` written, 2 patient rows + header + summary line | **PASS** |

`TC-02`'s expected bed is **BED-101 (ICU)** rather than the report table's "BED-102", because a
CRITICAL patient is placed in the ICU bed and BED-101 is the ICU bed in the report's own Figure 5.1 —
see `CHANGELOG.md` §A5.

### Additional regression tests beyond TC-01…TC-04

| Area | Test | Result |
|---|---|---|
| Ordering | 500-patient randomised surge; heap invariant checked at every position | PASS |
| Ordering | total-order determinism for identical arrival stamps (id tie-break) | PASS |
| Ordering | queue accessor returns a copy, so callers cannot corrupt the heap | PASS |
| Allocation | patient stays at the head of the heap when a doctor or bed is missing | PASS |
| Allocation | no doctor is consumed by a dispatch that fails on beds | PASS |
| Allocation | acuity→bed-type mapping (CRITICAL→ICU, HIGH→trauma, ICU never taken by LOW) | PASS |
| Allocation | trauma specialist preferred for a CRITICAL patient | PASS |
| Discharge | double discharge raises a checked exception and changes nothing | PASS |
| Reassessment | promotion and demotion both re-heapify correctly | PASS |
| Vitals | escalation sweep promotes only the at-risk patient | PASS |
| Validation | blank name / age 0 / age 130 / HR 400 / SpO₂ 140 / BP "high" all rejected | PASS |
| CSV | commas and quotes in free text keep the 12-column layout intact | PASS |
| **Concurrency** | 8 threads × 250 registrations: 2000 unique ids, zero duplicates, heap still ordered | PASS |
| **Concurrency** | 4 threads treating/discharging concurrently; 4 cross-entity invariants hold | PASS |
| **Persistence** | SQLite round-trip: queue, busy doctor, occupied bed, vitals and audit rows restored | PASS |
| **Persistence** | `Robert'); DROP TABLE patients;--` stored verbatim, table intact | PASS |

**Totals:** JUnit 27/27 passing (`docs/evidence/junit-run.txt`); REST smoke test 31/31 passing
(`docs/evidence/rest-smoke-test.txt`).

---

## 3. Section 6.5.3 — Concurrency evidence

The Week-4 defect (duplicate `patientId` under two simultaneous registrations) is now a permanent
regression test. Eight threads are released from a common start line and each registers 250 patients:

```
tests successful : 27        tests failed : 0
  ConcurrencyTest.concurrentRegistrationDoesNotDuplicateIds              PASS
  ConcurrencyTest.concurrentTreatmentKeepsResourcesConsistent            PASS
```

`concurrentTreatmentKeepsResourcesConsistent` asserts four system-wide invariants after the workers
finish, which is the property the report calls "the queue, patient records, doctors, beds, database
and dashboard remained consistent as a complete system":

1. every occupied bed refers to a patient whose status is `IN_TREATMENT`;
2. every busy doctor refers to the patient recorded against them;
3. occupied beds = patients in treatment = busy doctors;
4. waiting + in-treatment + discharged = every patient ever registered (nobody vanishes).

---

## 4. Persistence evidence (restart proof)

`docs/evidence/persistence-restart.txt` — the console was closed after the TC-01…TC-04 session and
started again against the same file:

```
[ERverse] Restored session: 2 patient record(s), 3 doctor(s), 4 bed(s), 2 ambulance(s) from data/erverse.db
--- waiting:1  next:Low Priority Case  beds free:4  doctors free:3  en route:2
```

The discharged patient stayed discharged, the waiting patient went back into the heap at the correct
position, and the audit table held every event:

| table | rows |
|---|---|
| `patients` | 2 |
| `doctors` | 3 |
| `beds` | 4 |
| `ambulances` | 2 |
| `triage_events` | 5 |

All statements go through `PreparedStatement`; `PersistenceTest.preparedStatementsNeutraliseInjection`
registers a patient named `Robert'); DROP TABLE patients;--` and proves it is stored as data with the
table still intact — the Week-7 review requirement.

---

## 5. REST + dashboard evidence

`docs/evidence/rest-smoke-test.txt` — 31 checks covering the four test cases over HTTP, the ordering
rule's parity with `Patient.compareTo()`, the error vocabulary that mirrors the Java checked
exceptions (`400 INVALID_PATIENT_DATA`, `409 NO_DOCTORS_AVAILABLE`, `409 NO_BEDS_AVAILABLE`,
`409 ALREADY_DISCHARGED`, `404 PATIENT_NOT_FOUND`), the ETA simulation and the dashboard payload
contract. Notable checks:

```
TC-01  Critical patient is queue position 1                                  PASS
TC-01  Queue is sorted by severity rank                                      PASS
       Equal severity falls back to arrival order (FIFO)                     PASS
TC-02  Doctor assigned to the critical patient                               PASS
TC-02  ICU bed assigned to the critical patient                              PASS
       A failed dispatch leaves the patient queued (not dropped)             PASS
TC-03  Bed returns to FREE / Doctor returns to AVAILABLE                     PASS
       Double discharge -> 409 ALREADY_DISCHARGED                            PASS
TC-04  GET /api/report.csv returns text/csv, header + rows + summary         PASS
```

`docs/dashboard.png` (viewport) and `docs/dashboard-full.png` (whole page) are captured from the
running server with the demo surge loaded, one patient in treatment and one ambulance carrying a
pre-arrival CRITICAL case. They show the five metric cards, the live queue with its priority chips
and waiting timers, the bed grid (BED-101 ICU occupied), the doctor roster, the ambulance tracker,
the filtered patient history and the audit trail — i.e. everything §5.3 promises, and they are
suitable as the report's Figure 5.1 / Figure 7.1.

Note the queue in that capture: **K. Naveen (58, SpO₂ 91 %) is shown as HIGH, above R. Vasan's
MEDIUM**, although Naveen was registered as MEDIUM. That is the vitals escalation sweep working —
the §6.4 limitation being closed in a visible, auditable way.

---

## 6. Table 7.1 — Course outcomes, evidence index

| Course outcome | Where the evidence lives |
|---|---|
| **CO1** Data structures & algorithm analysis | `Patient.compareTo()`, `TriageSystem` heap usage; `docs/evidence/benchmark.txt` (measured O(log n) vs re-sorted baseline); `TriageOrderingTest` (500-patient surge); Table 6.1 above |
| **CO2** Object-oriented design | `Priority` enum with behaviour (`fromString`, `isMoreUrgentThan`), encapsulated `Patient`/`Doctor`/`Bed`/`Ambulance` with private fields and accessors, `Comparable<Patient>` polymorphism, `Bed.BedType` and `Ambulance.Status` nested enums |
| **CO3** Concurrency & robustness | `synchronized` mutators and accessors in `TriageSystem`; `AtomicInteger` id source; `ConcurrencyTest` (2000 concurrent registrations, invariant checks); checked `TriageException` hierarchy with one subclass per failure mode |
| **CO4** Persistence & system design | `DatabaseManager` schema + `PreparedStatement` persistence + session reload; `PersistenceTest`; `server/app.js` mirroring the engine with a single shared comparator definition; `docs/evidence/persistence-restart.txt` |
| **CO5** Testing & UI delivery | 27 JUnit tests and 31 REST checks; `docs/evidence/console-session.txt`; the glassmorphism dashboard with live polling, modal registration, reassessment, CSV export and print; `docs/dashboard*.png` |

---

## 7. Reproducing every number in this document

```bash
./scripts/fetch-deps.sh                 # once: downloads the two jars into lib/
./scripts/run-tests.sh                  # 27 JUnit tests  -> docs/evidence/junit-run.txt format

javac --release 17 -d bin src/erverse/*.java
javac --release 17 -cp bin -d testbin test/erverse/OrderingBenchmark.java
java -cp "bin:testbin" erverse.OrderingBenchmark        # the benchmark table (takes ~5 min)

java -cp "bin:lib/sqlite-jdbc-3.46.1.3.jar" erverse.Main < scripts/evidence/console-session.input

npm install && npm start                # terminal 1: API + dashboard on :3000
node scripts/smoke-test.js              # terminal 2: the 31 REST checks
```

`scripts/evidence/console-session.input` is the exact piped input that produced
`docs/evidence/console-session.txt`, so the transcript can be regenerated byte-for-byte (patient ids
are deterministic on a fresh `data/` directory).

> Timing numbers depend on the machine. The *shape* of the result — flat heap cost, growing list
> cost, crossover near a few hundred patients — is what the report claims, and that shape is stable
> across runs because it is a property of the asymptotics rather than of the hardware.
