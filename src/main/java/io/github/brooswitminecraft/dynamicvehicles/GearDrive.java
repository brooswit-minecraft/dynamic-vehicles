package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure per-wheel accelerator/brake force from the signed throttle (accelerator minus brake pedal, or the
 * keyboard/gamepad zza) and the drivetrain's explicit {@link CarPhysics.Gear} (MINECRAFT-382).
 *
 * <p>The accelerator (throttle &gt; 0) propels only in the selected gear's own direction: forward for a
 * forward gear, backward for {@link CarPhysics.Gear#REVERSE}, and never in {@link CarPhysics.Gear#NEUTRAL}.
 * The brake (throttle &lt; 0) always opposes whatever the car is actually doing -- see {@link #brake} --
 * regardless of gear, so it never itself drives the car backward (that is the accelerator's job once
 * REVERSE is selected). Each direction is gated on the car already being at most barely moving the other
 * way, so shifting at speed coasts to a stop under the brake/rolling resistance first rather than the
 * drive force instantaneously reversing the car's velocity in one tick.
 */
public final class GearDrive {
    private GearDrive() {}

    /** The accelerator's drive force for this wheel, or 0 outside the gear's own direction or in NEUTRAL. */
    public static double drive(double throttle, double forwardSpeed, CarPhysics.Gear gear,
            double driveForce, double reverseForce, double forceScale, double maxSpeed) {
        if (throttle <= 0 || gear == CarPhysics.Gear.NEUTRAL) {
            return 0.0;
        }
        if (gear == CarPhysics.Gear.REVERSE) {
            return forwardSpeed <= 0.5
                    ? -throttle * reverseForce * forceScale * Math.max(0.0, 1.0 - Math.abs(forwardSpeed) / 8.0)
                    : 0.0;
        }
        return forwardSpeed >= -0.5
                ? throttle * driveForce * forceScale * Math.max(0.0, 1.0 - Math.abs(forwardSpeed) / maxSpeed)
                : 0.0;
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
