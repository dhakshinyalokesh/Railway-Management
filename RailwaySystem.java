import java.util.*;

public class RailwaySystem {

    static Scanner sc = new Scanner(System.in);

    static final String STATION_NAME =
            "Central Railway Station";

    static final int CLEARANCE_BUFFER = 10;

    // HashMap

    static HashMap<String, TrainService> trains =
            new HashMap<>();

    static HashMap<String, Passenger> passengers =
            new HashMap<>();

    static HashMap<Integer, Reservation> reservations =
            new HashMap<>();

    static HashMap<Integer, Platform> platforms =
            new HashMap<>();


    // PriorityQueue for train requests

    static PriorityQueue<PlatformRequest> platformQueue =
            new PriorityQueue<>();


    // Min-heap

    static PriorityQueue<ReleaseRecord> releaseHeap =
            new PriorityQueue<>();


    // Sorted ArrayList

    static ArrayList<PlatformOccupation> timetable =
            new ArrayList<>();


    static PlatformAllocationPolicy allocationPolicy =
            new SimplePlatformAllocationPolicy();


    static int nextReservationId = 1;

    static long nextArrivalOrder = 1;

    static int currentTime = 0;


    public static void main(String[] args) {

        initializeData();

        int choice;

        do {

            releasePlatforms();

            System.out.println("\n==============================");
            System.out.println(STATION_NAME);
            System.out.println("Current Time: " + currentTime);
            System.out.println("==============================");

            System.out.println("1. View Trains");
            System.out.println("2. View Platforms");
            System.out.println("3. Book Ticket");
            System.out.println("4. Cancel Booking");
            System.out.println("5. View Waiting List");
            System.out.println("6. Request Platform");
            System.out.println("7. Allocate Platform");
            System.out.println("8. Confirm Arrival");
            System.out.println("9. Confirm Departure");
            System.out.println("10. Add Delay");
            System.out.println("11. Change Priority");
            System.out.println("12. Advance Time");
            System.out.println("13. View Timetable");
            System.out.println("14. Exit");

            System.out.print("\nEnter choice: ");
            choice = sc.nextInt();

            switch (choice) {

                case 1 -> viewTrains();
                case 2 -> viewPlatforms();
                case 3 -> bookTicket();
                case 4 -> cancelBooking();
                case 5 -> viewWaitingList();
                case 6 -> requestPlatform();
                case 7 -> allocatePlatform();
                case 8 -> confirmArrival();
                case 9 -> confirmDeparture();
                case 10 -> addDelay();
                case 11 -> changePriority();
                case 12 -> advanceTime();
                case 13 -> viewTimetable();

                case 14 ->
                        System.out.println("Program Closed.");

                default ->
                        System.out.println("Invalid choice.");
            }

        } while (choice != 14);
    }



    static void initializeData() {

    // 6 trains
    trains.put("T101",
            new TrainService(
                    "T101", "Kongu Express",
                    100, 130, 180, 2));

    trains.put("T102",
            new TrainService(
                    "T102", "Salem Express",
                    140, 170, 220, 3));

    trains.put("T103",
            new TrainService(
                    "T103", "Erode Passenger",
                    180, 210, 160, 1));

    trains.put("T104",
            new TrainService(
                    "T104", "Chennai Express",
                    220, 260, 300, 4));

    trains.put("T105",
            new TrainService(
                    "T105", "Coimbatore Special",
                    280, 310, 200, 2));

    trains.put("T106",
            new TrainService(
                    "T106", "Night Express",
                    330, 360, 250, 3));


    // 3 platforms
    platforms.put(1, new Platform(1, 200));
    platforms.put(2, new Platform(2, 280));
    platforms.put(3, new Platform(3, 350));


    // No default bookings.
    // All trains start with 10 available seats.
    // Passengers book tickets through the menu.

    System.out.println(
            "System initialized with 6 trains, "
                    + "3 platforms and 10 seats per train.");
}


  

