package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** MINECRAFT-380 review fix: a pre-fix version of {@link ChopperPhysics} added an unbounded {@code vy +=
 * ...} every tick while nose-up/nose-down, with no cap and no way back to level once the pilot's view
 * returned to level. These tests check the fix: climb/dive rate converges to and never exceeds {@code
 * MAX_CLIMB_SPEED}, and vy actually decays back toward 0 once pitch returns to level (not left pinned at
 * whatever it had accumulated). */
class ChopperPhysicsTest {

    @Test
    void sustainedClimbConvergesAndNeverExceedsMaxClimbSpeed() {
        double vx = 0, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < 200; tick++) {
            ChopperPhysics.Result r = ChopperPhysics.step(vx, vy, vz, yaw, 0.0, 0.0, -90.0, true);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
            assertTrue(vy <= ChopperPhysics.MAX_CLIMB_SPEED + 1e-9,
                    "tick " + tick + ": vy " + vy + " exceeded MAX_CLIMB_SPEED " + ChopperPhysics.MAX_CLIMB_SPEED);
        }
        assertTrue(vy > ChopperPhysics.MAX_CLIMB_SPEED * 0.9, "climb should have converged near its cap, vy=" + vy);
    }

    @Test
    void levellingOutDecaysAccumulatedClimbBackToZero() {
        double vx = 0, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < 200; tick++) {
            ChopperPhysics.Result r = ChopperPhysics.step(vx, vy, vz, yaw, 0.0, 0.0, -90.0, true);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
        }
        assertTrue(vy > 0.1, "sanity: should be climbing before levelling out, vy=" + vy);
        for (int tick = 0; tick < 200; tick++) {
            ChopperPhysics.Result r = ChopperPhysics.step(vx, vy, vz, yaw, 0.0, 0.0, 0.0, true);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
        }
        assertEquals(0.0, vy, 1e-6, "levelling the view should bring vy back to 0, not leave the accumulated climb unchanged");
    }

    @Test
    void unpilotedChopperSinksAtABoundedRate() {
        double vy = ChopperPhysics.MAX_CLIMB_SPEED;
        for (int tick = 0; tick < 200; tick++) {
            ChopperPhysics.Result r = ChopperPhysics.step(0, vy, 0, 0, 0.0, 0.0, 0.0, false);
            vy = r.vy();
            assertTrue(vy >= -ChopperPhysics.MAX_FALL_SPEED - 1e-9,
                    "tick " + tick + ": unpiloted sink rate " + vy + " exceeded the bounded MAX_FALL_SPEED " + ChopperPhysics.MAX_FALL_SPEED);
        }
    }

    @Test
    void fullThrottleNeverExceedsMaxSpeed() {
        double vx = 0, vy = 0, vz = 0, yaw = 0;
        for (int tick = 0; tick < 300; tick++) {
            ChopperPhysics.Result r = ChopperPhysics.step(vx, vy, vz, yaw, 1.0, 0.0, 0.0, true);
            vx = r.vx();
            vz = r.vz();
            yaw = r.yawDeg();
            double speed = Math.hypot(vx, vz);
            assertTrue(speed <= ChopperPhysics.MAX_SPEED + 1e-9,
                    "tick " + tick + ": speed " + speed + " exceeded MAX_SPEED " + ChopperPhysics.MAX_SPEED);
        }
    }
}
