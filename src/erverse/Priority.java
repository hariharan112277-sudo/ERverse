package erverse;

/**
 * Represents the four medical urgency levels used to triage patients.
 * Lower rank value = higher urgency (treated first), so the enum doubles as
 * the primary key of the natural ordering defined in {@link Patient#compareTo(Patient)}.
 *
 * <p>Ranks mirror the four-band structure of the Emergency Severity Index
 * described in Chapter 1 of the PBL report (Level 1 resuscitation ... Level 4
 * non-urgent).</p>
 */
public enum Priority {

    CRITICAL(1, "Critical - immediate life threat"),
    HIGH(2, "High - urgent, treat within 15 min"),
    MEDIUM(3, "Medium - treat within 60 min"),
    LOW(4, "Low - non urgent");

    private final int rank;
    private final String description;

    Priority(int rank, String description) {
        this.rank = rank;
        this.description = description;
    }

    public int getRank() {
        return rank;
    }

    public String getDescription() {
        return description;
    }

    /** True when this priority is clinically more urgent than {@code other}. */
    public boolean isMoreUrgentThan(Priority other) {
        return this.rank < other.rank;
    }

    /**
     * Parses a user supplied severity label. Accepts the enum name in any case
     * ("critical", "Critical"), the numeric rank ("1".."4") and the short
     * labels used by the REST layer ("P1".."P4", "RED"/"ORANGE"/"YELLOW"/"GREEN").
     *
     * @throws InvalidPatientDataException when the text cannot be mapped to a band
     */
    public static Priority fromString(String raw) throws InvalidPatientDataException {
        if (raw == null || raw.isBlank()) {
            throw new InvalidPatientDataException("Severity is required (CRITICAL/HIGH/MEDIUM/LOW)");
        }
        String s = raw.trim().toUpperCase().replace("P", "").replace("_", "");
        switch (s) {
            case "1": case "CRITICAL": case "RED":            return CRITICAL;
            case "2": case "HIGH":     case "ORANGE":         return HIGH;
            case "3": case "MEDIUM":   case "YELLOW":         return MEDIUM;
            case "4": case "LOW":      case "GREEN":          return LOW;
            default:
                throw new InvalidPatientDataException(
                        "Unknown severity '" + raw + "'. Use CRITICAL, HIGH, MEDIUM or LOW.");
        }
    }

    @Override
    public String toString() {
        return name();
    }

    /** Rank-ordered, human readable band list, used by the console menus. */
    public static String menuLine() {
        return "1-Critical  2-High  3-Medium  4-Low";
    }
}
