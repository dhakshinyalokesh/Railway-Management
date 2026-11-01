# Railway Management System

This repository began as a Java console simulation. The web demo adds a browser dashboard and an SQLite database while keeping the original console classes in place.

## What the web demo does

- Shows the six sample train services and seat availability.
- Saves passengers and reservations in SQLite, so data remains after the app closes.
- Adds a passenger to the FIFO waiting list when a train has no confirmed seats left.
- Cancels reservations and promotes the oldest waiting passenger when a confirmed seat becomes free.
- Requests and allocates platforms by emergency/connection/scheduled priority, train priority, and request order.
- Confirms arrivals and departures, applies conflict-checked delays, changes train priority, and advances station time to release cleared platforms.

## Run the web demo

You need JDK 21 and Apache Maven. The `run-web.bat` file starts the app from Windows. Maven downloads the SQLite JDBC driver the first time it runs.

```text
run-web.bat
```

Then open `http://localhost:8080` in a browser. Stop the server with Ctrl+C in the terminal. The database is stored at `.data/railway.db`; that local data folder is excluded from Git.

## How the pieces fit together

```text
Browser (HTML, CSS, JavaScript)
          │ HTTP requests / JSON responses
          ▼
RailwayWebServer.java + PlatformWebController.java (Java APIs)
          │ JDBC (Java's database connection interface)
          ▼
SQLite file: .data/railway.db
```

The browser asks `GET /api/trains`, `GET /api/bookings`, and `GET /api/platforms` for data. Booking uses `POST /api/bookings`; cancellation uses `DELETE /api/bookings/{id}`. Platform operations use `POST /api/platform-requests`, `POST /api/platform-allocations`, `POST /api/platform-occupations/{trainId}/arrival`, `POST /api/platform-occupations/{trainId}/departure`, `POST /api/trains/{trainId}/delay`, `POST /api/trains/{trainId}/priority`, and `POST /api/station/time`. `PlatformWebController.java` connects these actions to the same Java domain classes and policies as the console scheduler. The request queue, platform state, timetable, train time/priority changes, and station clock are stored in SQLite.

The database stores the train, reservation, and station-operation records in related tables:

- `trains` stores each train and its seat capacity.
- `passengers` stores each passenger once for each booking.
- `reservations` links a passenger to a train and tracks `CONFIRMED`, `WAITING`, or `CANCELLED` status.
- `platforms` stores platform lengths and current physical occupancy; `platform_requests` stores queued request type and arrival order; `platform_occupations` stores reserved intervals and arrival/departure confirmation; `station_state` stores the simulated clock.

The original console entry point is still `RailwaySystem.java`. The web demo uses the separate `RailwayWebServer.java` HTTP layer and `PlatformWebController.java` station-operation API, while reusing the project's `TrainService`, `PlatformRequest`, and platform-allocation policy classes.

## Assignment report and scenario checks

See docs/REPORT.md for the problem analysis, UML, data-structure justification, scenario results, demo flow, and limitations. The 30 sample booking requests are in docs/sample-booking-requests.csv. Compile and run the dependency-free assignment checks with:

```powershell
javac -d target\check *.java
java -cp target\check AssignmentScenarioChecks
```

