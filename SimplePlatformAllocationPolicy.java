import java.util.ArrayList;
import java.util.Comparator;

public class SimplePlatformAllocationPolicy
        implements PlatformAllocationPolicy {

    @Override
    public Platform allocatePlatform(
            TrainService train,
            ArrayList<Platform> platforms,
            ArrayList<PlatformOccupation> timetable,
            int clearanceBuffer) {

        int start = train.getArrivalTime();

        int end = train.getDepartureTime()
                + clearanceBuffer;

        platforms.sort(
                Comparator.comparingInt(
                        Platform::getPlatformId));

        for (Platform platform : platforms) {

  

            if (platform.getPlatformLength()
                    < train.getTrainLength()) {

                continue;
            }


            if (platform.isOccupied()) {

                continue;
            }

            boolean conflict = false;

            for (PlatformOccupation occupation : timetable) {

                if (occupation.getPlatformId()
                        == platform.getPlatformId()) {

                    if (occupation.overlaps(start, end)) {

                        conflict = true;
                        break;
                    }
                }
            }

            if (!conflict) {
                return platform;
            }
        }

        return null;
    }
}