    static void viewTrains() {

        for (TrainService train : trains.values()) {

            System.out.println(
                    "\n" + train.getTrainId()
                            + " - " + train.getTrainName());

            System.out.println(
                    "Arrival: " + train.getArrivalTime()
                            + " Departure: "
                            + train.getDepartureTime());

            System.out.println(
                    "Length: " + train.getTrainLength());

            System.out.println(
                    "Priority: "
                            + train.getDeclaredPriority());

            System.out.println(
                    "Seats: "
                            + train.getAvailableSeats()
                            + "/" + train.getTotalSeats());

            System.out.println(
                    "Class: " + train.getSeatClass());

            System.out.println(
                    "Segment: "
                            + train.getBookingSegment());
        }
    }



    static void viewPlatforms() {

        for (Platform platform : platforms.values()) {

            System.out.println(
                    "Platform "
                            + platform.getPlatformId()
                            + " Length: "
                            + platform.getPlatformLength()
                            + " Occupied: "
                            + platform.isOccupied());
        }
    }


 

    static void bookTicket() {

        sc.nextLine();

        System.out.print("Passenger name: ");
        String name = sc.nextLine();

        System.out.print("Train ID: ");
        String trainId = sc.nextLine();

        TrainService train = trains.get(trainId);

        if (train == null) {

            System.out.println("Train not found.");
            return;
        }

        String passengerId =
                "P" + (passengers.size() + 1);

        Passenger passenger =
                new Passenger(passengerId, name);

        passengers.put(passengerId, passenger);

        Reservation reservation =
                train.bookSeat(
                        passenger,
                        nextReservationId);

        if (reservation != null) {

            reservations.put(
                    nextReservationId,
                    reservation);

            System.out.println(
                    "Booking confirmed. ID: "
                            + nextReservationId);

            nextReservationId++;

        } else {

            System.out.println(
                    "No seats. Added to FIFO waiting list.");
        }
    }


   

    static void cancelBooking() {

        System.out.print("Reservation ID: ");
        int reservationId = sc.nextInt();

        Reservation reservation =
                reservations.get(reservationId);

        if (reservation == null) {

            System.out.println("Reservation not found.");
            return;
        }

        TrainService train =
                trains.get(reservation.getTrainId());

        Reservation promoted =
                train.cancelBooking(
                        reservationId,
                        nextReservationId);

        reservation.cancel();

        System.out.println("Booking cancelled.");

        if (promoted != null) {

            reservations.put(
                    nextReservationId,
                    promoted);

            System.out.println(
                    "Oldest waiting passenger promoted.");

            nextReservationId++;
        }
    }



    static void viewWaitingList() {

        System.out.print("Train ID: ");
        String trainId = sc.next();

        TrainService train = trains.get(trainId);

        if (train == null) {

            System.out.println("Train not found.");
            return;
        }

        if (train.getWaitingQueue().isEmpty()) {

            System.out.println("Waiting list is empty.");

        } else {

            System.out.println("FIFO Waiting List:");

            for (Passenger passenger :
                    train.getWaitingQueue()) {

                System.out.println(
                        passenger.getPassengerId()
                                + " - "
                                + passenger.getName());
            }
        }
    }



    static void requestPlatform() {

        System.out.print("Train ID: ");
        String trainId = sc.next();

        TrainService train = trains.get(trainId);

        if (train == null) {

            System.out.println("Train not found.");
            return;
        }

        System.out.println(
                "1. Emergency");
        System.out.println(
                "2. Connection");
        System.out.println(
                "3. Scheduled");

        System.out.print("Request type: ");
        int type = sc.nextInt();

        PlatformRequest request;

        if (type == 1) {

            request =
                    new EmergencyArrival(
                            train,
                            nextArrivalOrder);

        } else if (type == 2) {

            request =
                    new ConnectionArrival(
                            train,
                            nextArrivalOrder);

        } else {

            request =
                    new ScheduledArrival(
                            train,
                            nextArrivalOrder);
        }

        platformQueue.add(request);

        nextArrivalOrder++;

        System.out.println(
                "Platform request added.");
    }



