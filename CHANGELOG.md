# CHANGELOG — deviations from the submitted PBL report

This file records **every place where the implementation deliberately differs from the submitted
report**, why, and what the report says instead. Nothing here is an omission: each item is a defect,
an ambiguity or an under-specification that was found while building the code, and each was resolved
in favour of the behaviour a real emergency department needs.

Read this before the viva — examiners generally prefer a candidate who can explain a correction to a
candidate who cannot explain a bug.

---

## A. Defects in the report's own code snippets (fixed)

### A1. `treatNextPatient()` dropped patients out of the queue — report §5.2.3

**Report shows:**

```java
Patient patient = waitingQueue.poll();                       // removed FIRST
Doctor doctor = findAvailableDoctor()
    .orElseThrow(() -> new NoDoctorsAvailableException("No doctors free"));
Bed bed = findAvailableBed()
    .orElseThrow(() -> new NoBedsAvailableException("No beds free"));
```

**Problem.** `poll()` executes before either check. If no bed is free, the exception is thrown *after*
the patient has already left the heap — the patient disappears from the waiting queue with their
status still `WAITING`, which is the exact "silently drop a patient under load" failure Chapter 1
claims the system is designed to prevent.

**Fixed behaviour.** Peek the heap root → secure a doctor → secure a bed → *only then* `poll()`.

```java
Patient next = waitingQueue.peek();
if (next == null) return "No patients waiting.";
Doctor doctor = selectDoctor(next.getPriority())
        .orElseThrow(() -> new NoDoctorsAvailableException(...));
Bed bed = selectBed(next.getPriority())
        .orElseThrow(() -> new NoBedsAvailableException(...));
waitingQueue.poll();                                         // dequeue is now safe
```

**Evidence.** `ResourceAllocationTest.doctorExhaustionKeepsPatientQueued` and
`bedExhaustionKeepsPatientQueued` assert that the patient stays at the head of the heap; the REST
smoke test asserts the same rule over HTTP (`A failed dispatch leaves the patient queued`).

### A2. A failed dispatch also consumed a doctor

**Problem.** With the report's ordering the doctor was marked busy before the bed was checked, so a
bed shortage silently burned a doctor.

**Fixed behaviour.** Resources are only mutated once *both* are available, so a `NoBedsAvailableException`
leaves the roster untouched (asserted: `countAvailableDoctors()` is unchanged after the failure).

### A3. `dischargePatient(int, String, String)` required the caller to remember the resources — report §TriageSystem

**Report shows:** `dischargePatient(int patientId, String doctorName, String bedId)`.

**Problem.** The caller has to know which doctor and bed were assigned. In the dashboard this is where
the Week 10 "flaky discharge test on double-click" came from: a second, stale call released a doctor
who had already been re-assigned to a new patient.

**Fixed behaviour.** `dischargePatient(int patientId)` reads the assigned resources off the patient
record itself, is idempotent-safe (a second discharge raises `TriageException("...already discharged")`
instead of silently corrupting state), and logs an audit row. Test:
`DischargeAndReassessmentTest.doubleDischargeIsRejected`.

### A4. The natural ordering was not fully deterministic

**Report §5.2.1** breaks ties only on arrival timestamp. Two registrations inside the same clock tick
(`LocalDateTime.now()` has nanosecond resolution but the report's CSV/text output truncates to
seconds) could therefore compare as equal, and `PriorityQueue` would return them in an
implementation-defined order that differed between the Java console and the dashboard — precisely the
Week 8 ordering mismatch, only partially fixed.

**Fixed behaviour.** `compareTo()` compares rank → arrival timestamp → **patient id**:

```java
int diff = Integer.compare(this.priority.getRank(), other.priority.getRank());
if (diff != 0) return diff;
diff = this.arrivalTime.compareTo(other.arrivalTime);
if (diff != 0) return diff;
return Integer.compare(this.patientId, other.patientId);   // total order
```

