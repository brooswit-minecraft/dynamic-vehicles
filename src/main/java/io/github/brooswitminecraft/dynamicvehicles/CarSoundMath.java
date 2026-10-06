package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure mapping from what the car is doing to how loud and how pitched each of
 * its sounds is. No Minecraft types, so the curves can be unit tested.
 */
public final class CarSoundMath {
    private CarSoundMath() {}

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Volumes of the idle, mid and high engine loops at a speed (m/s), cross-faded so one or two are audible. */
    public static double[] engineMix(double speed) {
        double idle = clamp01(1.0 - speed / 7.0);
        double mid = clamp01(speed / 4.0) * clamp01(1.0 - Math.max(0.0, speed - 12.0) / 12.0);
        double high = clamp01((speed - 13.0) / 12.0);
        return new double[] {idle, mid, high};
    }

    /** Engine pitch: rises with speed and with the throttle held. */
    public static double enginePitch(double speed, double throttle) {
        return 0.8 + 0.55 * clamp01(speed / 32.0) + 0.15 * clamp01(throttle);
    }

    /** Overall engine loudness: louder under throttle. */
    public static double engineVolume(double throttle) {
        return 0.55 + 0.45 * clamp01(throttle);
    }

    /** 0 = none (below the threshold), 1 light, 2 medium, 3 hard, 4 severe, from how much speed a crash shed (m/s). */
    public static int impactSeverity(double speedLost) {
        if (speedLost < 3.0) {
            return 0;
        }
        if (speedLost < 6.0) {
            return 1;
        }
        if (speedLost < 10.0) {
            return 2;
        }
        if (speedLost < 16.0) {
            return 3;
        }
        return 4;
    }

    public static double impactVolume(int severity) {
        return switch (severity) {
            case 1 -> 0.5;
            case 2 -> 0.75;
            case 3 -> 0.95;
            case 4 -> 1.0;
            default -> 0.0;
        };
    }

    /** Tire rolling noise grows with speed up to a cruise. */
    public static double rollingVolume(double speed) {
        return 0.7 * clamp01(speed / 14.0);
    }

    public static double rollingPitch(double speed) {
        return 0.85 + 0.3 * clamp01(speed / 30.0);
    }

    /** Skid loudness from the slip level (0..1). */
    public static double skidVolume(double slip) {
        return clamp01(slip) * 0.9;
    }

    /** Which skid sample: the quiet one for a light slide, the hard one past this slip. */
    public static boolean hardSkid(double slip) {
        return slip >= 0.5;
    }

    /** Slip level (0..1) from a tire slip speed in m/s; 4 m/s of uncorrected sliding is a full skid. */
    public static double slipLevel(double slipSpeed) {
        return clamp01(slipSpeed / 4.0);
    }
}
