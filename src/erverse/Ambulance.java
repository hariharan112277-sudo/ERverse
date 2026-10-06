package erverse;

/**
 * Represents an incoming or returning ambulance unit.
 *
 * <p>Supports the pre-arrival planning scenario of Section 8.3.2: a unit may be
 * flagged as carrying a known patient (name + assessed severity) so that the
 * department can prepare a bed and alert the matching doctor before the vehicle
 * reaches the entrance.</p>
 */
public class Ambulance {

    public enum Status {
        EN_ROUTE("En route to ED"),
        ARRIVED("Arrived at ED"),
        AVAILABLE("Available for dispatch");

        private final String label;

        Status(String label) { this.label = label; }

        public String getLabel() { return label; }
    }

    private final String ambulanceId;
    private Status status;
    private int etaMinutes;
    private String patientOnBoard;
    private Priority patientPriority;

    public Ambulance(String ambulanceId, int etaMinutes) {
        this.ambulanceId = ambulanceId;
        this.etaMinutes = Math.max(0, etaMinutes);
        this.status = this.etaMinutes == 0 ? Status.ARRIVED : Status.EN_ROUTE;
    }

    public String getAmbulanceId() { return ambulanceId; }

    /** Textual status, kept for compatibility with the console/report formats. */
    public String getStatus() { return status.name(); }

    public Status getStatusEnum() { return status; }

    public void setStatus(String status) {
        try {
            this.status = Status.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException e) {
            this.status = Status.EN_ROUTE;
        }
    }

    public void setStatus(Status status) { this.status = status; }

    public int getEtaMinutes() { return etaMinutes; }

    public void setEtaMinutes(int etaMinutes) { this.etaMinutes = Math.max(0, etaMinutes); }

    public String getPatientOnBoard() { return patientOnBoard; }

    public Priority getPatientPriority() { return patientPriority; }

    /** Notifies the ED that this unit is bringing a known patient (Section 8.3.2). */
    public void notifyPatientOnBoard(String patientName, Priority priority) {
        this.patientOnBoard = patientName;
        this.patientPriority = priority;
    }

    /** Dispatches an available unit toward a new call with the given ETA. */
    public void dispatch(int etaMinutes) {
        this.etaMinutes = Math.max(1, etaMinutes);
        this.status = Status.EN_ROUTE;
        this.patientOnBoard = null;
        this.patientPriority = null;
    }

    /** Marks the unit free again after a hand-over. */
    public void markAvailable() {
        this.status = Status.AVAILABLE;
        this.etaMinutes = 0;
        this.patientOnBoard = null;
        this.patientPriority = null;
    }

    /**
     * Advances the simulation by one minute. An EN_ROUTE unit whose ETA reaches
     * zero transitions to ARRIVED - the countdown shown on the dashboard.
     *
     * @return true when the unit changed state during this tick
     */
    public boolean tick() {
        if (status == Status.EN_ROUTE) {
            if (etaMinutes > 0) {
                etaMinutes--;
            }
            if (etaMinutes == 0) {
                status = Status.ARRIVED;
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(String.format("Ambulance[%s] Status:%-10s ETA:%d min",
                ambulanceId, status.name(), etaMinutes));
        if (patientOnBoard != null) {
            sb.append("  carrying ").append(patientOnBoard)
              .append(patientPriority == null ? "" : " (" + patientPriority + ")")
              .append(" - prepare bed");
        }
        return sb.toString();
    }
}
