package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure mapping from raw joystick axis values (-1..1, as GLFW reports them) to the car's steer, throttle and
 * brake inputs. No Minecraft or GLFW types, so it is unit-tested on synthetic axis arrays.
 */
public final class WheelMapping {
    /** Where a released pedal sits on its axis. */
    public enum PedalRest { AUTO, HIGH, LOW }

    public record Settings(int steerAxis, int throttleAxis, int brakeAxis, boolean invertSteer, boolean combinedPedals,
                           boolean invertPedals, double steerDeadzone, double steerScale, double pedalDeadzone,
                           double steerCurve) {}

    /** steer: positive = left (the vanilla xxa convention the car reads); throttle and brake are 0..1. */
    public record Output(double steer, double throttle, double brake) {
        public double forward() {
            return throttle - brake;
        }
    }

    /** Known axis layout of a device, found by its GLFW name; pedal rest is where the released pedal sits. */
    public record Profile(int steerAxis, int throttleAxis, int brakeAxis, PedalRest pedalRest) {}

    /** Layout used for a device with no profile. */
    public static final Profile GENERIC = new Profile(0, 2, 3, PedalRest.AUTO);
    /** Logitech G29 through G HUB, measured on a real one: axis 0 wheel, axis 1 accelerator, axis 2 brake, axis 3 clutch (unused), all pedals rest at +1 and go to -1. */
    public static final Profile G29 = new Profile(0, 1, 2, PedalRest.HIGH);

    private WheelMapping() {}

    public static Profile profileFor(String deviceName) {
        String name = deviceName == null ? "" : deviceName.toLowerCase(java.util.Locale.ROOT);
        return name.contains("g29") ? G29 : GENERIC;
    }

    /** Gain that makes `effectiveDegrees` of wheel travel (lock to lock) reach full lock on a wheel that turns `lockDegrees`. */
    public static double lockGain(double lockDegrees, double effectiveDegrees) {
        return effectiveDegrees <= 0 ? 1.0 : Math.max(1.0, lockDegrees / effectiveDegrees);
    }

    /** A configured axis (-1 = take the profile's) or the profile's value. */
    public static int axisOr(int configured, int profileAxis) {
        return configured >= 0 ? configured : profileAxis;
    }

    /** The configured rest mode; AUTO defers to the profile's when it knows better. */
    public static PedalRest restOr(PedalRest configured, PedalRest profile) {
        return configured == PedalRest.AUTO ? profile : configured;
    }

    /** Axis value of a released pedal read from a sample, or NaN if the sample is not at either end (pedal unread or half pressed). */
    public static double detectRest(double sample) {
        if (sample >= 0.9) {
            return 1.0;
        }
        if (sample <= -0.9) {
            return -1.0;
        }
        return Double.NaN;
    }

    /** Resolves the configured rest mode; NaN when AUTO has not seen a released pedal yet. */
    public static double rest(PedalRest mode, double detected) {
        return switch (mode) {
            case HIGH -> 1.0;
            case LOW -> -1.0;
            case AUTO -> detected;
        };
    }

    public static double axis(float[] axes, int index) {
        return index >= 0 && index < axes.length ? axes[index] : 0.0;
    }

    /** Steering: deadzone, rescale so full lock still reaches 1, scale, clamp, and flip to the car's positive = left. */
    public static double steer(double value, double deadzone, double scale, boolean invert, double curve) {
        double magnitude = Math.abs(value);
        if (magnitude <= deadzone) {
            return 0.0;
        }
        double shaped = (magnitude - deadzone) / (1.0 - deadzone) * scale;
        shaped = Math.pow(Math.min(1.0, shaped), Math.max(1.0, curve));
        double rightPositive = Math.copySign(shaped, value);
        double left = invert ? rightPositive : -rightPositive;
        return left == 0.0 ? 0.0 : left;
    }

    /** One pedal's travel 0..1 from its axis value and rest value; 0 if the rest is unknown. */
    public static double pedal(double value, double rest, double deadzone) {
        if (Double.isNaN(rest)) {
            return 0.0;
        }
        double end = -rest;
        double travel = (value - rest) / (end - rest);
        travel = Math.max(0.0, Math.min(1.0, travel));
        if (travel <= deadzone) {
            return 0.0;
        }
        return Math.min(1.0, (travel - deadzone) / (1.0 - deadzone));
    }

    /** A combined pedal axis: centred is neither, one end throttle, the other brake. */
    public static Output combined(double value, double deadzone, boolean invert, double steer) {
        double c = invert ? -value : value;
        double magnitude = Math.abs(c);
        if (magnitude <= deadzone) {
            return new Output(steer, 0.0, 0.0);
        }
        double shaped = Math.min(1.0, (magnitude - deadzone) / (1.0 - deadzone));
        return c > 0 ? new Output(steer, shaped, 0.0) : new Output(steer, 0.0, shaped);
    }

    public static Output map(float[] axes, Settings s, double throttleRest, double brakeRest) {
        double steer = steer(axis(axes, s.steerAxis()), s.steerDeadzone(), s.steerScale(), s.invertSteer(), s.steerCurve());
        if (s.combinedPedals()) {
            return combined(axis(axes, s.throttleAxis()), s.pedalDeadzone(), s.invertPedals(), steer);
        }
        return new Output(steer,
                pedal(axis(axes, s.throttleAxis()), throttleRest, s.pedalDeadzone()),
                pedal(axis(axes, s.brakeAxis()), brakeRest, s.pedalDeadzone()));
    }

    /** The wheel wins on a channel it is actually moving; otherwise the keyboard's value stands. */
    public static float merge(float keyboard, double wheel) {
        return Math.abs(wheel) > 1.0e-4 ? (float) Math.max(-1.0, Math.min(1.0, wheel)) : keyboard;
    }

    /** Honk and handbrake read from the wheel's shifter paddles, independent of each other and of jump. */
    public record Paddles(boolean honk, boolean handbrake) {}

    /** Whether a button index is pressed in a raw GLFW-style button sample; -1 (disabled) never reads as pressed. */
    public static boolean buttonDown(byte[] buttons, int index) {
        return index >= 0 && index < buttons.length && buttons[index] != 0;
    }

    public static Paddles paddles(byte[] buttons, int honkButton, int handbrakeButton) {
        return new Paddles(buttonDown(buttons, honkButton), buttonDown(buttons, handbrakeButton));
    }

    /**
     * CarEntity's honk/handbrake decision (MINECRAFT-167): jump alone drives both exactly as before when the
     * wheel reports neither paddle pressed (no wheel detected, or a wheel with that paddle disabled/unpressed);
     * each wheel paddle is an independent extra source once it fires, regardless of jump (a wheel-active client
     * forces its own jump key to false before this ever sees it, so the two never actually compete).
     */
    public static Paddles resolve(boolean jumpPressed, Paddles wheelPaddles) {
        return new Paddles(jumpPressed || wheelPaddles.honk(), jumpPressed || wheelPaddles.handbrake());
    }
}
