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

    static double slip(Object body) {
        return ((SableCarBody) body).lastSlipSpeed();
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

    /** The gear ratio actually engaged this tick; see {@link SableCarBody#gearRatio()}. */
    static double gearRatio(Object body) {
        return ((SableCarBody) body).gearRatio();
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

    static double[] wheelSteerAngles(Object body) {
        return ((SableCarBody) body).steerAngles();
    }

    static double[] wheelSpinRates(Object body) {
        return ((SableCarBody) body).wheelSpinRates();
    }

    static double[] wheelContactSpeeds(Object body) {
        return ((SableCarBody) body).wheelContactSpeeds();
    }

    static double[] wheelForces(Object body) {
        return ((SableCarBody) body).wheelForces();
    }

    static void remove(Object body) {
        ((SableCarBody) body).remove();
    }
}
