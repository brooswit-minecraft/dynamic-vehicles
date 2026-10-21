package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.server.level.ServerLevel;

/**
 * The only door to Sable. Nothing outside this class may mention a Sable type;
 * the body is passed around as Object and the real work lives in
 * {@link SableCarBody}. Sable is a required dependency: it is the only car physics.
 */
final class SableCompat {
    private SableCompat() {}

    static Object create(ServerLevel level, CarEntity car) {
        return SableCarBody.create(level, car);
    }

    static void tick(Object body, CarEntity car, double throttle, double steer, boolean handbrake, CarPhysics.Gear gear, double dt) {
        ((SableCarBody) body).tick(car, throttle, steer, handbrake, gear, dt);
    }

    static void syncEntity(Object body, CarEntity car) {
        ((SableCarBody) body).syncEntity(car);
    }

    static org.joml.Quaternionf orientation(Object body) {
        return ((SableCarBody) body).orientationF();
    }

    static String describe(Object body, CarPhysics.Gear gear, double throttle) {
        return ((SableCarBody) body).describe(gear, throttle);
    }

    static double audioSlip(Object body) {
        return ((SableCarBody) body).lastAudioSlipSpeed();
    }

    static double slip(Object body) {
        return ((SableCarBody) body).lastSlipSpeed();
    }

    /** MINECRAFT-447 GameTest-only hooks; see each {@code SableCarBody} method's own javadoc for why
     * neither is a second way to configure a real car. */
    static void setLastGroundedYawDegForTest(Object body, double yawDeg) {
        ((SableCarBody) body).setLastGroundedYawDegForTest(yawDeg);
    }

    static void addLinearVelocityForTest(Object body, org.joml.Vector3d linear) {
        ((SableCarBody) body).addLinearVelocityForTest(linear);
    }

    /** Applies a collision's horizontal momentum loss to the live rigid body. */
    static void scaleHorizontalVelocity(Object body, double retainedSpeedScale) {
        ((SableCarBody) body).scaleHorizontalVelocity(retainedSpeedScale);
    }

    /** The real slip speed/load/{@code Erosion.Result} the production tick last actually passed to and
     * got back from {@code SlipReporter.report} (MINECRAFT-401 review, round 4) -- see
     * {@link SableCarBody#lastErosionResult()}. */
    static double lastErosionReportSlipSpeed(Object body) {
        return ((SableCarBody) body).lastErosionReportSlipSpeed();
    }

    static double lastErosionReportLoadKg(Object body) {
        return ((SableCarBody) body).lastErosionReportLoadKg();
    }

    static io.github.brooswitminecraft.dynamicterrain.Erosion.Result lastErosionResult(Object body) {
        return ((SableCarBody) body).lastErosionResult();
    }

    /** Engine RPM implied by the drivetrain this tick (MINECRAFT-401). */
    static double engineRpm(Object body) {
        return ((SableCarBody) body).engineRpm();
    }

    /** Automatic mode's own currently held 0-based gear index, regardless of which gear is selected. */
    static int automaticGearIndex(Object body) {
        return ((SableCarBody) body).automaticGearIndex();
    }

    /** MINECRAFT-444: this tick's own real clutch torque (N*m), the per-wheel force it transmitted (N)
     * and the engine-axis reaction torque applied to the body (N*m). */
    static double clutchTorque(Object body) {
        return ((SableCarBody) body).clutchTorque();
    }

    /** The engine's own net output torque; see {@link SableCarBody#engineOutputTorque()}. */
    static double engineOutputTorque(Object body) {
        return ((SableCarBody) body).engineOutputTorque();
    }

    /** The body's measured world-space angular velocity; see {@link SableCarBody#bodyAngularVelocity()}. */
    static double[] bodyAngularVelocity(Object body) {
        return ((SableCarBody) body).bodyAngularVelocity();
    }

    static double clutchWheelForce(Object body) {
        return ((SableCarBody) body).clutchWheelForce();
    }

    /** The coupled solve's lock torque; see {@link SableCarBody#clutchLockTorque()}. */
    static double clutchLockTorque(Object body) {
        return ((SableCarBody) body).clutchLockTorque();
    }

    /** The driver's governed demand at the engine; see {@link SableCarBody#clutchDemandTorque()}. */
    static double clutchDemandTorque(Object body) {
        return ((SableCarBody) body).clutchDemandTorque();
    }

    /** The wheel side the clutch was solved against; see {@link SableCarBody#clutchWheelSideOmega()}. */
    static double clutchWheelSideOmega(Object body) {
        return ((SableCarBody) body).clutchWheelSideOmega();
    }

    static double clutchWheelSideTorque(Object body) {
        return ((SableCarBody) body).clutchWheelSideTorque();
    }