    static void allocatePlatform() {

        if (platformQueue.isEmpty()) {

            System.out.println("No waiting train requests.");
            return;
        }

        ArrayList<PlatformRequest> skipped =
                new ArrayList<>();

        PlatformRequest selectedRequest = null;
        Platform selectedPlatform = null;


        while (!platformQueue.isEmpty()) {

            PlatformRequest request =
                    platformQueue.poll();

            TrainService train =
                    request.getTrain();

            Platform platform =
                    allocationPolicy.allocatePlatform(
                            train,
                            new ArrayList<>(
                                    platforms.values()),
                            timetable,
                            CLEARANCE_BUFFER);

            if (platform != null) {

                selectedRequest = request;
                selectedPlatform = platform;

                break;

            } else {

                skipped.add(request);
            }
        }


        // Reinsert skipped requests

        for (PlatformRequest request : skipped) {

            platformQueue.add(request);
        }


        if (selectedRequest == null) {

            System.out.println(
                    "No suitable platform available.");

            return;
        }


        TrainService train =
                selectedRequest.getTrain();

        int start =
                train.getArrivalTime();

        int end =
                train.getDepartureTime()
                        + CLEARANCE_BUFFER;


        PlatformOccupation occupation =
                new PlatformOccupation(
                        selectedPlatform.getPlatformId(),
                        train.getTrainId(),
                        start,
                        end);

        timetable.add(occupation);

        timetable.sort(
                Comparator.comparingInt(
                        PlatformOccupation::getStartTime));


        // Add release time to min-heap

        releaseHeap.add(
                new ReleaseRecord(
                        end,
                        selectedPlatform.getPlatformId(),
                        train.getTrainId()));


        System.out.println(
                "Platform "
                        + selectedPlatform.getPlatformId()
                        + " reserved for "
                        + train.getTrainName());
    }



    static void confirmArrival() {

        System.out.print("Train ID: ");
        String trainId = sc.next();

        PlatformOccupation occupation =
                findOccupation(trainId);

        if (occupation == null) {

            System.out.println("No platform reservation.");
            return;
        }

        Platform platform =
                platforms.get(
                        occupation.getPlatformId());

        if (platform.occupyPlatform(trainId)) {

            trains.get(trainId).confirmArrival();

            System.out.println(
                    "Arrival confirmed.");

        } else {

            System.out.println(
                    "Platform already occupied. Train not displaced.");
        }
    }


   

    static void confirmDeparture() {

        System.out.print("Train ID: ");
        String trainId = sc.next();

        PlatformOccupation occupation =
                findOccupation(trainId);

        if (occupation == null) {

            System.out.println("No platform reservation.");
            return;
        }

        Platform platform =
                platforms.get(
                        occupation.getPlatformId());

        trains.get(trainId).confirmDeparture();

        platform.releasePlatform(trainId);

        System.out.println(
                "Departure confirmed.");

        System.out.println(
                "Platform remains reserved until clearance time.");
    }



