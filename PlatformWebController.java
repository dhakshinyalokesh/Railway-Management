import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** HTTP actions for the same platform-scheduling operations available in the console menu. */
public final class PlatformWebController {
    private static final int CLEARANCE_BUFFER = 10;
    private static final int[] PLATFORM_LENGTHS = {200, 280, 350};

    private PlatformWebController() {}

    static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS station_state ("
                    + "singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1), current_time INTEGER NOT NULL)");
            statement.executeUpdate("INSERT OR IGNORE INTO station_state(singleton_id, current_time) VALUES(1, 0)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS platforms ("
                    + "platform_id INTEGER PRIMARY KEY, platform_length INTEGER NOT NULL, "
                    + "occupied INTEGER NOT NULL DEFAULT 0, current_train_id TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS platform_requests ("
                    + "request_id INTEGER PRIMARY KEY AUTOINCREMENT, train_id TEXT NOT NULL REFERENCES trains(train_id), "
                    + "request_type TEXT NOT NULL CHECK(request_type IN ('EMERGENCY','CONNECTION','SCHEDULED')), "
                    + "arrival_order INTEGER NOT NULL UNIQUE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS platform_occupations ("
                    + "train_id TEXT PRIMARY KEY REFERENCES trains(train_id), "
                    + "platform_id INTEGER NOT NULL REFERENCES platforms(platform_id), "
                    + "start_time INTEGER NOT NULL, end_time INTEGER NOT NULL, "
                    + "arrival_confirmed INTEGER NOT NULL DEFAULT 0, departure_confirmed INTEGER NOT NULL DEFAULT 0)");
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM platforms")) {
                if (rows.next() && rows.getInt(1) == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO platforms(platform_id, platform_length) VALUES(?,?)")) {
                        for (int i = 0; i < PLATFORM_LENGTHS.length; i++) {
                            insert.setInt(1, i + 1);
                            insert.setInt(2, PLATFORM_LENGTHS[i]);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
            }
        }
    }

    static void handlePlatforms(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed");
            return;
        }
        try (Connection connection = RailwayWebServer.connect()) {
            int currentTime = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT current_time FROM station_state WHERE singleton_id=1")) {
                if (rows.next()) currentTime = rows.getInt(1);
            }

            List<String> platforms = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT p.platform_id,p.platform_length,p.occupied,p.current_train_id,"
                         + "COUNT(o.train_id) AS scheduled FROM platforms p LEFT JOIN platform_occupations o "
                         + "ON o.platform_id=p.platform_id GROUP BY p.platform_id ORDER BY p.platform_id")) {
                while (rows.next()) {
                    String currentTrain = rows.getString("current_train_id");
                    platforms.add("{\"id\":\"P" + rows.getInt("platform_id") + "\",\"length\":"
                            + rows.getInt("platform_length") + ",\"occupied\":" + (rows.getInt("occupied") == 1)
                            + ",\"currentTrainId\":" + (currentTrain == null ? "null" : RailwayWebServer.quote(currentTrain))
                            + ",\"scheduled\":" + rows.getInt("scheduled") + "}");
                }
            }

            List<String> queue = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT q.request_id,q.request_type,q.arrival_order,t.train_id,t.name,"
                         + "t.arrival_time,t.departure_time,t.train_length,t.priority FROM platform_requests q "
                         + "JOIN trains t ON t.train_id=q.train_id")) {
                PriorityQueue<QueuedRequest> pending = new PriorityQueue<>(Comparator
                        .comparing(QueuedRequest::request)
                        .thenComparingLong(QueuedRequest::arrivalOrder));
                while (rows.next()) {
                    TrainService train = trainFrom(rows);
                    long order = rows.getLong("arrival_order");
                    pending.add(new QueuedRequest(rows.getInt("request_id"), order,
                            request(rows.getString("request_type"), train, order)));
                }
                while (!pending.isEmpty()) {
                    QueuedRequest item = pending.remove();
                    TrainService train = item.request().getTrain();
                    queue.add("{\"requestId\":" + item.requestId() + ",\"trainId\":"
                            + RailwayWebServer.quote(train.getTrainId()) + ",\"trainName\":"
                            + RailwayWebServer.quote(train.getTrainName()) + ",\"requestType\":"
                            + RailwayWebServer.quote(requestType(item.request())) + ",\"priority\":"
                            + train.getDeclaredPriority() + ",\"arrival\":" + train.getArrivalTime()
                            + ",\"arrivalOrder\":" + item.arrivalOrder() + "}");
                }
            }

            List<String> timetable = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT o.train_id,t.name AS train_name,t.train_length,t.priority,"
                         + "o.platform_id,o.start_time,o.end_time,o.arrival_confirmed,o.departure_confirmed "
                         + "FROM platform_occupations o JOIN trains t ON t.train_id=o.train_id ORDER BY o.start_time")) {
                while (rows.next()) {
                    String status = rows.getInt("departure_confirmed") == 1 ? "DEPARTED_CLEARANCE"
                            : rows.getInt("arrival_confirmed") == 1 ? "ARRIVED" : "RESERVED";
                    timetable.add("{\"trainId\":" + RailwayWebServer.quote(rows.getString("train_id"))
                            + ",\"trainName\":" + RailwayWebServer.quote(rows.getString("train_name"))
                            + ",\"trainLength\":" + rows.getInt("train_length")
                            + ",\"priority\":" + rows.getInt("priority")
                            + ",\"platformId\":\"P" + rows.getInt("platform_id") + "\",\"start\":"
                            + rows.getInt("start_time") + ",\"end\":" + rows.getInt("end_time")
                            + ",\"arrivalConfirmed\":" + (rows.getInt("arrival_confirmed") == 1)
                            + ",\"departureConfirmed\":" + (rows.getInt("departure_confirmed") == 1)
                            + ",\"status\":" + RailwayWebServer.quote(status) + "}");
                }
            }
            RailwayWebServer.sendJson(exchange, 200, "{\"currentTime\":" + currentTime + ",\"clearanceBuffer\":"
                    + CLEARANCE_BUFFER + ",\"platforms\":[" + String.join(",", platforms)
                    + "],\"queue\":[" + String.join(",", queue) + "],\"timetable\":["
                    + String.join(",", timetable) + "]}");
        } catch (SQLException exception) {
            RailwayWebServer.sendError(exchange, exception);
        }
    }

    static void handleRequests(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Use POST to request a platform");
            return;
        }
        Map<String, String> form = RailwayWebServer.parseForm(exchange);
        String trainId = form.getOrDefault("trainId", "").trim();
        String type = form.getOrDefault("requestType", "SCHEDULED").trim().toUpperCase();
        if (!List.of("EMERGENCY", "CONNECTION", "SCHEDULED").contains(type)) {
            respond(exchange, 400, "Request type must be Emergency, Connection, or Scheduled");
            return;
        }
        try (Connection connection = RailwayWebServer.connect()) {
            connection.setAutoCommit(false);
            try {
                TrainService train = findTrain(connection, trainId);
                if (train == null) {
                    connection.rollback();
                    respond(exchange, 404, "Train not found");
                    return;
                }
                if (hasActivePlatformRequest(connection, trainId)) {
                    connection.rollback();
                    respond(exchange, 409, "This train already has a queued or allocated platform request");
                    return;
                }
                long order;
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX(arrival_order),0)+1 FROM platform_requests")) {
                    rows.next();
                    order = rows.getLong(1);
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO platform_requests(train_id,request_type,arrival_order) VALUES(?,?,?)")) {
                    insert.setString(1, trainId);
                    insert.setString(2, type);
                    insert.setLong(3, order);
                    insert.executeUpdate();
                }
                connection.commit();
                respond(exchange, 201, "Platform request queued for " + trainId);
            } catch (SQLException | IOException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            RailwayWebServer.sendError(exchange, exception);
        }
    }

    static void handleAllocation(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Use POST to allocate the next platform request");
            return;
        }
        try (Connection connection = RailwayWebServer.connect()) {
            connection.setAutoCommit(false);
            try {
                ArrayList<Platform> platforms = loadPlatforms(connection);
                ArrayList<PlatformOccupation> timetable = loadOccupations(connection);
                PriorityQueue<QueuedRequest> pending = loadRequests(connection);
                while (!pending.isEmpty()) {
                    QueuedRequest item = pending.remove();
                    TrainService train = item.request().getTrain();
                    Platform platform = new SimplePlatformAllocationPolicy().allocatePlatform(
                            train, platforms, timetable, CLEARANCE_BUFFER);
                    if (platform == null) continue;
                    int end = train.getDepartureTime() + CLEARANCE_BUFFER;
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO platform_occupations(train_id,platform_id,start_time,end_time) VALUES(?,?,?,?)")) {
                        insert.setString(1, train.getTrainId());
                        insert.setInt(2, platform.getPlatformId());
                        insert.setInt(3, train.getArrivalTime());
                        insert.setInt(4, end);
                        insert.executeUpdate();
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM platform_requests WHERE request_id=?")) {
                        delete.setInt(1, item.requestId());
                        delete.executeUpdate();
                    }
                    connection.commit();
                    respond(exchange, 200, "Platform P" + platform.getPlatformId() + " reserved for "
                            + train.getTrainName() + " through clearance at " + formatMinutes(end));
                    return;
                }
                connection.rollback();
                respond(exchange, 409, pending.isEmpty()
                        ? "No queued request currently fits a free platform" : "No suitable platform is available");
            } catch (SQLException | IOException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            RailwayWebServer.sendError(exchange, exception);
        }
    }

    static void handleOccupationAction(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length != 5 || !"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Unsupported platform action");
            return;
        }
        String trainId = parts[3];
        String action = parts[4];
        if (!List.of("arrival", "departure").contains(action)) {
            respond(exchange, 404, "Unknown platform action");
            return;
        }
        try (Connection connection = RailwayWebServer.connect()) {
            connection.setAutoCommit(false);
            try {
                int platformId;
                int arrivalConfirmed;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT platform_id,arrival_confirmed FROM platform_occupations WHERE train_id=?")) {
                    find.setString(1, trainId);
                    try (ResultSet rows = find.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            respond(exchange, 404, "No platform reservation exists for this train");
                            return;
                        }
                        platformId = rows.getInt("platform_id");
                        arrivalConfirmed = rows.getInt("arrival_confirmed");
                    }
                }
                if ("arrival".equals(action)) {
                    try (PreparedStatement occupy = connection.prepareStatement(
                            "UPDATE platforms SET occupied=1,current_train_id=? WHERE platform_id=? AND occupied=0")) {
                        occupy.setString(1, trainId);
                        occupy.setInt(2, platformId);
                        if (occupy.executeUpdate() == 0) {
                            connection.rollback();
                            respond(exchange, 409, "Platform is already occupied; this train was not displaced");
                            return;
                        }
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE platform_occupations SET arrival_confirmed=1 WHERE train_id=?")) {
                        update.setString(1, trainId);
                        update.executeUpdate();
                    }
                    connection.commit();
                    respond(exchange, 200, "Arrival confirmed at platform P" + platformId);
                    return;
                }
                if (arrivalConfirmed == 0) {
                    connection.rollback();
                    respond(exchange, 409, "Confirm the train arrival before its departure");
                    return;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE platform_occupations SET departure_confirmed=1 WHERE train_id=?")) {
                    update.setString(1, trainId);
                    update.executeUpdate();
                }
                try (PreparedStatement release = connection.prepareStatement(
                        "UPDATE platforms SET occupied=0,current_train_id=NULL WHERE platform_id=? AND current_train_id=?")) {
                    release.setInt(1, platformId);
                    release.setString(2, trainId);
                    release.executeUpdate();
                }
                connection.commit();
                respond(exchange, 200, "Departure confirmed; platform remains reserved through its clearance time");
            } catch (SQLException | IOException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            RailwayWebServer.sendError(exchange, exception);
        }
    }

    static void handleTrainOperation(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length != 5 || !"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Unsupported train operation");
            return;
        }
        String trainId = parts[3];
        String action = parts[4];
        Map<String, String> form = RailwayWebServer.parseForm(exchange);
        try (Connection connection = RailwayWebServer.connect()) {
            connection.setAutoCommit(false);
            try {
                TrainService train = findTrain(connection, trainId);
                if (train == null) {
                    connection.rollback();
                    respond(exchange, 404, "Train not found");
                    return;
                }
                if ("priority".equals(action)) {
                    int priority = parseInt(form.get("priority"), -1);
                    if (priority < 1 || priority > 5) {
                        connection.rollback();
                        respond(exchange, 400, "Priority must be between 1 and 5");
                        return;
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE trains SET priority=? WHERE train_id=?")) {
                        update.setInt(1, priority);
                        update.setString(2, trainId);
                        update.executeUpdate();
                    }
                    connection.commit();
                    respond(exchange, 200, "Declared priority updated; pending queue order will use the new value");
                    return;
                }
                if (!"delay".equals(action)) {
                    connection.rollback();
                    respond(exchange, 404, "Unknown train operation");
                    return;
                }
                int delay = parseInt(form.get("minutes"), 0);
                if (delay <= 0) {
                    connection.rollback();
                    respond(exchange, 400, "Delay must be a positive number of minutes");
                    return;
                }
                int newStart = train.getArrivalTime() + delay;
                int newDeparture = train.getDepartureTime() + delay;
                Integer platformId = null;
                int arrivalConfirmed = 0;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT platform_id,arrival_confirmed,departure_confirmed FROM platform_occupations WHERE train_id=?")) {
                    find.setString(1, trainId);
                    try (ResultSet rows = find.executeQuery()) {
                        if (rows.next()) {
                            platformId = rows.getInt("platform_id");
                            arrivalConfirmed = rows.getInt("arrival_confirmed");
                            if (arrivalConfirmed == 1 || rows.getInt("departure_confirmed") == 1) {
                                connection.rollback();
                                respond(exchange, 409, "A delay cannot be applied after arrival or departure confirmation");
                                return;
                            }
                        }
                    }
                }
                int newEnd = newDeparture + CLEARANCE_BUFFER;
                if (platformId != null) {
                    try (PreparedStatement conflict = connection.prepareStatement(
                            "SELECT train_id FROM platform_occupations WHERE platform_id=? AND train_id<>? "
                                    + "AND start_time < ? AND end_time > ? LIMIT 1")) {
                        conflict.setInt(1, platformId);
                        conflict.setString(2, trainId);
                        conflict.setInt(3, newEnd);
                        conflict.setInt(4, newStart);
                        try (ResultSet rows = conflict.executeQuery()) {
                            if (rows.next()) {
                                connection.rollback();
                                respond(exchange, 409, "Delay rejected: it would overlap another train on the reserved platform");
                                return;
                            }
                        }
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE platform_occupations SET start_time=?,end_time=? WHERE train_id=?")) {
                        update.setInt(1, newStart);
                        update.setInt(2, newEnd);
                        update.setString(3, trainId);
                        update.executeUpdate();
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE trains SET arrival_time=arrival_time+?,departure_time=departure_time+? WHERE train_id=?")) {
                    update.setInt(1, delay);
                    update.setInt(2, delay);
                    update.setString(3, trainId);
                    update.executeUpdate();
                }
                connection.commit();
                respond(exchange, 200, "Delay applied; new arrival " + formatMinutes(newStart)
                        + ", departure " + formatMinutes(newDeparture));
            } catch (SQLException | IOException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            RailwayWebServer.sendError(exchange, exception);
        }
    }

    static void handleTime(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Use POST to advance station time");
            return;
        }
        int minutes = parseInt(RailwayWebServer.parseForm(exchange).get("minutes"), 0);
        if (minutes <= 0) {
            respond(exchange, 400, "Advance time by a positive number of minutes");
            return;
        }
        try (Connection connection = RailwayWebServer.connect()) {
            connection.setAutoCommit(false);
            try {
                int currentTime;
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT current_time FROM station_state WHERE singleton_id=1")) {
                    rows.next();
                    currentTime = rows.getInt(1) + minutes;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE station_state SET current_time=? WHERE singleton_id=1")) {
                    update.setInt(1, currentTime);
                    update.executeUpdate();
                }
                releaseDuePlatforms(connection, currentTime);
                connection.commit();
                respond(exchange, 200, "Station time is now " + formatMinutes(currentTime));
            } catch (SQLException | IOException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            RailwayWebServer.sendError(exchange, exception);
        }
    }

    private static void releaseDuePlatforms(Connection connection, int currentTime) throws SQLException {
        PriorityQueue<ReleaseRecord> releaseHeap = new PriorityQueue<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT platform_id,train_id,end_time FROM platform_occupations")) {
            while (rows.next()) releaseHeap.add(new ReleaseRecord(rows.getInt("end_time"),
                    rows.getInt("platform_id"), rows.getString("train_id")));
        }
        while (!releaseHeap.isEmpty() && releaseHeap.peek().getReleaseTime() <= currentTime) {
            ReleaseRecord record = releaseHeap.remove();
            try (PreparedStatement release = connection.prepareStatement(
                    "UPDATE platforms SET occupied=0,current_train_id=NULL WHERE platform_id=? AND current_train_id=?")) {
                release.setInt(1, record.getPlatformId());
                release.setString(2, record.getTrainId());
                release.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM platform_occupations WHERE train_id=? AND end_time=?")) {
                delete.setString(1, record.getTrainId());
                delete.setInt(2, record.getReleaseTime());
                delete.executeUpdate();
            }
        }
    }

    private static ArrayList<Platform> loadPlatforms(Connection connection) throws SQLException {
        ArrayList<Platform> platforms = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM platforms ORDER BY platform_id")) {
            while (rows.next()) {
                Platform platform = new Platform(rows.getInt("platform_id"), rows.getInt("platform_length"));
                String currentTrainId = rows.getString("current_train_id");
                if (rows.getInt("occupied") == 1 && currentTrainId != null) platform.occupyPlatform(currentTrainId);
                platforms.add(platform);
            }
        }
        return platforms;
    }

    private static ArrayList<PlatformOccupation> loadOccupations(Connection connection) throws SQLException {
        ArrayList<PlatformOccupation> timetable = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT platform_id,train_id,start_time,end_time "
                     + "FROM platform_occupations ORDER BY start_time")) {
            while (rows.next()) timetable.add(new PlatformOccupation(rows.getInt("platform_id"),
                    rows.getString("train_id"), rows.getInt("start_time"), rows.getInt("end_time")));
        }
        return timetable;
    }

    private static PriorityQueue<QueuedRequest> loadRequests(Connection connection) throws SQLException {
        PriorityQueue<QueuedRequest> pending = new PriorityQueue<>(Comparator
                .comparing(QueuedRequest::request).thenComparingLong(QueuedRequest::arrivalOrder));
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT q.request_id,q.request_type,q.arrival_order,t.train_id,t.name,"
                     + "t.arrival_time,t.departure_time,t.train_length,t.priority FROM platform_requests q "
                     + "JOIN trains t ON t.train_id=q.train_id")) {
            while (rows.next()) {
                long order = rows.getLong("arrival_order");
                TrainService train = trainFrom(rows);
                pending.add(new QueuedRequest(rows.getInt("request_id"), order,
                        request(rows.getString("request_type"), train, order)));
            }
        }
        return pending;
    }

    private static TrainService trainFrom(ResultSet rows) throws SQLException {
        return new TrainService(rows.getString("train_id"), rows.getString("name"),
                rows.getInt("arrival_time"), rows.getInt("departure_time"),
                rows.getInt("train_length"), rows.getInt("priority"));
    }

    private static TrainService findTrain(Connection connection, String trainId) throws SQLException {
        if (trainId.isBlank()) return null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT train_id,name,arrival_time,departure_time,train_length,priority FROM trains WHERE train_id=?")) {
            statement.setString(1, trainId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? trainFrom(rows) : null; }
        }
    }

    private static boolean hasActivePlatformRequest(Connection connection, String trainId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM platform_requests WHERE train_id=? UNION ALL "
                        + "SELECT 1 FROM platform_occupations WHERE train_id=? LIMIT 1")) {
            statement.setString(1, trainId);
            statement.setString(2, trainId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static PlatformRequest request(String type, TrainService train, long order) {
        return switch (type) {
            case "EMERGENCY" -> new EmergencyArrival(train, order);
            case "CONNECTION" -> new ConnectionArrival(train, order);
            default -> new ScheduledArrival(train, order);
        };
    }

    private static String requestType(PlatformRequest request) {
        if (request instanceof EmergencyArrival) return "EMERGENCY";
        if (request instanceof ConnectionArrival) return "CONNECTION";
        return "SCHEDULED";
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException exception) { return fallback; }
    }

    private static String formatMinutes(int value) {
        int hour = Math.floorDiv(value, 60) % 24;
        int minute = Math.floorMod(value, 60);
        return String.format("%02d:%02d", hour, minute);
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        RailwayWebServer.sendJson(exchange, status, "{\"message\":" + RailwayWebServer.quote(message) + "}");
    }

    private record QueuedRequest(int requestId, long arrivalOrder, PlatformRequest request) {}
}
