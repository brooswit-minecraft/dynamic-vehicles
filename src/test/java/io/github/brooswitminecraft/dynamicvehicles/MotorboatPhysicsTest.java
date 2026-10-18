package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** MINECRAFT-380 review fix: a pre-fix version of {@link MotorboatPhysics} mixed a "9 m/s" per-second
 * engine accel into a per-tick integration with no conversion, so full throttle actually converged to
 * about 103.5 blocks/sec (18x {@code MAX_SPEED}). These tests pin the per-tick convention down: speed
 * must never exceed {@code MAX_SPEED}, and distance travelled over a known number of ticks must match
 * the real-world travel a boat this fast should cover, not an 18x-inflated one. */
class MotorboatPhysicsTest {

    @Test
    void fullThrottleNeverExceedsMaxSpeed() {
        double vx = 0, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < 400; tick++) {
            MotorboatPhysics.Result r = MotorboatPhysics.step(vx, vy, vz, yaw, 1.0, 0.0, true, true, 0.0);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
            double speed = Math.hypot(vx, vz);
            assertTrue(speed <= MotorboatPhysics.MAX_SPEED + 1e-9,
                    "tick " + tick + ": speed " + speed + " exceeded MAX_SPEED " + MotorboatPhysics.MAX_SPEED);
        }
    }

    @Test
    void oneSecondOfFullThrottleCoversARealisticDistance() {
        double vx = 0, vy = 0, vz = 0, yaw = 0;
        double distance = 0;
        for (int tick = 0; tick < 20; tick++) {
            MotorboatPhysics.Result r = MotorboatPhysics.step(vx, vy, vz, yaw, 1.0, 0.0, true, true, 0.0);
            vx = r.vx();
            vz = r.vz();
            yaw = r.yawDeg();
            distance += Math.hypot(vx, vz);
        }
        // Upper bound: every one of the 20 ticks travelling at the hard cap. The pre-fix bug would have
        // put this bound-violating distance in the hundreds of blocks for a single second of throttle.
        assertTrue(distance <= MotorboatPhysics.MAX_SPEED * 20 + 1e-6,
                "one second of full throttle covered " + distance + " blocks, above the " + MotorboatPhysics.MAX_SPEED * 20 + " block/sec cap");
    }

    @Test
    void idleBoatDecaysTowardRestUnderDrag() {
        double vx = MotorboatPhysics.MAX_SPEED, vy = 0, vz = 0, yaw = 0;
        double previousSpeed = Math.hypot(vx, vz);
        for (int tick = 0; tick < 50; tick++) {
            MotorboatPhysics.Result r = MotorboatPhysics.step(vx, vy, vz, yaw, 0.0, 0.0, true, true, 0.0);
            vx = r.vx();
            vz = r.vz();
            double speed = Math.hypot(vx, vz);
            assertTrue(speed <= previousSpeed + 1e-9, "tick " + tick + ": speed grew from " + previousSpeed + " to " + speed + " with no throttle");
            previousSpeed = speed;
        }
        assertTrue(previousSpeed < 0.01, "boat should have nearly stopped after 50 idle ticks, still at " + previousSpeed);
    }

    @Test
    void beachedBoatFallsUnderGravityNotFloatCorrection() {
        double vy = 0;
        for (int tick = 0; tick < 10; tick++) {
            MotorboatPhysics.Result r = MotorboatPhysics.step(0, vy, 0, 0, 0.0, 0.0, false, false, 0.0);
            vy = r.vy();
        }
        assertTrue(vy < 0, "a boat with no water nearby should be falling, vy=" + vy);
    }
}