  static void addDelay() {

    System.out.print("Train ID: ");
    String trainId = sc.next();

    System.out.print("Delay minutes: ");
    int delay = sc.nextInt();

    TrainService train = trains.get(trainId);

    if (train == null) {

        System.out.println("Train not found.");
        return;
    }

    // Delay must be positive
    if (delay <= 0) {

        System.out.println(
                "Delay must be greater than 0 minutes.");
        return;
    }

    PlatformOccupation occupation =
            findOccupation(trainId);

    // Case 1: No platform allocated yet
    // Delay can be directly applied.
    if (occupation == null) {

        train.addDelay(delay);

        System.out.println(
                "Delay updated successfully.");

        System.out.println(
                "New arrival time: "
                        + train.getArrivalTime());

        System.out.println(
                "New departure time: "
                        + train.getDepartureTime());

        return;
    }

    int newStart =
            train.getArrivalTime() + delay;

    int newEnd =
            train.getDepartureTime()
                    + delay
                    + CLEARANCE_BUFFER;

    if (hasConflict(
            occupation.getPlatformId(),
            trainId,
            newStart,
            newEnd)) {

        System.out.println(
                "Delay rejected because of platform overlap.");

        System.out.println(
                "The existing platform plan cannot support this delay.");

        return;
    }

    train.addDelay(delay);

 
    occupation.setStartTime(newStart);
    occupation.setEndTime(newEnd);

    timetable.sort(
            Comparator.comparingInt(
                    PlatformOccupation::getStartTime));

    releaseHeap.add(
            new ReleaseRecord(
                    newEnd,
                    occupation.getPlatformId(),
                    trainId));

    System.out.println(
            "Delay updated successfully.");

    System.out.println(
            "New arrival time: "
                    + train.getArrivalTime());

    System.out.println(
            "New departure time: "
                    + train.getDepartureTime());
}



    static void changePriority() {

        System.out.print("Train ID: ");
        String trainId = sc.next();

        System.out.print("New priority: ");
        int priority = sc.nextInt();

        TrainService train =
                trains.get(trainId);

        if (train == null) {

            System.out.println("Train not found.");
            return;
        }

        train.setDeclaredPriority(priority);



        ArrayList<PlatformRequest> temp =
                new ArrayList<>();

        while (!platformQueue.isEmpty()) {

            temp.add(platformQueue.poll());
        }

        for (PlatformRequest request : temp) {

            platformQueue.add(request);
        }

        System.out.println(
                "Priority updated and queue reordered.");
    }


    static void advanceTime() {

        System.out.print("Advance minutes: ");

        int minutes = sc.nextInt();

        currentTime += minutes;

        releasePlatforms();

        System.out.println(
                "Current time: " + currentTime);
    }

    static void releasePlatforms() {

        while (!releaseHeap.isEmpty()
                && releaseHeap.peek().getReleaseTime()
                <= currentTime) {

            ReleaseRecord record =
                    releaseHeap.poll();

            PlatformOccupation occupation =
                    findOccupation(
                            record.getTrainId());



            if (occupation == null) {
                continue;
            }

            if (occupation.getEndTime()
                    != record.getReleaseTime()) {

                continue;
            }


            Platform platform =
                    platforms.get(
                            record.getPlatformId());


            timetable.remove(occupation);

            platform.releasePlatform(
                    record.getTrainId());

            System.out.println(
                    "Platform "
                            + record.getPlatformId()
                            + " released.");
        }
    }


    static void viewTimetable() {

        System.out.println(
                "\n--- PLATFORM TIMETABLE ---");

        if (timetable.isEmpty()) {

            System.out.println("No reservations.");
            return;
        }

        for (PlatformOccupation occupation :
                timetable) {

            System.out.println(
                    "Platform "
                            + occupation.getPlatformId()
                            + " | Train "
                            + occupation.getTrainId()
                            + " | ["
                            + occupation.getStartTime()
                            + ", "
                            + occupation.getEndTime()
                            + ")");
        }
    }


    static PlatformOccupation findOccupation(
            String trainId) {

        for (PlatformOccupation occupation :
                timetable) {

            if (occupation.getTrainId()
                    .equals(trainId)) {

                return occupation;
            }
        }

        return null;
    }


    static boolean hasConflict(
            int platformId,
            String trainId,
            int newStart,
            int newEnd) {

        for (PlatformOccupation occupation :
                timetable) {

            if (occupation.getPlatformId()
                    == platformId
                    && !occupation.getTrainId()
                    .equals(trainId)) {

                if (occupation.overlaps(
                        newStart, newEnd)) {

                    return true;
                }
            }
        }

        return false;
    }
}