    /** The drivetrain impulse the wheels actually took; see {@link SableCarBody#realizedDriveImpulse()}. */
    static double realizedDriveImpulse(Object body) {
        return ((SableCarBody) body).realizedDriveImpulse();
    }

    /** The tires' delivered force the next solve will read; see {@link SableCarBody#drivenTireForce()}. */
    static double drivenTireForce(Object body) {
        return ((SableCarBody) body).drivenTireForce();
    }

    /** Per wheel, the same; see {@link SableCarBody#wheelRealizedDriveImpulses()}. */
    static double[] wheelRealizedDriveImpulses(Object body) {
        return ((SableCarBody) body).wheelRealizedDriveImpulses();
    }

    /** Per wheel, how many sub-steps its tire touched something; see {@link SableCarBody#wheelContactSubSteps()}. */
    static int[] wheelContactSubSteps(Object body) {
        return ((SableCarBody) body).wheelContactSubSteps();
    }

    static double engineReactionTorque(Object body) {
        return ((SableCarBody) body).engineReactionTorque();
    }

    /** The engine's own unclamped angular speed, rad/s; see {@link SableCarBody#engineOmega()}. */
    static double engineOmega(Object body) {
        return ((SableCarBody) body).engineOmega();
    }

    /** The engine speed the last tick started from; see {@link SableCarBody#priorEngineOmega()}. */
    static double priorEngineOmega(Object body) {
        return ((SableCarBody) body).priorEngineOmega();
    }

    /** The gear ratio actually engaged this tick; see {@link SableCarBody#gearRatio()}. */
    static double gearRatio(Object body) {
        return ((SableCarBody) body).gearRatio();
    }

    static double lastDiffuserRollTorque(Object body) {
        return ((SableCarBody) body).lastDiffuserRollTorque();
    }

    static double speed(Object body) {
        return ((SableCarBody) body).speed();
    }

    static boolean touching(Object body) {
        return ((SableCarBody) body).touching();
    }

    /** Each wheel's suspension travel (m), steer angle (rad) and spin rate (rad/s), for client sync. */
    static double[] wheelTravel(Object body) {
        return ((SableCarBody) body).suspensionTravel();
    }

    /** The block each wheel's raycast hit on the last tick ({@code null} for a miss), for GameTests. */
    static net.minecraft.core.BlockPos[] wheelContactBlocks(Object body) {
        return ((SableCarBody) body).wheelContactBlocks();
    }

    static double[] wheelSteerAngles(Object body) {
        return ((SableCarBody) body).steerAngles();
    }

    static double[] wheelSpinRates(Object body) {
        return ((SableCarBody) body).wheelSpinRates();
    }

    static double[] wheelContactSpeeds(Object body) {
        return ((SableCarBody) body).wheelContactSpeeds();
    }

    /** Each grounded wheel's actual slip speed in m/s from the same physics tick as its contact block. */
    static double[] wheelSlipSpeeds(Object body) {
        return ((SableCarBody) body).wheelSlipSpeeds();
    }

    /** Each wheel's own drivetrain longitudinal command; see {@link SableCarBody#wheelDriveCommands()}. */
    static double[] wheelDriveCommands(Object body) {
        return ((SableCarBody) body).wheelDriveCommands();
    }

    static double[] wheelForces(Object body) {
        return ((SableCarBody) body).wheelForces();
    }

    static double[] wheelReactionForces(Object body) {
        return ((SableCarBody) body).wheelReactionForces();
    }

    static double[] wheelLateralForces(Object body) {
        return ((SableCarBody) body).wheelLateralForces();
    }

    static double[] wheelFrictionLimits(Object body) {
        return ((SableCarBody) body).wheelFrictionLimits();
    }

    static void remove(Object body) {
        ((SableCarBody) body).remove();
    }

    // MINECRAFT-410: the motorboat's own rigid body, same door, same invariant -- nothing outside this
    // class may mention SableMotorboatBody/Sable types either.

    static Object createBoat(ServerLevel level, MotorboatEntity boat) {
        return SableMotorboatBody.create(level, boat);
    }

    static void tickBoat(Object body, MotorboatEntity boat, double throttle, double steer, double dt) {
        ((SableMotorboatBody) body).tick(boat, throttle, steer, dt);
    }

    static void syncEntityBoat(Object body, MotorboatEntity boat) {
        ((SableMotorboatBody) body).syncEntity(boat);
    }

    static org.joml.Quaternionf orientationBoat(Object body) {
        return ((SableMotorboatBody) body).orientationF();
    }

    static double speedBoat(Object body) {
        return ((SableMotorboatBody) body).speed();
    }

    static String describeBoat(Object body) {
        return ((SableMotorboatBody) body).describe();
    }

    static void removeBoat(Object body) {
        ((SableMotorboatBody) body).remove();
    }
}
