package erverse;

/**
 * Represents one emergency bed. Chapter 4.1 describes ICU / trauma / general
 * bed tracking, so a bed carries a {@link BedType} and {@link TriageSystem}
 * tries to match the patient's severity band to the appropriate type.
 */
public class Bed {

    /** Bed classification, from highest to lowest acuity. */
    public enum BedType {
        ICU("ICU / resuscitation"),
        TRAUMA("Trauma bay"),
        GENERAL("General emergency");

        private final String label;

        BedType(String label) { this.label = label; }

        public String getLabel() { return label; }

        public static BedType fromString(String raw) {
            if (raw == null) return GENERAL;
            switch (raw.trim().toUpperCase()) {
                case "ICU": case "RESUS": return ICU;
                case "TRAUMA":            return TRAUMA;
                default:                  return GENERAL;
            }
        }
    }

    private final String bedId;
    private final BedType type;
    private boolean occupied;
    private Integer occupiedByPatientId;
    private String occupiedByPatientName;

    /** Legacy single-argument form: a general emergency bed. */
    public Bed(String bedId) {
        this(bedId, BedType.GENERAL);
    }

    public Bed(String bedId, BedType type) {
        this.bedId = bedId;
        this.type = type == null ? BedType.GENERAL : type;
        this.occupied = false;
    }

    public String getBedId() { return bedId; }

    public BedType getType() { return type; }

    public boolean isOccupied() { return occupied; }

    public Integer getOccupiedByPatientId() { return occupiedByPatientId; }

    public String getOccupiedByPatientName() { return occupiedByPatientName; }

    public void occupy(int patientId) { occupy(patientId, null); }

    public void occupy(int patientId, String patientName) {
        this.occupied = true;
        this.occupiedByPatientId = patientId;
        this.occupiedByPatientName = patientName;
    }

    public void release() {
        this.occupied = false;
        this.occupiedByPatientId = null;
        this.occupiedByPatientName = null;
    }

    @Override
    public String toString() {
        return String.format("Bed[%s] %-18s - %s", bedId, "(" + type.name() + ")",
                occupied ? "OCCUPIED by Patient #" + occupiedByPatientId
                        + (occupiedByPatientName == null ? "" : " " + occupiedByPatientName)
                        + " since arrival" : "FREE");
    }
}
