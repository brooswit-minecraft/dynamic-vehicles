package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure per-wheel accelerator/brake force from the signed throttle (accelerator minus brake pedal, or the
 * keyboard/gamepad zza), the drivetrain's explicit {@link CarPhysics.Gear} and, for a forward gear, the
 * drive-force multiplier its currently engaged ratio implies ({@link DrivetrainMath#forceMultiplier}).
 *
 * <p>The accelerator (throttle &gt; 0) propels only in the selected gear's own direction: forward for a
 * forward gear or {@link CarPhysics.Gear#AUTOMATIC}, backward for {@link CarPhysics.Gear#REVERSE}, and
 * never in {@link CarPhysics.Gear#NEUTRAL}. The brake (throttle &lt; 0) always opposes whatever the car is
 * actually doing -- see {@link #brake} -- regardless of gear, so it never itself drives the car backward
 * (that is the accelerator's job once REVERSE is selected).
 *
 * <p>MINECRAFT-401: unlike the force this replaces, the accelerator is never gated to zero merely because
 * the car is currently rolling the other way -- forward torque while rolling backward, and reverse torque
 * while rolling forward, both apply in the selected direction through this wheel's own traction-limited
 * tire force (the friction circle in {@link WheelMath#tire}), exactly like accelerating in the car's own
 * direction already does. Nothing here teleports the car's velocity: the force below is one tick's worth
 * of impulse, so the chassis keeps rolling the old way until that force, integrated tick after tick,
 * genuinely overcomes its motion -- there is no instantaneous flip to prevent in the first place. What
 * each direction's own governor term below still does is soften the force as the car's speed in THAT
 * gear's own direction approaches its governed top speed (an engine power curve flattening out), which is
 * a different thing from the removed opposing-direction gate and must not reintroduce it: the governor is
 * keyed to {@code Math.max(0.0, forwardSpeed)} (or its reverse-signed equivalent), not {@code
 * Math.abs(forwardSpeed)}, so an opposing speed of any magnitude never softens the force at all.
 */
public final class GearDrive {
    /** Reverse's own governed top speed, m/s -- unrelated to the forward gears' {@code maxSpeed}. */
    private static final double REVERSE_GOVERNOR_SPEED = 8.0;

    private GearDrive() {}

    /**
     * The accelerator's drive force for this wheel, or 0 outside the gear's own direction or in NEUTRAL.
     *
     * @param forceMultiplier {@link DrivetrainMath#forceMultiplier} for the gear ratio actually engaged
     *        this tick (the manual gear's own ratio, or automatic mode's currently selected one); ignored
     *        for {@link CarPhysics.Gear#REVERSE}, which has its own single ratio baked into
     *        {@code reverseForce}.
     */
    public static double drive(double throttle, double forwardSpeed, CarPhysics.Gear gear, double forceMultiplier,
            double driveForce, double reverseForce, double forceScale, double maxSpeed) {
        if (throttle <= 0 || gear == CarPhysics.Gear.NEUTRAL) {
            return 0.0;
        }
        if (gear == CarPhysics.Gear.REVERSE) {
            double governor = Math.max(0.0, 1.0 - Math.max(0.0, -forwardSpeed) / REVERSE_GOVERNOR_SPEED);
            return -throttle * reverseForce * forceScale * governor;
        }
        double governor = Math.max(0.0, 1.0 - Math.max(0.0, forwardSpeed) / maxSpeed);
        return throttle * driveForce * forceMultiplier * forceScale * governor;
    }

    /**
     * The brake pedal's force for this wheel: always decelerates the car's actual motion, any gear, any
     * speed (MINECRAFT-382) -- including holding it near a standstill, forward or backward, rather than
     * releasing the car to roll once it drops below some speed threshold. WheelMath.tire()'s own stop
     * target already scales down smoothly as forwardSpeed approaches zero, so handing it a full brakeForce
     * here never overshoots into driving the car the other way.
     */
    public static double brake(double throttle, double forwardSpeed, double brakeForce, double forceScale) {
        return throttle < 0 ? -throttle * brakeForce * forceScale : 0.0;
    }
}