The identical rule is implemented once in `server/app.js` (`comparePatients()`), which is the "one
specification, two implementations" discipline the report asks for in §3.6.2. Test:
`TriageOrderingTest.identicalTimestampsStillOrderDeterministically`.

### A5. TC-02's expected values did not match the seeded resources

**Report Table 6.2** states TC-02 expects "Doctor D01 & Bed **BED-102**". The report's own Figure 5.1
shows BED-101 as the ICU bed, and a fresh ED would place a CRITICAL patient in ICU — so BED-102 was
simply carried over from an earlier draft. The test matrix in `docs/VERIFICATION.md` records the
values the code actually produces (D01 trauma specialist + BED-101 ICU), consistent with the
report's Figure 5.1.

---

## B. Places where the report under-specified the design (decided and documented)

### B1. Four exception classes were squeezed into one file

**Report Table 5.1 / Appendix A.1** list a single module `TriageException.java` for "custom exception
classes". Compiling four top-level classes in one file produces the javac warning *"auxiliary class
should not be accessed from outside its own source file"* on **every** usage site in `Priority`,
`TriageSystem` and `Main`.

**Decision.** `TriageException.java` holds the base class; `NoDoctorsAvailableException`,
`NoBedsAvailableException` and `InvalidPatientDataException` were split into their own files, so the
build is warning-free with `-Xlint:all`. The hierarchy, the checked-exception decision and the
naming are unchanged.

### B2. Beds gained a type

The report's `Bed.java` (Table 5.1) is "occupancy state and the id of the occupying patient", but
§4.1 and §6.4 both talk about "ICU/trauma/general beds" and "a specialisation-keyed index". Beds now
carry `BedType` (ICU / TRAUMA / GENERAL) and allocation is acuity-aware: CRITICAL → ICU first (but
takes any free bed rather than waiting), HIGH → trauma bay, MEDIUM/LOW → general (ICU stays reserved
for resuscitations). Bed ids BED-101…BED-104 match the report's Figure 5.1.

### B3. The dashboard "Export CSV" was specified as a frontend action (TC-04) with no endpoint

Table 5.2 has no CSV route, yet TC-04 clicks "Export CSV" and expects
`ERverse_Daily_Report.csv`. **Decision:** the report is generated server-side at `GET /api/report.csv`
with `Content-Disposition: attachment`, and the dashboard button is a plain download link. Both the
Java console (`menu 11` → `TriageSystem.exportDailyReportCsv`) and the API emit the same column set,
and both quote free-text fields so a comma in a name or symptom cannot break the layout.

### B4. "Thread-safe" was extended to the read paths, and patient ids made atomic

§2.10 argues correctly that `PriorityQueue` is not thread-safe and that `TriageSystem` must own the
lock. The report's snippets only synchronize the three mutators, which still lets a reader observe a
heap mid-sift-down through the unguarded `getWaitingQueue()` accessor it exposes.

**Decision.** All queue accessors (`getWaitingQueueSorted`, `peekNextPatient`, `waitingCount`,
`queuePosition`, …) are `synchronized` too, `getWaitingQueue()` no longer hands out the live heap
(it returns a sorted copy), and the shared id counter is an `AtomicInteger` as a second line of
defence behind the monitor.

### B5. Vitals were recorded but never used — the report's own limitation §6.4

§6.4 admits "vitals … are not yet used to automatically escalate a patient's priority if their
condition deteriorates while waiting." **Decision:** that gap is closed with an explicit, logged,
opt-in sweep (`menu 9`, `POST /api/vitals-sweep`) rather than silent automation: a *waiting* patient
with SpO₂ < 92 % or HR outside 40–130 bpm is promoted exactly one band, with the reason recorded in
the audit trail. Staff reassessment (`menu 7`, `POST /api/patients/:id/reassess`) satisfies §8.4's
requirement that clinical judgement must always be able to override the queue.

### B6. `Priority.fromString()` was specified in Table 5.1 but never shown

