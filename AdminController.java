import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controller for Admin-only operations:
 * - User management (list, create, delete)
 * - Train management (add new train services)
 */
public final class AdminController {

    private AdminController() {}

    /**
     * Handles /api/admin/users
     * GET: list all registered users
     * POST: create a new user (Staff, Passenger, or Admin)
     * DELETE: delete a user by id (/api/admin/users/{id})
     */
    public static void handleUsers(HttpExchange exchange) throws IOException {
        User adminUser = AuthManager.getAuthenticatedUser(exchange);
        if (adminUser == null) {
            RailwayWebServer.sendJson(exchange, 401, "{\"error\":\"Authentication required\"}");
            return;
        }
        if (!adminUser.isAdmin()) {
            RailwayWebServer.sendJson(exchange, 403, "{\"error\":\"Access denied: Administrator role required\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("GET".equals(method) && "/api/admin/users".equals(path)) {
            listUsers(exchange);
        } else if ("POST".equals(method) && "/api/admin/users".equals(path)) {
            createUser(exchange);
        } else if ("DELETE".equals(method) && path.matches("/api/admin/users/\\d+")) {
            int targetUserId = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            deleteUser(exchange, adminUser, targetUserId);
        } else {
            RailwayWebServer.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    private static void listUsers(HttpExchange exchange) throws IOException {
        String sql = "SELECT user_id, username, email, full_name, role, created_at FROM users ORDER BY user_id ASC";
        try (Connection connection = RailwayWebServer.connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            List<String> userList = new ArrayList<>();
            while (rs.next()) {
                User user = new User(
                        rs.getInt("user_id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("role"),
                        rs.getString("created_at")
                );
                userList.add(user.toJson());
            }

            RailwayWebServer.sendJson(exchange, 200, "[" + String.join(",", userList) + "]");
        } catch (SQLException e) {
            RailwayWebServer.sendError(exchange, e);
        }
    }

    private static void createUser(HttpExchange exchange) throws IOException {
        Map<String, String> form = RailwayWebServer.parseForm(exchange);
        String username = form.getOrDefault("username", "").trim().toLowerCase();
        String email = form.getOrDefault("email", "").trim().toLowerCase();
        String fullName = form.getOrDefault("fullName", "").trim();
        String password = form.getOrDefault("password", "").trim();
        String role = form.getOrDefault("role", "PASSENGER").trim().toUpperCase();

        if (username.isBlank() || email.isBlank() || fullName.isBlank() || password.isBlank()) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"All fields are required\"}");
            return;
        }

        if (!List.of("PASSENGER", "STAFF", "ADMIN").contains(role)) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"Invalid role specified\"}");
            return;
        }

        try (Connection connection = RailwayWebServer.connect()) {
            // Check uniqueness
            try (PreparedStatement check = connection.prepareStatement("SELECT username, email FROM users WHERE username = ? OR email = ?")) {
                check.setString(1, username);
                check.setString(2, email);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        RailwayWebServer.sendJson(exchange, 409, "{\"error\":\"Username or email already exists\"}");
                        return;
                    }
                }
            }

            int newId = AuthManager.createUser(connection, username, email, password, fullName, role);
            RailwayWebServer.sendJson(exchange, 201, "{\"message\":\"User created successfully\",\"userId\":" + newId + "}");
        } catch (SQLException e) {
            RailwayWebServer.sendError(exchange, e);
        }
    }

    private static void deleteUser(HttpExchange exchange, User adminUser, int targetUserId) throws IOException {
        if (adminUser.getUserId() == targetUserId) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"You cannot delete your own account\"}");
            return;
        }

        try (Connection connection = RailwayWebServer.connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE user_id = ?")) {
            statement.setInt(1, targetUserId);
            int affected = statement.executeUpdate();
            if (affected > 0) {
                RailwayWebServer.sendJson(exchange, 200, "{\"message\":\"User deleted successfully\"}");
            } else {
                RailwayWebServer.sendJson(exchange, 404, "{\"error\":\"User not found\"}");
            }
        } catch (SQLException e) {
            RailwayWebServer.sendError(exchange, e);
        }
    }

    /**
     * Handles /api/admin/trains
     * POST: Add a new train service
     */
    public static void handleTrains(HttpExchange exchange) throws IOException {
        User adminUser = AuthManager.getAuthenticatedUser(exchange);
        if (adminUser == null) {
            RailwayWebServer.sendJson(exchange, 401, "{\"error\":\"Authentication required\"}");
            return;
        }
        if (!adminUser.isAdmin()) {
            RailwayWebServer.sendJson(exchange, 403, "{\"error\":\"Access denied: Administrator role required\"}");
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            RailwayWebServer.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        Map<String, String> form = RailwayWebServer.parseForm(exchange);
        String trainId = form.getOrDefault("trainId", "").trim().toUpperCase();
        String name = form.getOrDefault("name", "").trim();
        String arrivalStr = form.getOrDefault("arrival", "").trim();
        String departureStr = form.getOrDefault("departure", "").trim();
        String lengthStr = form.getOrDefault("length", "").trim();
        String priorityStr = form.getOrDefault("priority", "1").trim();
        String seatsStr = form.getOrDefault("totalSeats", "10").trim();

        if (trainId.isBlank() || name.isBlank() || arrivalStr.isBlank() || departureStr.isBlank() || lengthStr.isBlank()) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"Please fill in all train fields\"}");
            return;
        }

        int arrival, departure, length, priority, seats;
        try {
            arrival = Integer.parseInt(arrivalStr);
            departure = Integer.parseInt(departureStr);
            length = Integer.parseInt(lengthStr);
            priority = Integer.parseInt(priorityStr);
            seats = Integer.parseInt(seatsStr);
        } catch (NumberFormatException e) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"Arrival, departure, length, priority, and seats must be numbers\"}");
            return;
        }

        if (arrival >= departure) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"Departure time must be after arrival time\"}");
            return;
        }

        String sql = "INSERT INTO trains(train_id, name, arrival_time, departure_time, train_length, priority, total_seats) VALUES(?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = RailwayWebServer.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, trainId);
            statement.setString(2, name);
            statement.setInt(3, arrival);
            statement.setInt(4, departure);
            statement.setInt(5, length);
            statement.setInt(6, priority);
            statement.setInt(7, seats);
            statement.executeUpdate();

            RailwayWebServer.sendJson(exchange, 201, "{\"message\":\"Train service added successfully\",\"trainId\":"
                    + RailwayWebServer.quote(trainId) + "}");
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE constraint failed")) {
                RailwayWebServer.sendJson(exchange, 409, "{\"error\":\"Train ID already exists\"}");
            } else {
                RailwayWebServer.sendError(exchange, e);
            }
        }
    }
}
