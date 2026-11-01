public class Platform {

    private int platformId;
    private int platformLength;

    private boolean occupied = false;
    private String currentTrainId = null;

    public Platform(int platformId, int platformLength) {

        this.platformId = platformId;
        this.platformLength = platformLength;
    }

    // Encapsulation
    public boolean occupyPlatform(String trainId) {

        if (occupied) {
            return false;
        }

        occupied = true;
        currentTrainId = trainId;

        return true;
    }

    public void releasePlatform(String trainId) {

        if (trainId.equals(currentTrainId)) {

            occupied = false;
            currentTrainId = null;
        }
    }

    public int getPlatformId() {
        return platformId;
    }

    public int getPlatformLength() {
        return platformLength;
    }

    public boolean isOccupied() {
        return occupied;
    }

    public String getCurrentTrainId() {
        return currentTrainId;
    }
}