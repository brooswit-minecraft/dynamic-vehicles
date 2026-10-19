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

    static String describe(Object body) {
        return ((SableCarBody) body).describe();
    }

    static double slip(Object body) {
        return ((SableCarBody) body).lastSlipSpeed();
    }

    static boolean touching(Object body) {
        return ((SableCarBody) body).touching();
    }

    static void setLastGroundedYawDegForTest(Object body, double yawDeg) {
        ((SableCarBody) body).setLastGroundedYawDegForTest(yawDeg);
    }

    static void addLinearVelocityForTest(Object body, org.joml.Vector3d linear) {
        ((SableCarBody) body).addLinearVelocityForTest(linear);
    }

    static double speed(Object body) {
        return ((SableCarBody) body).speed();
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
