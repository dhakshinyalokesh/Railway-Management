# Railway Reservation and Platform Allocation System

## 1. Problem analysis

Payanam Junction is represented as one station with six train services, three platforms, and one end-to-end General seat class of ten seats per train. The original console model keeps state in memory for one run; the browser demo persists booking and platform-operation state in SQLite. It is a teaching simulation, not a live railway system.

The program addresses two station tasks:

1. **Reservations:** confirm a booking while seats remain. When full, append the passenger to that train's FIFO waiting list. A cancellation frees a seat and promotes the oldest waiting passenger immediately.
2. **Platform scheduling:** order requests by emergency, connection, then scheduled type; break category ties by declared train priority and then request arrival order. Assign the first numbered platform that is long enough and has no overlapping reserved interval. An interval runs from arrival through departure plus a ten-minute clearance buffer. An occupying train is not displaced.

Time values are integer minutes on a single simulated day. The sample values are relative minutes, not clock strings. The scope excludes multiple stations, route planning, payment, live feeds, and authentication. The web server uses SQLite and reuses the Java platform-allocation classes and policy, while its HTTP handlers persist the station clock, queued requests, platform occupations, and train changes.

## 2. Data structures and algorithm choices

| Structure | Used for | Why it fits |
|---|---|---|
| `HashMap` | Train, passenger, reservation, and platform lookup | Finds an item by its ID without scanning every item. |
| `ArrayDeque` | Each train's waiting passengers | `addLast` and `removeFirst` express FIFO order directly. |
| `PriorityQueue<PlatformRequest>` | Waiting train requests | Selects the highest-ranked request; `compareTo` gives deterministic ties. |
| `PriorityQueue<ReleaseRecord>` | Future platform release events | A min-heap exposes the earliest release time first. |
| `ArrayList<PlatformOccupation>` | Platform timetable | Keeps reservations that can be scanned for conflicts and displayed in start-time order. |
| Linear platform scan | Candidate platform selection | Three platforms are small enough to check in order; check length first, then every interval on that platform. |

For intervals `[a,b)` and `[c,d)`, overlap is `a < d && b > c`. The half-open interval means one occupation ending exactly when another begins is not an overlap. Allocation checks the proposed interval `[arrival, departure + 10)`.

## 3. UML class diagram

```mermaid
classDiagram
    class RailwaySystem {
      +main(String[] args)
      +initializeData()
      +bookTicket()
      +cancelBooking()
      +requestPlatform()
      +allocatePlatform()
      +addDelay()
      +viewTimetable()
    }
    class PlatformWebController {
      +handlePlatforms(HttpExchange)
      +handleAllocation(HttpExchange)
      +handleTime(HttpExchange)
      +handleTrainOperation(HttpExchange)
    }
    class TrainService {
      -String trainId
      -int availableSeats
      -ArrayList~Reservation~ confirmedBookings
      -ArrayDeque~Passenger~ waitingQueue
      +bookSeat(Passenger, int) Reservation
      +cancelBooking(int, int) Reservation
      +addDelay(int)
    }
    class Passenger
    class Reservation {
      -String status
      +cancel()
    }
    class Platform {
      -int platformLength
      -boolean occupied
      +occupyPlatform(String) boolean
      +releasePlatform(String)
    }
    class PlatformRequest {
      <<abstract>>
      #long arrivalOrder
      +priorityRank()* int
      +compareTo(PlatformRequest) int
    }
    class EmergencyArrival
    class ConnectionArrival
    class ScheduledArrival
    class PlatformAllocationPolicy {
      <<interface>>
      +allocatePlatform(...) Platform
    }
    class SimplePlatformAllocationPolicy
    class PlatformOccupation {
      -int startTime
      -int endTime
      +overlaps(int, int) boolean
    }
    class ReleaseRecord {
      -int releaseTime
      +compareTo(ReleaseRecord) int
    }

    RailwaySystem --> TrainService : manages
    RailwaySystem --> Platform : manages
    RailwaySystem --> PlatformRequest : priority queue
    RailwaySystem --> PlatformOccupation : timetable
    RailwaySystem --> ReleaseRecord : release heap
    RailwaySystem --> PlatformAllocationPolicy : delegates allocation
    PlatformWebController --> PlatformAllocationPolicy : delegates allocation
    PlatformWebController --> PlatformRequest : prioritizes requests
    PlatformWebController --> ReleaseRecord : release events
    TrainService "1" o-- "0..*" Reservation : confirmed bookings
    TrainService "1" o-- "0..*" Passenger : FIFO waiting queue
    Reservation --> Passenger
    PlatformRequest --> TrainService
    PlatformRequest <|-- EmergencyArrival
    PlatformRequest <|-- ConnectionArrival
    PlatformRequest <|-- ScheduledArrival
    PlatformAllocationPolicy <|.. SimplePlatformAllocationPolicy
    PlatformOccupation --> Platform
```

