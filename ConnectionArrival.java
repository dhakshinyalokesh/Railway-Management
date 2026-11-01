public class ConnectionArrival extends PlatformRequest {

    public ConnectionArrival(TrainService train,
                             long arrivalOrder) {

        super(train, arrivalOrder);
    }

    @Override
    public int priorityRank() {
        return 2;
    }
}