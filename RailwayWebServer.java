import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web server and API controller for the Railway Management System.
 * Connects the web UI to SQLite and integrates authentication with
 * separate dashboards for Passenger, Staff, and Admin.
 */
public class RailwayWebServer {
    private static final int DEFAULT_PORT = 8080;
    private static final String DB_URL = "jdbc:sqlite:.data/railway.db";

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(".data"));
        initializeDatabase();

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", String.valueOf(DEFAULT_PORT)));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Core train and booking APIs
        server.createContext("/api/trains", RailwayWebServer::handleTrains);
        server.createContext("/api/bookings", RailwayWebServer::handleBookings);
        server.createContext("/api/my-bookings", RailwayWebServer::handleMyBookings);

        // Authentication APIs
        server.createContext("/api/auth/login", AuthManager::handleLogin);
        server.createContext("/api/auth/register", AuthManager::handleRegister);
        server.createContext("/api/auth/logout", AuthManager::handleLogout);
        server.createContext("/api/auth/me", AuthManager::handleMe);

        // Admin-only management APIs
        server.createContext("/api/admin/users", AdminController::handleUsers);
        server.createContext("/api/admin/trains", AdminController::handleTrains);

        // Platform scheduling operations (Staff & Admin only)
        server.createContext("/api/platforms", PlatformWebController::handlePlatforms);
        server.createContext("/api/platform-requests", ex -> requireStaffOrAdmin(PlatformWebController::handleRequests, ex));
        server.createContext("/api/platform-allocations", ex -> requireStaffOrAdmin(PlatformWebController::handleAllocation, ex));
        server.createContext("/api/platform-occupations/", ex -> requireStaffOrAdmin(PlatformWebController::handleOccupationAction, ex));
        server.createContext("/api/station/time", ex -> requireStaffOrAdmin(PlatformWebController::handleTime, ex));

        // Frontend static assets & dashboard pages
        server.createContext("/", RailwayWebServer::serveFrontend);

        server.setExecutor(null);
        server.start();

        System.out.println("Railway Management System is running on port " + port);
        System.out.println("Open http://localhost:" + port + " in your browser to log in.");
    }

    static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(DB_URL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private static void initializeDatabase() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trains (
                        train_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        arrival_time INTEGER NOT NULL,
                        departure_time INTEGER NOT NULL,
                        train_length INTEGER NOT NULL,
                        priority INTEGER NOT NULL,
                        total_seats INTEGER NOT NULL DEFAULT 10
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS passengers (
                        passenger_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reservations (
                        reservation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        passenger_id INTEGER NOT NULL REFERENCES passengers(passenger_id),
                        train_id TEXT NOT NULL REFERENCES trains(train_id),
                        status TEXT NOT NULL CHECK(status IN ('CONFIRMED', 'WAITING', 'CANCELLED')),
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM trains")) {
                if (count.next() && count.getInt(1) == 0) {
                    seedTrains(connection);
                }
            }

            // Ensure exactly the 6 intended trains exist in the application
            statement.executeUpdate("DELETE FROM reservations WHERE train_id NOT IN ('T101', 'T102', 'T103', 'T104', 'T105', 'T106')");
            statement.executeUpdate("DELETE FROM platform_occupations WHERE train_id NOT IN ('T101', 'T102', 'T103', 'T104', 'T105', 'T106')");
            statement.executeUpdate("DELETE FROM platform_requests WHERE train_id NOT IN ('T101', 'T102', 'T103', 'T104', 'T105', 'T106')");
            statement.executeUpdate("DELETE FROM trains WHERE train_id NOT IN ('T101', 'T102', 'T103', 'T104', 'T105', 'T106')");

            PlatformWebController.initialize(connection);
            AuthManager.initialize(connection);
        }
    }

    private static void seedTrains(Connection connection) throws SQLException {
        String sql = "INSERT INTO trains(train_id, name, arrival_time, departure_time, train_length, priority) VALUES(?, ?, ?, ?, ?, ?)";
        Object[][] trains = {
                {"T101", "Kongu Express", 100, 130, 180, 2},
                {"T102", "Salem Express", 140, 170, 220, 3},
                {"T103", "Erode Passenger", 180, 210, 160, 1},
                {"T104", "Chennai Express", 220, 260, 300, 4},
                {"T105", "Coimbatore Special", 280, 310, 200, 2},
                {"T106", "Night Express", 330, 360, 250, 3}
        };
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Object[] train : trains) {
                insert.setString(1, (String) train[0]);
                insert.setString(2, (String) train[1]);
                insert.setInt(3, (Integer) train[2]);
                insert.setInt(4, (Integer) train[3]);
                insert.setInt(5, (Integer) train[4]);
                insert.setInt(6, (Integer) train[5]);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void requireStaffOrAdmin(HttpHandler handler, HttpExchange exchange) throws IOException {
        User user = AuthManager.getAuthenticatedUser(exchange);
        if (user == null) {
            sendJson(exchange, 401, "{\"error\":\"Authentication required\"}");
            return;
        }
        if (user.isPassenger()) {
            sendJson(exchange, 403, "{\"error\":\"Access denied: Staff or Admin role required\"}");
            return;
        }
        handler.handle(exchange);
    }

    private static void handleTrains(HttpExchange exchange) throws IOException {
        if (!"/api/trains".equals(exchange.getRequestURI().getPath())) {
            // Train operational updates (delays, priority changes) require Staff or Admin
            User user = AuthManager.getAuthenticatedUser(exchange);
            if (user == null || user.isPassenger()) {
                sendJson(exchange, 403, "{\"error\":\"Access denied: Staff or Admin role required\"}");
                return;
            }
            PlatformWebController.handleTrainOperation(exchange);
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        String sql = """
                SELECT t.train_id, t.name, t.arrival_time, t.departure_time,
                       t.train_length, t.priority, t.total_seats,
                       SUM(CASE WHEN r.status = 'CONFIRMED' THEN 1 ELSE 0 END) AS booked
                FROM trains t LEFT JOIN reservations r ON r.train_id = t.train_id
                GROUP BY t.train_id ORDER BY t.arrival_time
                """;
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> trains = new ArrayList<>();
            while (rows.next()) {
                trains.add("{" +
                        "\"id\":" + quote(rows.getString("train_id")) + "," +
                        "\"name\":" + quote(rows.getString("name")) + "," +
                        "\"arrival\":" + rows.getInt("arrival_time") + "," +
                        "\"departure\":" + rows.getInt("departure_time") + "," +
                        "\"length\":" + rows.getInt("train_length") + "," +
                        "\"priority\":" + rows.getInt("priority") + "," +
                        "\"totalSeats\":" + rows.getInt("total_seats") + "," +
                        "\"booked\":" + rows.getInt("booked") + "}");
            }
            sendJson(exchange, 200, "[" + String.join(",", trains) + "]");
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void handleBookings(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(exchange.getRequestMethod()) && "/api/bookings".equals(path)) {
            listBookings(exchange);
        } else if ("POST".equals(exchange.getRequestMethod()) && "/api/bookings".equals(path)) {
            createBooking(exchange);
        } else if ("DELETE".equals(exchange.getRequestMethod()) && path.matches("/api/bookings/\\d+")) {
            cancelBooking(exchange, Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)));
        } else {
            sendJson(exchange, 405, "{\"error\":\"Unsupported booking request\"}");
        }
    }

    private static void listBookings(HttpExchange exchange) throws IOException {
        String sql = """
                SELECT r.reservation_id, p.name AS passenger, t.train_id, t.name AS train_name, r.status
                FROM reservations r JOIN passengers p ON p.passenger_id = r.passenger_id
                JOIN trains t ON t.train_id = r.train_id
                WHERE r.status <> 'CANCELLED' ORDER BY r.reservation_id DESC
                """;
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> bookings = new ArrayList<>();
            while (rows.next()) {
                bookings.add("{" +
                        "\"id\":" + rows.getInt("reservation_id") + "," +
                        "\"passenger\":" + quote(rows.getString("passenger")) + "," +
                        "\"trainId\":" + quote(rows.getString("train_id")) + "," +
                        "\"trainName\":" + quote(rows.getString("train_name")) + "," +
                        "\"status\":" + quote(rows.getString("status")) + "}");
            }
            sendJson(exchange, 200, "[" + String.join(",", bookings) + "]");
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void handleMyBookings(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        User currentUser = AuthManager.getAuthenticatedUser(exchange);
        if (currentUser == null) {
            sendJson(exchange, 401, "{\"error\":\"Please login to view your bookings\"}");
            return;
        }

        String sql = """
                SELECT r.reservation_id, p.name AS passenger, t.train_id, t.name AS train_name,
                       t.arrival_time, t.departure_time, r.status, r.created_at
                FROM reservations r
                JOIN passengers p ON p.passenger_id = r.passenger_id
                JOIN trains t ON t.train_id = r.train_id
                WHERE r.status <> 'CANCELLED' AND (r.user_id = ? OR p.name = ?)
                ORDER BY r.reservation_id DESC
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, currentUser.getUserId());
            statement.setString(2, currentUser.getFullName());

            try (ResultSet rows = statement.executeQuery()) {
                List<String> bookings = new ArrayList<>();
                while (rows.next()) {
                    bookings.add("{" +
                            "\"id\":" + rows.getInt("reservation_id") + "," +
                            "\"passenger\":" + quote(rows.getString("passenger")) + "," +
                            "\"trainId\":" + quote(rows.getString("train_id")) + "," +
                            "\"trainName\":" + quote(rows.getString("train_name")) + "," +
                            "\"arrival\":" + rows.getInt("arrival_time") + "," +
                            "\"departure\":" + rows.getInt("departure_time") + "," +
                            "\"status\":" + quote(rows.getString("status")) + "," +
                            "\"createdAt\":" + quote(rows.getString("created_at") == null ? "" : rows.getString("created_at")) + "}");
                }
                sendJson(exchange, 200, "[" + String.join(",", bookings) + "]");
            }
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void createBooking(HttpExchange exchange) throws IOException {
        User currentUser = AuthManager.getAuthenticatedUser(exchange);
        if (currentUser == null) {
            sendJson(exchange, 401, "{\"error\":\"Please log in to book a seat\"}");
            return;
        }

        Map<String, String> form = parseForm(exchange);
        String passengerName = form.getOrDefault("passengerName", "").trim();
        if (passengerName.isBlank()) {
            passengerName = currentUser.getFullName();
        }
        String trainId = form.getOrDefault("trainId", "").trim();
        if (passengerName.isBlank() || passengerName.length() > 80 || trainId.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Enter a passenger name and choose a train\"}");
            return;
        }

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                int capacity;
                try (PreparedStatement findTrain = connection.prepareStatement("SELECT total_seats FROM trains WHERE train_id = ?")) {
                    findTrain.setString(1, trainId);
                    try (ResultSet result = findTrain.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            sendJson(exchange, 404, "{\"error\":\"Train not found\"}");
                            return;
                        }
                        capacity = result.getInt(1);
                    }
                }
                int confirmed;
                try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM reservations WHERE train_id = ? AND status = 'CONFIRMED'")) {
                    count.setString(1, trainId);
                    try (ResultSet result = count.executeQuery()) { result.next(); confirmed = result.getInt(1); }
                }
                long passengerId;
                try (PreparedStatement insertPassenger = connection.prepareStatement("INSERT INTO passengers(name) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
                    insertPassenger.setString(1, passengerName);
                    insertPassenger.executeUpdate();
                    try (ResultSet keys = insertPassenger.getGeneratedKeys()) { keys.next(); passengerId = keys.getLong(1); }
                }
                String status = confirmed < capacity ? "CONFIRMED" : "WAITING";
                int reservationId;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO reservations(passenger_id, train_id, status, user_id) VALUES(?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    insert.setLong(1, passengerId);
                    insert.setString(2, trainId);
                    insert.setString(3, status);
                    insert.setInt(4, currentUser.getUserId());
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) { keys.next(); reservationId = keys.getInt(1); }
                }
                connection.commit();
                sendJson(exchange, 201, "{\"id\":" + reservationId + ",\"status\":" + quote(status) + "}");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void cancelBooking(HttpExchange exchange, int reservationId) throws IOException {
        User currentUser = AuthManager.getAuthenticatedUser(exchange);
        if (currentUser == null) {
            sendJson(exchange, 401, "{\"error\":\"Please login to cancel reservations\"}");
            return;
        }

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String trainId = null;
                String cancelledStatus = null;
                int ownerUserId = 0;
                String ownerPassengerName = null;

                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT r.train_id, r.status, r.user_id, p.name AS passenger_name
                        FROM reservations r
                        JOIN passengers p ON p.passenger_id = r.passenger_id
                        WHERE r.reservation_id = ? AND r.status <> 'CANCELLED'
                        """)) {
                    find.setInt(1, reservationId);
                    try (ResultSet result = find.executeQuery()) {
                        if (result.next()) {
                            trainId = result.getString("train_id");
                            cancelledStatus = result.getString("status");
                            ownerUserId = result.getInt("user_id");
                            ownerPassengerName = result.getString("passenger_name");
                        }
                    }
                }
                if (trainId == null) {
                    connection.rollback();
                    sendJson(exchange, 404, "{\"error\":\"Active reservation not found\"}");
                    return;
                }

                // If passenger, verify this booking belongs to them
                if (currentUser.isPassenger()) {
                    boolean isOwner = (ownerUserId > 0 && ownerUserId == currentUser.getUserId())
                            || (ownerPassengerName != null && ownerPassengerName.equalsIgnoreCase(currentUser.getFullName()));
                    if (!isOwner) {
                        connection.rollback();
                        sendJson(exchange, 403, "{\"error\":\"You are only authorized to cancel your own bookings\"}");
                        return;
                    }
                }

                try (PreparedStatement cancel = connection.prepareStatement("UPDATE reservations SET status = 'CANCELLED' WHERE reservation_id = ?")) {
                    cancel.setInt(1, reservationId);
                    cancel.executeUpdate();
                }
                if ("CONFIRMED".equals(cancelledStatus)) {
                    try (PreparedStatement promote = connection.prepareStatement(
                            "UPDATE reservations SET status = 'CONFIRMED' WHERE reservation_id = (SELECT reservation_id FROM reservations WHERE train_id = ? AND status = 'WAITING' ORDER BY reservation_id LIMIT 1)")) {
                        promote.setString(1, trainId);
                        promote.executeUpdate();
                    }
                }
                connection.commit();
                sendJson(exchange, 200, "{\"message\":\"Booking cancelled successfully\"}");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void serveFrontend(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        String requested = exchange.getRequestURI().getPath();

        // Default root redirection
        if ("/".equals(requested)) {
            User user = AuthManager.getAuthenticatedUser(exchange);
            if (user != null) {
                if (user.isAdmin()) requested = "/admin-dashboard.html";
                else if (user.isStaff()) requested = "/staff-dashboard.html";
                else requested = "/passenger-dashboard.html";
            } else {
                requested = "/login.html";
            }
        }

        if (requested.contains("..") || requested.contains("\\")) {
            sendJson(exchange, 400, "{\"error\":\"Invalid path\"}");
            return;
        }

        String resource = "/web" + requested;
        try (var input = RailwayWebServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendJson(exchange, 404, "{\"error\":\"Page not found\"}");
                return;
            }
            byte[] bytes = input.readAllBytes();
            String contentType = requested.endsWith(".css") ? "text/css; charset=utf-8"
                    : requested.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : requested.endsWith(".svg") ? "image/svg+xml"
                    : requested.endsWith(".png") ? "image/png"
                    : "text/html; charset=utf-8";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        for (String item : body.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2) {
                values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    public static String quote(String value) {
        if (value == null) return "\"\"";
        StringBuilder json = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> { if (ch < 0x20) json.append(String.format("\\u%04x", (int) ch)); else json.append(ch); }
            }
        }
        return json.append('"').toString();
    }

    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public static void sendError(HttpExchange exchange, SQLException exception) throws IOException {
        System.err.println("Database error: " + exception.getMessage());
        sendJson(exchange, 500, "{\"error\":\"Database request failed: " + exception.getMessage().replace("\"", "'") + "\"}");
    }
}