## 4. Scenario checks and results

The checks were run against the source classes using JDK 21.

| Scenario | Setup | Expected and observed result |
|---|---|---|
| 30 booking requests | See `sample-booking-requests.csv`; five requests per each of six services | Dataset contains 30 distinct sample requests. The console menu records one request at a time; it does not import CSV files. |
| Cancelled seat and FIFO promotion | Fill T101's ten seats, add two waiting passengers, cancel reservation 1 | **PASS.** Eleventh passenger is queued. Cancellation promotes that oldest passenger and the seat count returns to zero available. |
| Overlapping arrivals | All three platforms have reservations covering the proposed train's interval | **PASS.** Allocation returns no platform. |
| Overlong train | Request a 351-unit train when the longest platform is 350 units | **PASS.** Allocation returns no platform. |
| Delay extends platform occupation | Delay a reserved train by 20 minutes | **PASS.** Arrival and departure each move 20 minutes; the timetable occupation and release time move with them, preserving the 10-minute clearance. |
| Java compile | Compile all project Java sources and scenario checks | **PASS.** `javac -d target\\check *.java` completed successfully. |
| Browser JavaScript syntax | Check the dashboard script | Previously checked with `node --check`; this only checks syntax, not browser interactions. |

To rerun the Java scenario checks from the project folder:

```powershell
javac -d target\check *.java
java -cp target\check AssignmentScenarioChecks
```

Expected final line: `All assignment scenario checks passed.` The helper is an educational, dependency-free check, not a JUnit suite. The overlap and overlong cases call the same allocation policy used by the app, with explicit sample inputs. The delay check calls the actual `RailwaySystem.addDelay()` method.

## 5. Demonstration flow

1. Start the web demo with `run-web.bat` and open `http://localhost:8080`.
2. Book a passenger, then show the reservation in the browser and at `/api/bookings`.
3. Under Platform operations, choose a train and request type; queue requests of different types to show priority order, then allocate the next suitable train.
4. Confirm arrival, apply a delay and show that the system rejects it if the new interval overlaps another reservation, then confirm departure.
5. Advance the station clock past the clearance end and show the occupation released. Explain that the scheduled interval includes the 10-minute buffer.
6. If needed, run `java RailwaySystem` to show the original console controls alongside the browser interface.

Screenshots to capture for the final report: console startup with six trains/three platforms; booking and waitlist promotion; timetable before/after a delay; rejected overlap/overlong checks; and the web dashboard with saved bookings. Add your own screenshots after running these steps; none are embedded here because they must show your actual run.

## 6. Limitations and future improvements

- The original console simulation keeps state in memory and resets when it exits; the browser demo persists its station state in a local SQLite file.
- The console menu does not import the 30-row CSV; requests are entered individually.
- The web demo persists passenger/reservation and platform-operation data in SQLite, but it does not provide user accounts or live railway feeds.
- The model uses one station, synthetic data, one seat class, and a simple first-fit platform policy.
- A production system would need stronger validation, concurrency control, audit history, security, and integration with trusted railway data.

Possible future work: add a full audit log, CSV import/export, multiple seat classes and stations, and authentication.

## 7. SDG connection

The project is an educational simulation of transport infrastructure planning (SDG 9) and station resource use for communities (SDG 11). It does not measure real-world sustainability outcomes.
