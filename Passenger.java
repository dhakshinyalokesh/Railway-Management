public class Passenger {

    private String passengerId;
    private String name;

    public Passenger(String passengerId, String name) {
        this.passengerId = passengerId;
        this.name = name;
    }

    public String getPassengerId() {
        return passengerId;
    }

    public String getName() {
        return name;
    }
}