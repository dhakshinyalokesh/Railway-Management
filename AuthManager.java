import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Manages user accounts, salted SHA-256 password hashing,
 * and session authentication using SQLite.
 * Uses only standard Java SE libraries - simple and beginner friendly.
 */
public final class AuthManager {
    private static final long SESSION_DURATION_MS = 24L * 60L * 60L * 1000L; // 24 hours
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AuthManager() {}

    /**
     * Initializes the users and sessions tables in SQLite,
     * alters reservations to link user_id, and seeds default accounts.
     */
    public static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // 1. Create users table
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        user_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT UNIQUE NOT NULL,
                        email TEXT UNIQUE NOT NULL,
                        password_hash TEXT NOT NULL,
                        salt TEXT NOT NULL,
                        role TEXT NOT NULL CHECK(role IN ('PASSENGER', 'STAFF', 'ADMIN')),
                        full_name TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            // 2. Create sessions table for managing active logins
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        session_token TEXT PRIMARY KEY,
                        user_id INTEGER NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);

            // 3. Add user_id column to reservations table if not already present
            try {
                statement.executeUpdate("ALTER TABLE reservations ADD COLUMN user_id INTEGER REFERENCES users(user_id)");
            } catch (SQLException ignored) {
                // Column already exists
            }

            // 4. Seed default accounts if users table is empty
            try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM users")) {
                if (count.next() && count.getInt(1) == 0) {
                    seedDefaultUsers(connection);
                }
            }
        }
    }

    /**
     * Creates default demo accounts so you can log in immediately.
     */
    private static void seedDefaultUsers(Connection connection) throws SQLException {
        createUser(connection, "admin", "admin@railway.com", "admin123", "System Administrator", "ADMIN");
        createUser(connection, "staff", "staff@railway.com", "staff123", "Station Master", "STAFF");
        createUser(connection, "passenger", "passenger@railway.com", "pass123", "Sample Passenger", "PASSENGER");
        System.out.println("Default user accounts seeded: admin, staff, passenger");
    }

    /**
     * Creates a new user in the database with salted password hash.
     */
    public static int createUser(Connection connection, String username, String email, String password,
                                 String fullName, String role) throws SQLException {
        String salt = generateSalt();
        String hash = hashPassword(password, salt);

        String sql = "INSERT INTO users(username, email, password_hash, salt, role, full_name) VALUES(?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username.trim().toLowerCase());
            statement.setString(2, email.trim().toLowerCase());
            statement.setString(3, hash);
            statement.setString(4, salt);
            statement.setString(5, role.trim().toUpperCase());
            statement.setString(6, fullName.trim());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        return -1;
    }

    /**
     * Generates a 16-byte cryptographically secure random salt in hex string format.
     */
    public static String generateSalt() {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    /**
     * Hashes password with salt using standard Java SHA-256.
     */
    public static String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Authenticates the user with username/email and password.
     * Returns a new session token if successful, or null if invalid.
     */
    public static String[] authenticateUser(String identifier, String password) {
        if (identifier == null || identifier.isBlank() || password == null || password.isBlank()) {
            return null;
        }

        String search = identifier.trim().toLowerCase();
        String sql = "SELECT user_id, username, email, full_name, role, password_hash, salt FROM users WHERE username = ? OR email = ?";

        int userId = -1;
        String username = null;
        String email = null;
        String fullName = null;
        String role = null;

        try (Connection connection = RailwayWebServer.connect()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, search);
                statement.setString(2, search);

                try (ResultSet row = statement.executeQuery()) {
                    if (row.next()) {
                        String storedHash = row.getString("password_hash");
                        String storedSalt = row.getString("salt");
                        String computedHash = hashPassword(password, storedSalt);

                        if (storedHash.equals(computedHash)) {
                            userId = row.getInt("user_id");
                            username = row.getString("username");
                            email = row.getString("email");
                            fullName = row.getString("full_name");
                            role = row.getString("role");
                        }
                    }
                }
            }

            if (userId == -1) {
                return null; // Invalid credentials or not found
            }

            // Generate new session token after query ResultSet is closed
            String token = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            long expires = now + SESSION_DURATION_MS;

            try (PreparedStatement insertSession = connection.prepareStatement(
                    "INSERT INTO sessions(session_token, user_id, created_at, expires_at) VALUES(?, ?, ?, ?)")) {
                insertSession.setString(1, token);
                insertSession.setInt(2, userId);
                insertSession.setLong(3, now);
                insertSession.setLong(4, expires);
                insertSession.executeUpdate();
            }

            // Return token and user json
            User user = new User(userId, username, email, fullName, role, null);
            return new String[]{token, user.toJson()};
        } catch (SQLException e) {
            System.err.println("Authentication error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the authenticated User from the incoming HTTP request.
     * Checks session token from Authorization header or Cookie.
     */
    public static User getAuthenticatedUser(HttpExchange exchange) {
        String token = extractToken(exchange);
        if (token == null || token.isBlank()) {
            return null;
        }

        String sql = """
                SELECT u.user_id, u.username, u.email, u.full_name, u.role, u.created_at
                FROM sessions s
                JOIN users u ON u.user_id = s.user_id
                WHERE s.session_token = ? AND s.expires_at > ?
                """;

        try (Connection connection = RailwayWebServer.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, token);
            statement.setLong(2, System.currentTimeMillis());

            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    return new User(
                            row.getInt("user_id"),
                            row.getString("username"),
                            row.getString("email"),
                            row.getString("full_name"),
                            row.getString("role"),
                            row.getString("created_at")
                    );
                }
            }
        } catch (SQLException e) {
            System.err.println("Session check error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Extracts session token from HTTP headers (Authorization, X-Session-Token, or Cookie).
     */
    public static String extractToken(HttpExchange exchange) {
        // 1. Check Authorization header: Bearer <token>
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }

        // 2. Check X-Session-Token header
        String customHeader = exchange.getRequestHeaders().getFirst("X-Session-Token");
        if (customHeader != null && !customHeader.isBlank()) {
            return customHeader.trim();
        }

        // 3. Check Cookie header: session_token=<token>
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null) {
            for (String cookie : cookieHeader.split(";")) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && "session_token".equals(pair[0])) {
                    return pair[1].trim();
                }
            }
        }

        return null;
    }

    /**
     * Invalidate/remove session from database.
     */
    public static void invalidateSession(String token) {
        if (token == null) return;
        try (Connection connection = RailwayWebServer.connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE session_token = ?")) {
            statement.setString(1, token);
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Logout error: " + e.getMessage());
        }
    }

    // --- HTTP HANDLERS ---

    public static void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            RailwayWebServer.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        Map<String, String> form = RailwayWebServer.parseForm(exchange);
        String usernameOrEmail = form.getOrDefault("username", form.getOrDefault("email", "")).trim();
        String password = form.getOrDefault("password", "").trim();

        if (usernameOrEmail.isBlank() || password.isBlank()) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"Please enter your username/email and password\"}");
            return;
        }

        String[] authResult = authenticateUser(usernameOrEmail, password);
        if (authResult == null) {
            RailwayWebServer.sendJson(exchange, 401, "{\"error\":\"Invalid username/email or password\"}");
            return;
        }

        String token = authResult[0];
        String userJson = authResult[1];

        // Set session cookie
        exchange.getResponseHeaders().add("Set-Cookie",
                "session_token=" + token + "; Path=/; Max-Age=86400; SameSite=Lax");

        String response = "{\"token\":" + RailwayWebServer.quote(token) + ",\"user\":" + userJson + "}";
        RailwayWebServer.sendJson(exchange, 200, response);
    }

    public static void handleRegister(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            RailwayWebServer.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        Map<String, String> form = RailwayWebServer.parseForm(exchange);
        String username = form.getOrDefault("username", "").trim().toLowerCase();
        String email = form.getOrDefault("email", "").trim().toLowerCase();
        String fullName = form.getOrDefault("fullName", "").trim();
        String password = form.getOrDefault("password", "").trim();

        // Validation
        if (username.isBlank() || email.isBlank() || fullName.isBlank() || password.isBlank()) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"All fields are required\"}");
            return;
        }

        if (username.length() < 3 || username.length() > 30 || !username.matches("^[a-zA-Z0-9_.-]+$")) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"Username must be 3-30 letters/numbers with no spaces\"}");
            return;
        }

        if (!email.contains("@") || !email.contains(".")) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"Please enter a valid email address\"}");
            return;
        }

        if (password.length() < 6) {
            RailwayWebServer.sendJson(exchange, 400, "{\"error\":\"Password must be at least 6 characters\"}");
            return;
        }

        try (Connection connection = RailwayWebServer.connect()) {
            // Check if username or email is already taken
            try (PreparedStatement check = connection.prepareStatement("SELECT username, email FROM users WHERE username = ? OR email = ?")) {
                check.setString(1, username);
                check.setString(2, email);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        String existingUsername = rs.getString("username");
                        if (username.equalsIgnoreCase(existingUsername)) {
                            RailwayWebServer.sendJson(exchange, 409, "{\"error\":\"Username is already registered. Please choose another.\"}");
                        } else {
                            RailwayWebServer.sendJson(exchange, 409, "{\"error\":\"Email is already registered. Please login instead.\"}");
                        }
                        return;
                    }
                }
            }

            int userId = createUser(connection, username, email, password, fullName, "PASSENGER");
            if (userId > 0) {
                // Auto-login the newly registered passenger
                String[] authResult = authenticateUser(username, password);
                if (authResult != null) {
                    String token = authResult[0];
                    String userJson = authResult[1];
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "session_token=" + token + "; Path=/; Max-Age=86400; SameSite=Lax");
                    RailwayWebServer.sendJson(exchange, 201, "{\"message\":\"Registration successful\",\"token\":"
                            + RailwayWebServer.quote(token) + ",\"user\":" + userJson + "}");
                    return;
                }
                RailwayWebServer.sendJson(exchange, 201, "{\"message\":\"Registration successful! You can now log in.\"}");
            } else {
                RailwayWebServer.sendJson(exchange, 500, "{\"error\":\"Failed to create account\"}");
            }
        } catch (SQLException e) {
            RailwayWebServer.sendError(exchange, e);
        }
    }

    public static void handleLogout(HttpExchange exchange) throws IOException {
        String token = extractToken(exchange);
        if (token != null) {
            invalidateSession(token);
        }

        // Expire cookie
        exchange.getResponseHeaders().add("Set-Cookie",
                "session_token=; Path=/; Max-Age=0; SameSite=Lax");

        RailwayWebServer.sendJson(exchange, 200, "{\"message\":\"Logged out successfully\"}");
    }

    public static void handleMe(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            RailwayWebServer.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        User user = getAuthenticatedUser(exchange);
        if (user == null) {
            RailwayWebServer.sendJson(exchange, 401, "{\"authenticated\":false,\"error\":\"Not authenticated\"}");
            return;
        }

        RailwayWebServer.sendJson(exchange, 200, "{\"authenticated\":true,\"user\":" + user.toJson() + "}");
    }
}
