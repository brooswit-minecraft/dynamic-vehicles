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
    /** Below this speed, m/s, a closed throttle contributes no drivetrain force at all (MINECRAFT-444):
     * there is effectively nothing turning for the engine to drag against, and {@code SableCarBody.tick}'s
     * own parked-hold brake already owns holding a standing car. Keeps engine drag from ever reading as a
     * force on a car at rest. */
    private static final double DRAG_STANDSTILL_SPEED = 0.5;

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
     * The force one driven wheel's contact patch feels from the clutch's own transmitted torque, N, signed
     * in the car's own forward frame (MINECRAFT-444).
     *
     * <p>{@link DrivetrainMath#clutchTorque} is a torque on the ENGINE's own shaft, upstream of the
     * gearbox. An ideal gearbox conserves power, so the torque arriving at the wheel shaft is multiplied by
     * exactly the same {@code ratio * FINAL_DRIVE} that {@link DrivetrainMath#wheelSideOmega} divides the
     * speed by on the way back -- then divided by the wheel radius for a contact force, and shared across
     * the driven wheels, because one engine's torque is split among them rather than duplicated per wheel.
     *
     * <p>Sign: {@code clutchTorque > 0} is torque flowing engine -&gt; drivetrain (the engine driving the
     * car in the engaged gear's own direction, hence negated for {@link CarPhysics.Gear#REVERSE});
     * {@code clutchTorque < 0} is the drivetrain dragging the engine around, i.e. engine braking, and comes
     * back out of here as a force opposing that gear's direction. {@link CarPhysics.Gear#NEUTRAL} is
     * disengaged and transmits nothing in either direction.
     */
    public static double clutchWheelForce(double clutchTorque, double ratio, double wheelRadius,
            int drivenWheels, CarPhysics.Gear gear, double forceScale) {
        // No coupling argument: this converts an already-computed clutch torque through the gearing, which
        // is the same arithmetic whatever the engine's own settings are.
        if (gear == CarPhysics.Gear.NEUTRAL || !(wheelRadius > 0.0)) {
            return 0.0;
        }
        double wheelShaftTorque = clutchTorque * ratio * DrivetrainMath.FINAL_DRIVE;
        double perWheel = wheelShaftTorque / wheelRadius / Math.max(1, drivenWheels);
        double gearDirection = gear == CarPhysics.Gear.REVERSE ? -1.0 : 1.0;
        return perWheel * gearDirection * forceScale;
    }

    /**
     * This wheel's actual drivetrain-sourced longitudinal command, N (MINECRAFT-444): the single place the
     * engine/clutch reaches the tire, replacing {@link #drive}'s own throttle-only force as the value a
     * caller hands {@code WheelMath.tire}. Two things the throttle-only path could not express:
     *
     * <ul>
     * <li><b>Engine braking propagates.</b> Coasting in gear, the clutch torque is negative (the wheels are
     * turning the engine against its own drag), so this returns a RETARDING force even at zero throttle,
     * where {@link #drive} returns exactly 0 and the car used to coast as if the engine were declutched.
     * <li><b>A slipping clutch bounds what the wheels get.</b> While driving, the throttle's demand is
     * capped by the magnitude the clutch is actually transmitting, so a capacity-limited (slipping) clutch
     * genuinely limits wheel torque instead of the wheels receiving full throttle force regardless.
     * </ul>
     *
     * <p>Not a second propulsion source: in the driving direction this only ever REDUCES {@link #drive}'s
     * own already-governed demand (never exceeds it), so every existing governor/ratio/limiter/top-speed
     * behaviour still bounds the result from above. In particular a clutch that happens to be driving while
     * the driver has lifted off (engine spun up, throttle released) cannot propel the car on its own: the
     * demand is 0 there, and {@code min} keeps it 0 -- the acceptance criteria's "neutral and brakes never
     * create unintended propulsion/reverse" is preserved by construction, not by a separate guard.
     *
     * <p>{@code clutchWheelForce} is {@link DrivetrainMath#clutchExchangeTorque} through this gear's own
     * gearing -- the single torque the clutch exchanges, the same value the engine integrates against, so
     * the two shafts are loaded equally and oppositely by construction.
     *
     * <p>Which of the two cases applies is decided by the THROTTLE, not by the exchange's own sign, and
     * that distinction is load-bearing. The exchange's sign follows the driven wheels' own rotation (the
     * driveshaft's, correctly -- that is what the clutch is bolted to), and a slipping wheel's rotation
     * need not agree with the car's motion: under hard opposing throttle the wheels can already be turning
     * the new way while the body still slides the old way. Branching on the exchange's sign there
     * classified a driving exchange as engine braking and returned it in place of the reverse the driver
     * was asking for, which held the car in a self-sustaining stall just above zero -- measured at +0.029
     * m/s with the throttle pinned in {@code opposingDirectionThrottleDecelerateAndReverseInBothMirroredDirections},
     * where the pure drivetrain arithmetic on its own reverses the same car at -4.67 m/s^2. Branching on
     * the throttle cannot be flipped by a slipping wheel: a driver asking for drive gets drive, bounded by
     * what the clutch can actually pass, and a closed throttle gets whatever the exchange is -- which is
     * the engine's own drag, the thing "engine braking" means.
     */
    public static double driveThroughClutch(double throttle, double forwardSpeed, CarPhysics.Gear gear,
            double forceMultiplier, double driveForce, double reverseForce, double forceScale, double maxSpeed,
            double clutchWheelForce) {
        if (gear == CarPhysics.Gear.NEUTRAL) {
            return 0.0;
        }
        if (throttle <= 0.0) {
            // Coasting or braking: the drivetrain contributes what the clutch is exchanging, which at a
            // closed throttle is the engine's own drag -- but ONLY ever as a force opposing the car's
            // actual motion. A clutch can momentarily be driving with the throttle shut (an engine still
            // spinning faster than the wheel side just after a shift), and the acceptance criteria are
            // explicit that neutral and the brakes must never create propulsion: a driver who has lifted
            // off gets deceleration or nothing. Below DRAG_STANDSTILL_SPEED there is nothing turning worth
            // dragging and SableCarBody's own parked-hold brake already owns holding the car.
            if (Math.abs(forwardSpeed) < DRAG_STANDSTILL_SPEED) {
                return 0.0;
            }
            boolean opposesMotion = clutchWheelForce * forwardSpeed < 0.0;
            return opposesMotion ? clutchWheelForce : 0.0;
        }
        double demand = drive(throttle, forwardSpeed, gear, forceMultiplier, driveForce, reverseForce,
                forceScale, maxSpeed);
        return Math.copySign(Math.min(Math.abs(demand), Math.abs(clutchWheelForce)), demand);
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
