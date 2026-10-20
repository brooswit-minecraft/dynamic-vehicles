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
     * <p>{@link DrivetrainMath#realizableClutchTorque} is a torque on the ENGINE's own shaft, upstream of
     * the gearbox. An ideal gearbox conserves power, so the torque arriving at the wheel shaft is multiplied
     * by exactly the same {@code ratio * FINAL_DRIVE} that {@link DrivetrainMath#wheelSideOmegaSigned}
     * multiplies the wheels' speed by on the way back -- then divided by the wheel radius for a contact
     * force, and shared across the driven wheels, because one engine's torque is split among them rather
     * than duplicated per wheel. {@link DrivetrainMath#clutchTorqueToTotalWheelForce} is that transform,
     * and {@link DrivetrainMath#wheelForceToClutchTorque} its exact inverse.
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
        if (gear == CarPhysics.Gear.NEUTRAL) {
            return 0.0;
        }
        double gearDirection = gear == CarPhysics.Gear.REVERSE ? -1.0 : 1.0;
        return DrivetrainMath.clutchTorqueToTotalWheelForce(clutchTorque, wheelRadius, ratio, gearDirection,
                forceScale) / Math.max(1, drivenWheels);
    }

    /**
     * This wheel's actual drivetrain-sourced longitudinal command, N (MINECRAFT-444): the single place the
     * engine/clutch reaches the tire, replacing {@link #drive}'s own throttle-only force as the value a
     * caller hands {@code WheelMath.tire}. Engine braking now propagates -- coasting in gear the clutch
     * passes a RETARDING force even at zero throttle, where {@link #drive} returns exactly 0 -- and a
     * slipping (capacity-limited) clutch genuinely bounds what the wheels get.
     *
     * <p>{@code clutchWheelForce} is this wheel's share of what the clutch OFFERED this tick:
     * {@link DrivetrainMath#realizableClutchTorque} through the engaged gearing. Review round 3 moved every
     * decision that used to be made here about the wheel half alone -- capping it at the driver's demand,
     * re-signing it to the demand's direction -- into that method, ahead of the engine's integration, so
     * this can only ever pass the offer on unchanged or withhold it; the engine is then charged for exactly
     * what the wheels took. Round 2 re-signed it here instead: with the wheel side over-running the engine
     * under full throttle the exchange was negative (spinning the engine UP) while the wheels were still
     * handed a positive, driving force -- both shafts gaining energy from the one exchange.
     *
     * <ul>
     * <li><b>Open throttle:</b> the offer, which {@code realizableClutchTorque} never points against the
     * selected gear (an exchange that reads the other way is decoupled there, not flipped here); anything
     * that still did would be withheld rather than driven.
     * <li><b>Closed throttle or brake:</b> the offer only as a force opposing the car's ACTUAL motion,
     * never below {@link #DRAG_STANDSTILL_SPEED}. A clutch can momentarily be driving with the throttle shut
     * (an engine still spinning faster than the wheel side just after a shift), and the acceptance criteria
     * are explicit that neutral and the brakes never create propulsion: a driver who has lifted off gets
     * deceleration or nothing. Below the threshold there is nothing turning worth dragging, and
     * {@code SableCarBody}'s own parked-hold brake already owns holding the car.
     * </ul>
     *
     * <p>The two cases are told apart by the THROTTLE, not by the offer's own sign, and that distinction is
     * load-bearing: under hard opposing throttle the driven wheels can already be turning the new way while
     * the body still slides the old way, so a sign-based test once classified a driving exchange as engine
     * braking and held the car in a self-sustaining stall at +0.029 m/s with the throttle pinned in
     * {@code opposingDirectionThrottleDecelerateAndReverseInBothMirroredDirections}.
     */
    public static double driveThroughClutch(double throttle, double forwardSpeed, CarPhysics.Gear gear,
            double clutchWheelForce) {
        if (gear == CarPhysics.Gear.NEUTRAL) {
            return 0.0;
        }
        if (throttle > 0.0) {
            double gearDirection = gear == CarPhysics.Gear.REVERSE ? -1.0 : 1.0;
            return clutchWheelForce * gearDirection > 0.0 ? clutchWheelForce : 0.0;
        }
        if (Math.abs(forwardSpeed) < DRAG_STANDSTILL_SPEED) {
            return 0.0;
        }
        return clutchWheelForce * forwardSpeed < 0.0 ? clutchWheelForce : 0.0;
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
