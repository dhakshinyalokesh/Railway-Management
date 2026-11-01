public abstract class PlatformRequest
        implements Comparable<PlatformRequest> {

    protected TrainService train;
    protected long arrivalOrder;

    public PlatformRequest(TrainService train,
                           long arrivalOrder) {

        this.train = train;
        this.arrivalOrder = arrivalOrder;
    }

    public abstract int priorityRank();

    public TrainService getTrain() {
        return train;
    }

    public long getArrivalOrder() {
        return arrivalOrder;
    }

    @Override
    public int compareTo(PlatformRequest other) {

        // Emergency / Connection / Scheduled

        int result = Integer.compare(
                other.priorityRank(),
                this.priorityRank());

        if (result != 0) {
            return result;
        }

        // Declared train priority

        result = Integer.compare(
                other.getTrain().getDeclaredPriority(),
                this.getTrain().getDeclaredPriority());

        if (result != 0) {
            return result;
        }

        // Arrival order for deterministic tie

        return Long.compare(
                this.arrivalOrder,
                other.arrivalOrder);
    }
}