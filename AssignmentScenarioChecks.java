import java.util.ArrayList;
import java.util.Scanner;

/** Small, dependency-free checks for the assignment's required scenarios. */
public class AssignmentScenarioChecks {

    public static void main(String[] args) {
        checkCancellationPromotesOldestPassenger();
        checkOverlappingAndOverlongAllocationsAreRejected();
        checkDelayExtendsPlatformOccupation();
        System.out.println("All assignment scenario checks passed.");
    }

    private static void checkCancellationPromotesOldestPassenger() {
        TrainService train = new TrainService("T", "Test", 10, 30, 100, 1);
        Reservation first = null;
        for (int i = 1; i <= 10; i++) {
            Passenger passenger = new Passenger("P" + i, "Passenger " + i);
            Reservation booked = train.bookSeat(passenger, i);
            if (i == 1) first = booked;
        }
        Passenger oldest = new Passenger("P11", "Oldest waiter");
        Passenger next = new Passenger("P12", "Next waiter");
        require(train.bookSeat(oldest, 11) == null, "11th passenger should wait");
        require(train.bookSeat(next, 12) == null, "12th passenger should wait");

        Reservation promoted = train.cancelBooking(first.getReservationId(), 13);
        require(promoted != null, "cancellation should promote a passenger");
        require(promoted.getPassenger() == oldest, "FIFO should promote the oldest waiter");
        require(train.getAvailableSeats() == 0, "promoted passenger should occupy the freed seat");
        System.out.println("PASS cancellation promotes oldest FIFO passenger");
    }

    private static void checkOverlappingAndOverlongAllocationsAreRejected() {
        SimplePlatformAllocationPolicy policy = new SimplePlatformAllocationPolicy();
        ArrayList<Platform> platforms = new ArrayList<>();
        platforms.add(new Platform(1, 200));
        platforms.add(new Platform(2, 280));
        platforms.add(new Platform(3, 350));
        ArrayList<PlatformOccupation> timetable = new ArrayList<>();
        timetable.add(new PlatformOccupation(1, "OTHER1", 100, 180));
        timetable.add(new PlatformOccupation(2, "OTHER2", 100, 180));
        timetable.add(new PlatformOccupation(3, "OTHER3", 100, 180));

        TrainService overlapping = new TrainService("T1", "Overlap", 120, 150, 180, 1);
        require(policy.allocatePlatform(overlapping, platforms, timetable, 10) == null,
                "overlapping reservation must not be assigned");

        TrainService overlong = new TrainService("T2", "Overlong", 200, 220, 351, 1);
        require(policy.allocatePlatform(overlong, platforms, new ArrayList<>(), 10) == null,
                "train longer than every platform must not be assigned");
        System.out.println("PASS overlapping and overlong train requests are rejected");
    }

    private static void checkDelayExtendsPlatformOccupation() {
        RailwaySystem.trains.clear();
        RailwaySystem.timetable.clear();
        RailwaySystem.releaseHeap.clear();
        TrainService train = new TrainService("T1", "Delayed", 100, 130, 180, 1);
        RailwaySystem.trains.put("T1", train);
        PlatformOccupation occupation = new PlatformOccupation(1, "T1", 100, 140);
        RailwaySystem.timetable.add(occupation);
        RailwaySystem.sc = new Scanner("T1 20");

        RailwaySystem.addDelay();

        require(train.getArrivalTime() == 120 && train.getDepartureTime() == 150,
                "delay should shift arrival and departure");
        require(occupation.getStartTime() == 120 && occupation.getEndTime() == 160,
                "platform reservation should shift and preserve its clearance buffer");
        System.out.println("PASS delay shifts the platform occupation and clearance end");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
