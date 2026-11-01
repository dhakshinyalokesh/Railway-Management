public class Reservation {

    private int reservationId;
    private Passenger passenger;
    private String trainId;
    private String status;

    public Reservation(int reservationId, Passenger passenger, String trainId) {
        this.reservationId = reservationId;
        this.passenger = passenger;
        this.trainId = trainId;
        this.status = "CONFIRMED";
    }

    public int getReservationId() {
        return reservationId;
    }

    public Passenger getPassenger() {
        return passenger;
    }

    public String getTrainId() {
        return trainId;
    }

    public String getStatus() {
        return status;
    }

    public void cancel() {
        status = "CANCELLED";
    }
}