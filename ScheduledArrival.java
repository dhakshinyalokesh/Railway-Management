public class ScheduledArrival extends PlatformRequest {

    public ScheduledArrival(TrainService train,
                            long arrivalOrder) {

        super(train, arrivalOrder);
    }

    @Override
    public int priorityRank() {
        return 1;
    }
}