Implemented to accept `CRITICAL`/`critical`, `1`–`4`, `P1`–`P4` and the triage colours
`RED`/`ORANGE`/`YELLOW`/`GREEN`, raising `InvalidPatientDataException` on anything else — so the
console, the REST API and the dashboard all accept the same vocabulary.

### B7. Registration validation limits are now explicit

`InvalidPatientDataException` needs concrete rules to be testable. Adopted: non-blank name; age
1–120; heart rate 20–250 bpm if supplied; SpO₂ 0–100 % if supplied; blood pressure `ddd/dd` or
`ddd/ddd` if supplied; severity must map to a band. Any blank vital means "not recorded".

### B8. Dashboard styling is self-contained

§5.3 lists FontAwesome among the frontend stack. It was dropped in favour of inline SVG so the page
renders identically with no network access (offline demo, air-gapped lab machines, printed handouts).
The glassmorphic look, panels and layout of Figure 5.1 are unchanged.

---

## C. Corrections made while testing the finished system

| # | Symptom | Cause | Fix |
|---|---|---|---|
| C1 | Console died with an uncaught `NoSuchElementException: No line found` when stdin reached EOF (Ctrl-D, or a piped test script that ran out of commands) | `Scanner.nextLine()` throws at EOF and no menu path handled it | `Main.nextLine()` converts EOF into a clean ordered shutdown; `main()` moved its `db.close()` into a `finally` block so the JDBC connection is always released |
| C2 | The "double-click discharge" flakiness from the Week 10 log | no guard against discharging an already-discharged patient | second call raises `TriageException("...already discharged")`; the dashboard disables the button for discharged rows and surfaces the error as a toast (C1-verified by `doubleDischargeIsRejected` and the REST check `Double discharge -> 409 ALREADY_DISCHARGED`) |
| C3 | Dashboard requested a favicon that did not exist (404 in the browser console) | no icon declared | inline SVG favicon via a `data:` URI |
| C4 | `👤` glyph rendered as a missing-glyph box on the bed grid | no emoji font in headless/Linux environments | replaced with an inline SVG person icon |
| C5 | Auto-placed CSS grid left a visible hole under the queue panel | implicit placement of the ambulance panel | explicit `grid-area` placement for every panel, plus internal scrolling on the queue list |

---

### C6. The benchmark result is reported honestly rather than favourably

§6.3 asserts that the heap stays near-flat while the re-sorted `ArrayList` grows with queue size.
That is confirmed — but the benchmark (`test/erverse/OrderingBenchmark.java`, output in
`docs/evidence/benchmark.txt`) also shows the baseline is **faster for n ≤ 50** (0.185 ms vs 0.201 ms
at n=5); the crossover is around n ≈ 500, after which the heap leads by 2.1× and eventually 576×.

**Decision.** The measurement is reported as it came out, with the crossover stated, in
`docs/VERIFICATION.md` §1 rather than "edited" to always favour the chosen structure. It is also the
stronger argument: the heap is chosen because its cost stays flat exactly when the waiting room
surges and the naive baseline degrades *precisely when the department is busiest* — and the printed
table can be regenerated live during the viva.

---

## D. What is still deliberately unfinished (matches report §8.2)

These remain out of scope and are listed as future work in the report, so no code claims them:

* **IoT vital streaming** — vitals are entered at triage, not streamed from bedside monitors.
* **AI ETA prediction** — ambulance ETAs are entered manually or simulated with the "+1 min" tick.
* **Multi-hospital synchronisation** — single-site state only.
* **Indexed resource allocation** — doctor/bed lookup is still a linear scan (`O(d)`, `O(b)`), which
  the report justifies for a few dozen resources.
* **Push updates** — the dashboard polls every 5 s; WebSockets are discussed in §8.5 but not built.
* **Auth, RBAC, encryption, retention policy** — §8.4 requirements for a production deployment, not
  implemented in this academic build.
