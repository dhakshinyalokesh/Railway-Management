public class ReleaseRecord
        implements Comparable<ReleaseRecord> {

    private int releaseTime;
    private int platformId;
    private String trainId;

    public ReleaseRecord(int releaseTime,
                         int platformId,
                         String trainId) {

        this.releaseTime = releaseTime;
        this.platformId = platformId;
        this.trainId = trainId;
    }

    @Override
    public int compareTo(ReleaseRecord other) {

        return Integer.compare(
                this.releaseTime,
                other.releaseTime);
    }

    public int getReleaseTime() {
        return releaseTime;
    }

    public int getPlatformId() {
        return platformId;
    }

    public String getTrainId() {
        return trainId;
    }
}