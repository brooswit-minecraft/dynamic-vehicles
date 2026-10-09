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

    /**
     * A smoothed "rev" value (0..1) that chases the throttle input: fast up so a stab of the gas reads
     * as immediate, slower down so it doesn't snap silent the instant the pedal is lifted. Call once per
     * client tick with the previous rev and the current throttle input to get the next rev.
     */
    public static double nextRev(double rev, double throttleInput, double dt) {
        double target = clamp01(Math.abs(throttleInput));
        double rate = target > rev ? 14.0 : 4.0;
        double step = rate * dt;
        double delta = target - rev;
        return rev + Math.max(-step, Math.min(step, delta));
    }

    /** Speed (m/s) each simulated gear tops out at before the next one takes over. */
    private static final double[] GEAR_BREAKS = {0.0, 5.0, 10.0, 16.0, 23.0, 31.0, Double.MAX_VALUE};

    /**
     * Gear-like pitch stepping, as a multiplier layered over the existing speed/throttle cross-fade:
     * climbs through a gear band as speed rises, then drops back down at the shift point into the next
     * gear, instead of climbing smoothly and unendingly with speed.
     */
    public static double gearPitch(double speed) {
        double s = Math.max(0.0, speed);
        int gear = 0;
        while (gear < GEAR_BREAKS.length - 2 && s >= GEAR_BREAKS[gear + 1]) {
            gear++;
        }
        double lo = GEAR_BREAKS[gear];
        double hi = Math.min(GEAR_BREAKS[gear + 1], lo + 40.0);
        double within = clamp01((s - lo) / (hi - lo));
        return 0.88 + 0.26 * within;
    }

    /**
     * 0..1: how hard the engine is straining -- throttle held down without the speed to show for it yet
     * (climbing, towing, or just flooring it from a stop), which is where a real engine note deepens and
     * loudens rather than just following speed.
     */
    public static double loadFactor(double throttleInput, double speed) {
        double throttle = clamp01(Math.abs(throttleInput));
        double unmet = clamp01(1.0 - speed / 10.0);
        return throttle * unmet;
    }

    /** Engine pitch: rises with speed, steps through gears, chases the throttle via the smoothed rev
     * value instead of lagging behind road speed, and dips slightly under load (a straining engine's
     * note drops before it catches back up). */
    public static double enginePitch(double speed, double rev, double load) {
        double base = 0.8 + 0.5 * clamp01(speed / 32.0) + 0.2 * clamp01(rev);
        return base * gearPitch(speed) - 0.08 * clamp01(load);
    }

    /** Overall engine loudness: louder under throttle, and louder still under load (straining). */
    public static double engineVolume(double throttle, double load) {
        return 0.55 + 0.45 * clamp01(throttle) + 0.25 * clamp01(load);
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
