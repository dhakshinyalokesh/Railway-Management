/**
 * Simple User class representing a system user (Passenger, Staff, or Admin).
 * Uses plain Java with no external dependencies for beginner-friendly simplicity.
 */
public class User {
    private final int userId;
    private final String username;
    private final String email;
    private final String fullName;
    private final String role;
    private final String createdAt;

    public User(int userId, String username, String email, String fullName, String role, String createdAt) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.createdAt = createdAt;
    }

    public int getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRole() {
        return role;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public boolean isPassenger() {
        return "PASSENGER".equalsIgnoreCase(role);
    }

    public boolean isStaff() {
        return "STAFF".equalsIgnoreCase(role);
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    /**
     * Converts user details to a clean JSON string.
     */
    public String toJson() {
        return "{" +
                "\"userId\":" + userId + "," +
                "\"username\":" + RailwayWebServer.quote(username) + "," +
                "\"email\":" + RailwayWebServer.quote(email) + "," +
                "\"fullName\":" + RailwayWebServer.quote(fullName) + "," +
                "\"role\":" + RailwayWebServer.quote(role) + "," +
                "\"createdAt\":" + RailwayWebServer.quote(createdAt == null ? "" : createdAt) +
                "}";
    }
}
