public class EmergencyArrival extends PlatformRequest {

    public EmergencyArrival(TrainService train,
                            long arrivalOrder) {

        super(train, arrivalOrder);
    }

    @Override
    public int priorityRank() {
        return 3;
    }
}