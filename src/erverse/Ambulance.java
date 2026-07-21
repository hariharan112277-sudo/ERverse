package erverse;

public class Ambulance {
    private final String ambulanceId;
    private String status; // EN_ROUTE, ARRIVED, AVAILABLE
    private int etaMinutes;

    public Ambulance(String ambulanceId, int etaMinutes) {
        this.ambulanceId = ambulanceId;
        this.etaMinutes = etaMinutes;
        this.status = "EN_ROUTE";
    }

    public String getAmbulanceId() { return ambulanceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getEtaMinutes() { return etaMinutes; }
    public void setEtaMinutes(int etaMinutes) { this.etaMinutes = etaMinutes; }

    @Override
    public String toString() {
        return String.format("Ambulance[%s] Status:%-10s ETA:%d min", ambulanceId, status, etaMinutes);
    }
}
