package erverse;

public class Bed {
    private final String bedId;
    private boolean occupied;
    private Integer occupiedByPatientId;

    public Bed(String bedId) {
        this.bedId = bedId;
        this.occupied = false;
    }

    public String getBedId() { return bedId; }
    public boolean isOccupied() { return occupied; }

    public void occupy(int patientId) {
        this.occupied = true;
        this.occupiedByPatientId = patientId;
    }

    public void release() {
        this.occupied = false;
        this.occupiedByPatientId = null;
    }

    public Integer getOccupiedByPatientId() { return occupiedByPatientId; }

    @Override
    public String toString() {
        return String.format("Bed[%s] - %s", bedId,
                occupied ? "OCCUPIED by Patient #" + occupiedByPatientId : "FREE");
    }
}
