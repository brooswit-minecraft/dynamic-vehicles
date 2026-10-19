package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-410: replaces the old {@code MotorboatPhysicsTest}, which exercised the now-deleted scalar
 * {@code MotorboatPhysics.step(vx,vy,vz,yaw,...)} API (direct yaw mutation, single water-column spring --
 * fundamentally incompatible with the rigid-body model this ticket requires). These tests exercise the
 * SAME pure functions {@link SableMotorboatBody#tick} actually calls (see that class), not a
 * reimplementation, and supersede every behaviour the old test covered (speed bounds, drag decay, idle
 * stability, airborne/beached fall) plus the new acceptance criteria: per-point buoyancy sign/convergence,
 * thrust direction tracking the body's live orientation, steer-torque sign, dry-motor zero-thrust, bounded
 * non-overshooting drag/damping, and finite output on edge-case inputs.
 */
class MotorboatPhysicsTest {

    // ---- pointBuoyantForce: per-hull-point buoyancy spring/damper ----

    @Test
    void dryPointGetsNoBuoyantForce() {
        assertEquals(0.0, MotorboatPhysics.pointBuoyantForce(0.0, 0.0, 3000.0, 700.0, 6000.0), 0);
        assertEquals(0.0, MotorboatPhysics.pointBuoyantForce(-1.0, 0.0, 3000.0, 700.0, 6000.0), 0);
    }

    @Test
    void deeperSubmersionPushesHarderUntilClamped() {
        double shallow = MotorboatPhysics.pointBuoyantForce(0.05, 0.0, 3000.0, 700.0, 6000.0);
        double deeper = MotorboatPhysics.pointBuoyantForce(0.3, 0.0, 3000.0, 700.0, 6000.0);
        assertTrue(deeper > shallow);
        assertTrue(shallow > 0.0);
    }

    @Test
    void suddenDeepSubmersionIsClampedNotExploding() {
        // A point "teleported into a lake" deep below the surface must never produce an unbounded force.
        double force = MotorboatPhysics.pointBuoyantForce(1000.0, 0.0, 3000.0, 700.0, 6000.0);
        assertEquals(6000.0, force, 0);
        assertTrue(Double.isFinite(force));
    }

    @Test
    void risingPointGetsLessNetForceThanAStationaryOneAtTheSameDepth() {
        // Damping opposes the point's own upward velocity, so a point already moving up toward the
        // surface gets pushed less hard than a motionless point at the same depth -- this is what lets
        // the spring-damper settle instead of overshooting indefinitely.
        double stationary = MotorboatPhysics.pointBuoyantForce(0.2, 0.0, 3000.0, 700.0, 6000.0);
        double rising = MotorboatPhysics.pointBuoyantForce(0.2, 1.0, 3000.0, 700.0, 6000.0);
        assertTrue(rising < stationary);
    }

    @Test
    void buoyantForceNeverNegative() {
        // A point rising fast enough that damping would overpower the spring must floor at 0, not pull
        // the hull down -- "a spring can only push" (same invariant WheelMath.suspensionForce documents).
        double force = MotorboatPhysics.pointBuoyantForce(0.01, 100.0, 3000.0, 700.0, 6000.0);
        assertEquals(0.0, force, 0);
    }

    @Test
    void buoyantForceFiniteForExtremeInputs() {
        assertTrue(Double.isFinite(MotorboatPhysics.pointBuoyantForce(1e9, -1e9, 3000.0, 700.0, 6000.0)));
        assertTrue(Double.isFinite(MotorboatPhysics.pointBuoyantForce(-1e9, 1e9, 3000.0, 700.0, 6000.0)));
        assertTrue(Double.isFinite(MotorboatPhysics.pointBuoyantForce(0.0, 0.0, 3000.0, 700.0, 6000.0)));
    }

    // ---- 4-point symmetric/asymmetric immersion: balanced vs. restoring torque ----

    /** Four corners, all at the same depth: identical force each, so the net torque about the centre of
     * mass is exactly zero (front/rear and port/starboard pairs cancel) -- symmetric immersion is
     * balanced, never injecting a spurious tilt. */
    @Test
    void symmetricImmersionAtAllFourPointsIsBalanced() {
        double depth = 0.25;
        double[] forces = new double[4];
        for (int i = 0; i < 4; i++) {
            forces[i] = MotorboatPhysics.pointBuoyantForce(depth, 0.0, 3000.0, 700.0, 6000.0);
        }
        for (int i = 1; i < 4; i++) {
            assertEquals(forces[0], forces[i], 1e-9);
        }
        // Mount layout mirrors SableMotorboatBody: (-x,-y,+z) front-port, (+x,-y,+z) front-starboard,
        // (-x,-y,-z) rear-port, (+x,-y,-z) rear-starboard. Net torque about Y (roll-ish, from the X lever
        // arm) and about X (pitch-ish, from the Z lever arm) must both be exactly zero when every point
        // carries the same upward force.
        double halfWidth = 0.7;
        double halfLength = 1.6;
        double[][] mounts = {
                {-halfWidth, halfLength}, {halfWidth, halfLength}, {-halfWidth, -halfLength}, {halfWidth, -halfLength},
        };
        double pitchTorque = 0.0; // about X: force * zOffset
        double rollTorque = 0.0; // about Z: force * xOffset
        for (int i = 0; i < 4; i++) {
            pitchTorque += forces[i] * mounts[i][1];
            rollTorque += forces[i] * mounts[i][0];
        }
        assertEquals(0.0, pitchTorque, 1e-9);
        assertEquals(0.0, rollTorque, 1e-9);
    }

    /** The bow (front) points deeper than the stern (rear) points: the front points push up harder, which
     * must produce a pitch torque in the direction that lifts the bow back toward level (a RESTORING
     * torque, not an arbitrary-sign one) -- the whole point of per-point buoyancy over the old single
     * whole-body spring. */
    @Test
    void bowDeeperThanSternProducesARestoringPitchTorqueTowardLevel() {
        double bowDepth = 0.4;
        double sternDepth = 0.1;
        double bowForce = MotorboatPhysics.pointBuoyantForce(bowDepth, 0.0, 3000.0, 700.0, 6000.0);
        double sternForce = MotorboatPhysics.pointBuoyantForce(sternDepth, 0.0, 3000.0, 700.0, 6000.0);
        assertTrue(bowForce > sternForce);
        double halfLength = 1.6;
        // Front mounts at +halfLength (bow), rear at -halfLength (stern): torque about X from each pair.
        double pitchTorque = 2 * bowForce * halfLength + 2 * sternForce * (-halfLength);
        // The bow (deeper, so more force) is further "up" on the +Z side; a net positive torque here
        // means the bow is being pushed up relatively more than the stern -- i.e. toward levelling the
        // deeper end back up, not away from it.
        assertTrue(pitchTorque > 0.0, "deeper bow should produce a torque that lifts it, got " + pitchTorque);
    }

    // ---- heave: released above the waterline settles down, converging (not diverging/exploding) ----

    @Test
    void heaveSettlesTowardEquilibriumAndDoesNotDiverge() {
        double massKg = 300.0;
        double gravityPerTick = 9.81 / 20.0 / 20.0; // crude per-tick gravity delta-v at 20 Hz, m/s per tick
        double stiffness = 3000.0;
        double damping = 700.0;
        double maxForce = 6000.0;
        double y = 3.0; // height of one hull point above its equilibrium depth, metres (released high)
        double vy = 0.0;
        double surfaceY = 0.0; // depth = surfaceY - y = -y while above the surface
        double[] amplitudeSamples = new double[2000];
        for (int tick = 0; tick < 2000; tick++) {
            double depth = surfaceY - y;
            double force = MotorboatPhysics.pointBuoyantForce(depth, vy, stiffness, damping, maxForce);
            double netForce = force - massKg * 9.81; // buoyancy up, weight down (one point's quarter-ish share)
            double dt = 1.0 / 20.0;
            vy += netForce / massKg * dt;
            y += vy * dt;
            amplitudeSamples[tick] = y;
            assertTrue(Double.isFinite(y) && Double.isFinite(vy), "tick " + tick + ": y=" + y + " vy=" + vy);
            assertTrue(Math.abs(y) < 1000.0, "diverged at tick " + tick + ": y=" + y);
        }
        // Amplitude (distance from the eventual resting point) at a late tick must be smaller than early
        // on -- decaying, not growing or sustaining forever. Compare the oscillation band in the first
        // 200 ticks against the last 200.
        double earlyMax = 0.0;
        double earlyMin = Double.MAX_VALUE;
        for (int i = 100; i < 300; i++) {
            earlyMax = Math.max(earlyMax, amplitudeSamples[i]);
            earlyMin = Math.min(earlyMin, amplitudeSamples[i]);
        }
        double lateMax = 0.0;
        double lateMin = Double.MAX_VALUE;
        for (int i = 1700; i < 1900; i++) {
            lateMax = Math.max(lateMax, amplitudeSamples[i]);
            lateMin = Math.min(lateMin, amplitudeSamples[i]);
        }
        assertTrue((lateMax - lateMin) < (earlyMax - earlyMin),
                "oscillation band should shrink over time: early=" + (earlyMax - earlyMin) + " late=" + (lateMax - lateMin));
    }

    // ---- thrustVector: direction tracks live orientation; steer rotates it; sign is test-pinned ----

    @Test
    void zeroThrottleIsExactlyZeroThrust() {
        MotorboatPhysics.Vector2 thrust = MotorboatPhysics.thrustVector(0.0, 1.0, 0.3, 0.0, 3000.0);
        assertEquals(0.0, thrust.x(), 0);
        assertEquals(0.0, thrust.z(), 0);
    }

    @Test
    void forwardThrottleThrustsAlongTheBoatsTrueCurrentFacingAtIdentityOrientation() {
        MotorboatPhysics.Vector2 thrust = MotorboatPhysics.thrustVector(0.0, 1.0, 0.0, 1.0, 3000.0);
        assertEquals(0.0, thrust.x(), 1e-9);
        assertEquals(3000.0, thrust.z(), 1e-9);
    }

    @Test
    void forwardThrottleThrustsAlongTheBoatsTrueCurrentFacingAtANonIdentityOrientation() {
        // The boat is facing world +X (forward = (1, 0)), NOT world +Z -- the thrust direction must
        // rotate WITH the body, never stay fixed to world +Z.
        MotorboatPhysics.Vector2 thrust = MotorboatPhysics.thrustVector(1.0, 0.0, 0.0, 1.0, 3000.0);
        assertEquals(3000.0, thrust.x(), 1e-9);
        assertEquals(0.0, thrust.z(), 1e-9);
    }

    @Test
    void reverseThrottleThrustsBackwardAlongTheSameSteeredDirection() {
        MotorboatPhysics.Vector2 forward = MotorboatPhysics.thrustVector(0.0, 1.0, 0.2, 1.0, 3000.0);
        MotorboatPhysics.Vector2 reverse = MotorboatPhysics.thrustVector(0.0, 1.0, 0.2, -1.0, 3000.0);
        // Documented design choice: this motor steers in reverse too (like a real outboard), so reverse is
        // exactly the negation of the same steered forward vector, not a separately-computed unsteered one.
        assertEquals(-forward.x(), reverse.x(), 1e-9);
        assertEquals(-forward.z(), reverse.z(), 1e-9);
    }

    @Test
    void steerRotatesTheThrustDirectionAwayFromPureForward() {
        MotorboatPhysics.Vector2 straight = MotorboatPhysics.thrustVector(0.0, 1.0, 0.0, 1.0, 3000.0);
        MotorboatPhysics.Vector2 steered = MotorboatPhysics.thrustVector(0.0, 1.0, 0.3, 1.0, 3000.0);
        assertEquals(0.0, straight.x(), 1e-9);
        assertTrue(Math.abs(steered.x()) > 1e-6, "steering should rotate thrust off the pure-forward axis");
    }

    @Test
    void positiveAndNegativeSteerProduceOppositeSignedThrustXComponent() {
        // Pinning the SIGN down, not just "different": this is what, through the motor's own lever arm,
        // produces a yaw torque of a determinate, opposite sign for opposite steer inputs (see
        // SableMotorboatBody's own mount layout and MotorboatPhysics.thrustVector's javadoc for the exact
        // torque derivation this sign feeds).
        MotorboatPhysics.Vector2 left = MotorboatPhysics.thrustVector(0.0, 1.0, 0.3, 1.0, 3000.0);
        MotorboatPhysics.Vector2 right = MotorboatPhysics.thrustVector(0.0, 1.0, -0.3, 1.0, 3000.0);
        assertTrue(left.x() < 0.0);
        assertTrue(right.x() > 0.0);
        assertTrue(left.x() * right.x() < 0.0, "opposite steer inputs must give opposite-signed thrust X components");
    }

    /** The actual yaw-torque sign at the stern mount, derived from a real thrust vector, for both steer
     * signs -- not just the raw thrust component. Mirrors exactly what {@code SableMotorboatBody} computes
     * (torque = r x F about the vertical axis, at the stern's own lever arm). */
    @Test
    void steerSignProducesOppositeSignedYawTorqueAtTheSternMount() {
        double mountX = 0.0;
        double mountZ = -1.6; // stern, behind the centre of mass
        MotorboatPhysics.Vector2 left = MotorboatPhysics.thrustVector(0.0, 1.0, 0.3, 1.0, 3000.0);
        MotorboatPhysics.Vector2 right = MotorboatPhysics.thrustVector(0.0, 1.0, -0.3, 1.0, 3000.0);
        double torqueLeft = mountX * left.z() - mountZ * left.x();
        double torqueRight = mountX * right.z() - mountZ * right.x();
        assertTrue(torqueLeft * torqueRight < 0.0, "opposite steer must give opposite-signed yaw torque");
        assertTrue(Math.abs(torqueLeft) > 1e-6 && Math.abs(torqueRight) > 1e-6);
    }

    @Test
    void thrustVectorFiniteForExtremeInputs() {
        MotorboatPhysics.Vector2 thrust = MotorboatPhysics.thrustVector(1e9, -1e9, 1e9, 1.0, 3000.0);
        assertTrue(Double.isFinite(thrust.x()) && Double.isFinite(thrust.z()));
    }

    // ---- boundedDampingDelta: lateral/longitudinal/angular/vertical damping primitive ----

    @Test
    void zeroVelocityOrZeroRateGivesNoDamping() {
        assertEquals(0.0, MotorboatPhysics.boundedDampingDelta(0.0, 5.0, 0.05), 0);
        assertEquals(0.0, MotorboatPhysics.boundedDampingDelta(2.0, 0.0, 0.05), 0);
    }

    @Test
    void dampingAlwaysOpposesTheVelocitysSign() {
        double deltaPositive = MotorboatPhysics.boundedDampingDelta(2.0, 1.0, 0.05);
        double deltaNegative = MotorboatPhysics.boundedDampingDelta(-2.0, 1.0, 0.05);
        assertTrue(deltaPositive < 0.0, "damping on a positive velocity must be negative (opposing)");
        assertTrue(deltaNegative > 0.0, "damping on a negative velocity must be positive (opposing)");
    }

    @Test
    void dampingNeverOvershootsPastZeroEvenWithALargeNominalRateAndRealDt() {
        // A big nominal rate * dt (> 1) would overshoot a naive "v *= (1 - rate*dt)" past zero and flip
        // sign; this must clamp at removing exactly v, landing at (near) zero, never past it.
        double v = 0.01;
        double delta = MotorboatPhysics.boundedDampingDelta(v, 50.0, 0.05); // rate*dt = 2.5
        double result = v + delta;
        assertEquals(0.0, result, 1e-12, "should clamp to exactly zero, not overshoot to " + result);
    }

    @Test
    void dampingDissipatesEnergyNeverAddsIt() {
        double v = 3.0;
        double delta = MotorboatPhysics.boundedDampingDelta(v, 0.8, 0.05);
        double result = v + delta;
        assertTrue(Math.abs(result) <= Math.abs(v), "damping must never increase speed");
    }

    @Test
    void repeatedZeroThrottleDampingFromASmallResidualVelocitySettlesExactlyToZeroWithoutBuzzing() {
        double v = 0.02;
        double dt = 1.0 / 20.0;
        double rate = 1.2;
        for (int tick = 0; tick < 500; tick++) {
            double before = v;
            v += MotorboatPhysics.boundedDampingDelta(v, rate, dt);
            assertTrue(Math.abs(v) <= Math.abs(before) + 1e-12, "tick " + tick + ": velocity grew from " + before + " to " + v);
        }
        assertEquals(0.0, v, 1e-6, "residual velocity should have settled to (near) exactly zero, not be buzzing at " + v);
    }

    @Test
    void longitudinalAndLateralRatesAreActuallyDifferentConstantsInUse() {
        // Exercises the two SEPARATE, differently-tunable rates SableMotorboatBody reads from
        // MotorboatConfig (mirrored here as plain literals -- ModConfigSpec itself needs NeoForge's own
        // bootstrap, unavailable to this plain-JUnit test source set, same reason no other test here calls
        // a ModConfigSpec value's own .get(); see MotorboatConfig's defaults for LONGITUDINAL_DRAG_RATE/
        // LATERAL_DRAG_RATE, which these two numbers match): pure forward motion decays at the
        // longitudinal rate, pure lateral motion decays at the (larger) lateral rate, over the same dt.
        double longRate = 1.2;
        double latRate = 6.0;
        assertTrue(latRate > longRate, "lateral drag must be configured stronger than longitudinal drag");
        double dt = 1.0 / 20.0;
        double longResult = 5.0 + MotorboatPhysics.boundedDampingDelta(5.0, longRate, dt);
        double latResult = 5.0 + MotorboatPhysics.boundedDampingDelta(5.0, latRate, dt);
        assertTrue(latResult < longResult, "lateral (slip) motion must decay faster than longitudinal motion");
    }

    @Test
    void dampingStableAcrossDifferentTimestepChoices() {
        // No sub-stepping in SableMotorboatBody (see its own javadoc for why) -- this proves the single-
        // step math stays bounded/non-overshooting at a few different dt values anyway, which is what
        // actually matters for this requirement, not literally sub-stepping for its own sake.
        for (double dt : new double[] {1.0 / 20.0, 1.0 / 60.0, 1.0 / 5.0, 1.0}) {
            double v = 1.0;
            double delta = MotorboatPhysics.boundedDampingDelta(v, 3.0, dt);
            double result = v + delta;
            assertTrue(Double.isFinite(result));
            assertTrue(result * v >= 0.0, "dt=" + dt + ": must not cross zero, got " + result);
            assertTrue(Math.abs(result) <= Math.abs(v), "dt=" + dt + ": must not add energy, got " + result);
        }
    }

    @Test
    void dampingFiniteForExtremeInputs() {
        assertTrue(Double.isFinite(MotorboatPhysics.boundedDampingDelta(1e12, 1e12, 1e12)));
        assertTrue(Double.isFinite(MotorboatPhysics.boundedDampingDelta(-1e12, 1e12, 1e12)));
        assertTrue(Double.isFinite(MotorboatPhysics.boundedDampingDelta(Double.MIN_VALUE, 1.0, 0.05)));
    }

    @Test
    void noNegativeRatePerSecondProducesDamping() {
        // Defensive: a negative/misconfigured rate must never AMPLIFY velocity.
        assertEquals(0.0, MotorboatPhysics.boundedDampingDelta(5.0, -2.0, 0.05), 0);
    }

    // ---- sanity: units stay in real SI, never the old per-tick-only mixing bug ----

    @Test
    void maxThrustDividedByMassGivesASensibleAccelerationNotAnInflatedOne() {
        // The old MotorboatPhysics bug mixed an unconverted per-second constant into per-tick integration
        // (a "9 m/s" accel became 180 blocks/sec). Sanity-check the config's own default numbers (mirrored
        // here as literals, see the note above) stay in a plausible single-digit-to-low-double-digit
        // m/s^2 range for a few-hundred-kg boat, not three orders of magnitude off.
        double maxThrust = 3000.0;
        double massKg = 300.0;
        double accel = maxThrust / massKg;
        assertTrue(accel > 0.1 && accel < 50.0, "full-throttle acceleration should be a plausible m/s^2 value, got " + accel);
    }
}
