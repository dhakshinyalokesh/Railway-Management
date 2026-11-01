public class PlatformOccupation {

    private int platformId;
    private String trainId;

    private int startTime;
    private int endTime;

    public PlatformOccupation(int platformId, String trainId,
                              int startTime, int endTime) {

        this.platformId = platformId;
        this.trainId = trainId;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public boolean overlaps(int newStart, int newEnd) {

        return newStart < endTime && newEnd > startTime;
    }

    public int getPlatformId() {
        return platformId;
    }

    public String getTrainId() {
        return trainId;
    }

    public int getStartTime() {
        return startTime;
    }

    public int getEndTime() {
        return endTime;
    }

    public void setStartTime(int startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(int endTime) {
        this.endTime = endTime;
    }
}