package erverse;

/**
 * Represents the four medical urgency levels used to triage patients.
 * Lower ordinal value = higher urgency (treated first).
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
}
