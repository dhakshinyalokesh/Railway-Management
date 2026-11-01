import java.util.ArrayList;

public interface PlatformAllocationPolicy {

    Platform allocatePlatform(
            TrainService train,
            ArrayList<Platform> platforms,
            ArrayList<PlatformOccupation> timetable,
            int clearanceBuffer);
}