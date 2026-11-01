import java.util.ArrayDeque;
import java.util.ArrayList;

public class TrainService {

    private String trainId;
    private String trainName;

    private int arrivalTime;
    private int departureTime;

    private int trainLength;
    private int declaredPriority;

    private String seatClass = "General";
    private String bookingSegment = "End-to-End";

    private final int totalSeats = 10;
    private int availableSeats = 10;

    private boolean arrivalConfirmed = false;
    private boolean departureConfirmed = false;

  
    private ArrayList<Reservation> confirmedBookings =
            new ArrayList<>();

   
    private ArrayDeque<Passenger> waitingQueue =
            new ArrayDeque<>();

    public TrainService(String trainId, String trainName,
                        int arrivalTime, int departureTime,
                        int trainLength, int declaredPriority) {

        this.trainId = trainId;
        this.trainName = trainName;
        this.arrivalTime = arrivalTime;
        this.departureTime = departureTime;
        this.trainLength = trainLength;
        this.declaredPriority = declaredPriority;
    }

  
    public Reservation bookSeat(Passenger passenger, int reservationId) {

        if (availableSeats > 0) {

            Reservation reservation =
                    new Reservation(reservationId, passenger, trainId);

            confirmedBookings.add(reservation);
            availableSeats--;

            return reservation;
        }

        waitingQueue.addLast(passenger);
        return null;
    }

    public Reservation cancelBooking(int reservationId, int newReservationId) {

        for (int i = 0; i < confirmedBookings.size(); i++) {

            Reservation reservation = confirmedBookings.get(i);

            if (reservation.getReservationId() == reservationId
                    && reservation.getStatus().equals("CONFIRMED")) {

                reservation.cancel();
                confirmedBookings.remove(i);

                availableSeats++;

                // Promote oldest waiting passenger
                if (!waitingQueue.isEmpty()) {

                    Passenger passenger = waitingQueue.removeFirst();

                    Reservation promoted =
                            new Reservation(newReservationId,
                                    passenger, trainId);

                    confirmedBookings.add(promoted);
                    availableSeats--;

                    return promoted;
                }

                return null;
            }
        }

        return null;
    }

    public void addDelay(int minutes) {
        arrivalTime += minutes;
        departureTime += minutes;
    }

    public void confirmArrival() {
        arrivalConfirmed = true;
    }

    public void confirmDeparture() {
        departureConfirmed = true;
    }

    public String getTrainId() {
        return trainId;
    }

    public String getTrainName() {
        return trainName;
    }

    public int getArrivalTime() {
        return arrivalTime;
    }

    public int getDepartureTime() {
        return departureTime;
    }

    public int getTrainLength() {
        return trainLength;
    }

    public int getDeclaredPriority() {
        return declaredPriority;
    }

    public void setDeclaredPriority(int priority) {
        declaredPriority = priority;
    }

    public int getAvailableSeats() {
        return availableSeats;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public String getSeatClass() {
        return seatClass;
    }

    public String getBookingSegment() {
        return bookingSegment;
    }

    public ArrayDeque<Passenger> getWaitingQueue() {
        return waitingQueue;
    }

    public ArrayList<Reservation> getConfirmedBookings() {
        return confirmedBookings;
    }
}