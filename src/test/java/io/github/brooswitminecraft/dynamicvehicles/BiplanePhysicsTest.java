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
            // Not exactly 0: grounded targets a small constant sink (BiplaneEntity's own post-move clamp
            // is what keeps the actually-visible vy at 0 once collision resolves it each tick) -- see
            // GROUND_GRAVITY's own javadoc for why pinning this to exactly 0 here broke vanilla's onGround
            // detection in an actual game-test run.
            assertEquals(-BiplanePhysics.GROUND_GRAVITY, vy, 1e-9, "grounded taxi should hold a constant small sink target, not drift, tick " + tick);
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
    void noseUpAtTakeoffSpeedLiftsOffEvenWhileStillReportedGrounded() {
        // Review 5563367455's second re-review (48012) caught this directly: onGround() reflects the
        // PREVIOUS tick's collision result, so the exact tick a taxiing plane first reaches TAKEOFF_SPEED
        // it is still reported grounded=true. An earlier version of this method checked grounded before
        // the takeoff transition, so the sink target always won and the plane could never leave the
        // ground at all, no matter how fast or how hard it pulled up. Nose-up (-90, straight up) at
        // exactly TAKEOFF_SPEED, still passed grounded=true, must now climb anyway.
        BiplanePhysics.Result r = BiplanePhysics.step(BiplanePhysics.TAKEOFF_SPEED, 0, 0, 0, 1.0, 0.0, -90.0, true, true);
        assertTrue(r.vy() > 0.0, "nose-up at takeoff speed should start climbing even while grounded still reads true, vy=" + r.vy());
    }

    @Test
    void fullTaxiToLiftoffSequenceClimbsOnlyOncePastTakeoffSpeedAndNoseUp() {
        double vx = 0, vy = 0, vz = 0, yaw = 0;
        // Ground roll, nose level: must not climb even once it clears takeoff speed, since the pilot
        // never pulled up (grounded stays true the whole way, as a real taxi run would report it).
        for (int tick = 0; tick < 100; tick++) {
            BiplanePhysics.Result r = BiplanePhysics.step(vx, vy, vz, yaw, 1.0, 0.0, 0.0, true, true);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
        }
        assertTrue(Math.hypot(vx, vz) >= BiplanePhysics.TAKEOFF_SPEED, "sanity: should have reached takeoff speed by tick 100");
        assertEquals(-BiplanePhysics.GROUND_GRAVITY, vy, 1e-9, "fast but nose-level should still stay grounded, not climb on its own");

        // Now pull the nose up, still "grounded" for this transitional tick: vy must start moving toward a
        // climb (VERTICAL_ACCEL rate-limits how fast it can flip from the grounded sink to climbing, so
        // one tick alone is not enough to go positive -- see noseUpAtTakeoffSpeedLiftsOffEvenWhileStillReportedGrounded
        // for that single-tick case starting from vy=0 instead of the grounded sink's -GROUND_GRAVITY).
        double vyBeforePullUp = vy;
        for (int tick = 0; tick < 10; tick++) {
            BiplanePhysics.Result r = BiplanePhysics.step(vx, vy, vz, yaw, 1.0, 0.0, -90.0, true, true);
            vx = r.vx();
            vy = r.vy();
            vz = r.vz();
            yaw = r.yawDeg();
        }
        assertTrue(vy > vyBeforePullUp, "pulling the nose up at takeoff speed should move vy toward a climb, started at " + vyBeforePullUp + " now " + vy);
        assertTrue(vy > 0.0, "pulling the nose up at takeoff speed should climb within 10 ticks, vy=" + vy);
    }

    @Test
    void landingSnapsToAGroundedSinkInsteadOfKeepingTheOldAscentOrDescent() {
        // Grounded targets a small constant sink (GROUND_GRAVITY), not exactly 0: a real game-test run
        // showed vanilla's own onGround flag needs a per-tick downward attempt to stay confirmed (see
        // BiplanePhysics' own javadoc on GROUND_GRAVITY). BiplaneEntity's post-move clamp is what zeroes
        // the actually-visible vy once collision has resolved that attempt each tick.
        BiplanePhysics.Result r = BiplanePhysics.step(BiplanePhysics.TAKEOFF_SPEED, -BiplanePhysics.MAX_FALL_SPEED, 0, 0, 0.0, 0.0, 0.0, true, true);
        assertEquals(-BiplanePhysics.GROUND_GRAVITY, r.vy(), 1e-9,
                "touching down (grounded) should snap vy to the grounded sink target immediately, not keep the old descent rate");
    }
}
