package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** MINECRAFT-380 review fix: a pre-fix version of {@link BiplanePhysics} mixed per-second thrust/speed
 * constants into a per-tick integration (the takeoff threshold alone converted to 140 blocks/sec) and
 * accumulated climb/dive velocity with no cap or path back to level. These tests check the fix across the
 * full ground-taxi / takeoff / sustained-climb / return-to-level / stall sequence the review asked for. */
class BiplanePhysicsTest {

    private static double speedAfterTicks(int ticks, double throttle) {
        double vx = 0, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < ticks; tick++) {
            BiplanePhysics.Result r = BiplanePhysics.step(vx, vy, vz, yaw, throttle, 0.0, 0.0, true, true);
            vx = r.vx();
            vz = r.vz();
            yaw = r.yawDeg();
        }
        return Math.hypot(vx, vz);
    }

    @Test
    void groundTaxiNeverExceedsMaxSpeedOrLeavesTheGround() {
        double vx = 0, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < 400; tick++) {
            BiplanePhysics.Result r = BiplanePhysics.step(vx, vy, vz, yaw, 1.0, 0.0, 0.0, true, true);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
            assertEquals(0.0, vy, 1e-9, "grounded taxi must not pick up vertical speed, tick " + tick);
            assertTrue(Math.hypot(vx, vz) <= BiplanePhysics.MAX_SPEED + 1e-9, "tick " + tick + ": exceeded MAX_SPEED while taxiing");
        }
    }

    @Test
    void reachesTakeoffSpeedWithinARealisticGroundRoll() {
        // 200 ticks (10 seconds) of full throttle is a generous ground roll; the pre-fix bug would have
        // cleared takeoff speed in a small fraction of a single tick instead.
        double speed = speedAfterTicks(200, 1.0);
        assertTrue(speed >= BiplanePhysics.TAKEOFF_SPEED, "should reach takeoff speed within 200 ticks of full throttle, only reached " + speed);
        assertTrue(speed <= BiplanePhysics.MAX_SPEED + 1e-9);
    }

    @Test
    void climbsOnceAirborneAboveTakeoffSpeedAndConvergesToABoundedRate() {
        double vx = BiplanePhysics.TAKEOFF_SPEED, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < 300; tick++) {
            BiplanePhysics.Result r = BiplanePhysics.step(vx, vy, vz, yaw, 1.0, 0.0, -90.0, true, false);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
            assertTrue(vy <= BiplanePhysics.MAX_CLIMB_SPEED + 1e-9, "tick " + tick + ": climb vy " + vy + " exceeded MAX_CLIMB_SPEED");
        }
        assertTrue(vy > BiplanePhysics.MAX_CLIMB_SPEED * 0.9, "climb should have converged near its cap, vy=" + vy);
    }

    @Test
    void levellingOutAfterAClimbDecaysVyBackToZero() {
        double vx = BiplanePhysics.TAKEOFF_SPEED, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < 200; tick++) {
            BiplanePhysics.Result r = BiplanePhysics.step(vx, vy, vz, yaw, 1.0, 0.0, -90.0, true, false);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
        }
        assertTrue(vy > 0.1, "sanity: should be climbing before levelling out, vy=" + vy);
        for (int tick = 0; tick < 200; tick++) {
            BiplanePhysics.Result r = BiplanePhysics.step(vx, vy, vz, yaw, 1.0, 0.0, 0.0, true, false);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
        }
        assertEquals(0.0, vy, 1e-6, "levelling out should decay vy back toward 0 rather than holding the old climb rate");
    }

    @Test
    void stallingBelowStallSpeedWhileAirborneSinksAtABoundedRate() {
        double vx = BiplanePhysics.STALL_SPEED * 0.5, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < 200; tick++) {
            BiplanePhysics.Result r = BiplanePhysics.step(vx, vy, vz, yaw, 0.0, 0.0, 0.0, true, false);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
            assertTrue(vy >= -BiplanePhysics.MAX_FALL_SPEED - 1e-9, "tick " + tick + ": stall sink rate " + vy + " exceeded the bounded MAX_FALL_SPEED");
        }
        assertTrue(vy < -BiplanePhysics.MAX_FALL_SPEED * 0.9, "a stalled plane should be sinking close to its bounded terminal rate, vy=" + vy);
    }

    @Test
    void landingZeroesVerticalSpeedImmediately() {
        BiplanePhysics.Result r = BiplanePhysics.step(BiplanePhysics.TAKEOFF_SPEED, -BiplanePhysics.MAX_FALL_SPEED, 0, 0, 0.0, 0.0, 0.0, true, true);
        assertEquals(0.0, r.vy(), 1e-9, "touching down (grounded) should snap vy to 0 immediately, not ramp down over several ticks");
    }
}
