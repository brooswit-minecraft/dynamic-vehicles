package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DrivetrainMathTest {
    /** One server tick, seconds -- what SableCarBody's engine/clutch block actually integrates at. */
    private static final double TICK_DT = 1.0 / 20.0;
    /** The shared stock engine/clutch settings every roster vehicle runs -- what these tests exercise
     * unless they are specifically checking a per-car override. */
    private static final VehicleSpec.DrivetrainCoupling STOCK = VehicleSpec.DrivetrainCoupling.STOCK;

    @Test
    void manualRatiosDecreaseFromD1ToD5() {
        for (int i = 1; i < DrivetrainMath.GEAR_RATIOS.length; i++) {
            assertTrue(DrivetrainMath.ratioFor(i) < DrivetrainMath.ratioFor(i - 1),
                    "each higher gear must have a lower (taller) ratio than the one before it");
        }
    }

    @Test
    void topGearHasAForceMultiplierOfExactlyOne() {
        // MINECRAFT-401: the existing per-wheel drive-force constants were tuned at top gear, so top
        // gear's own multiplier must be exactly 1.0 -- unchanged behaviour for anyone who never shifts.
        int topGear = DrivetrainMath.GEAR_RATIOS.length - 1;
        assertEquals(1.0, DrivetrainMath.forceMultiplier(DrivetrainMath.ratioFor(topGear)), 1e-12);
    }

    @Test
    void lowerGearsMultiplyDriveForceUp() {
        assertTrue(DrivetrainMath.forceMultiplier(DrivetrainMath.ratioFor(0)) > DrivetrainMath.forceMultiplier(DrivetrainMath.ratioFor(1)));
        assertTrue(DrivetrainMath.forceMultiplier(DrivetrainMath.ratioFor(0)) > 1.0, "D1 must deliver more force than top gear");
    }

    @Test
    void engineRpmIsClampedToIdleAndTheLimiter() {
        assertEquals(DrivetrainMath.IDLE_RPM, DrivetrainMath.engineRpm(0.0, DrivetrainMath.ratioFor(0)), 1e-9,
                "a stationary driven wheel must still read as idling, not stalled");
        assertEquals(DrivetrainMath.LIMITER_RPM, DrivetrainMath.engineRpm(10_000.0, DrivetrainMath.ratioFor(0)), 1e-9,
                "a wheel spinning far past what the ratio can carry must clamp at the limiter, not exceed it");
    }

    @Test
    void engineRpmRisesWithWheelSpeedAndWithALowerGearAtTheSameSpeed() {
        double atLow = DrivetrainMath.engineRpm(5.0, DrivetrainMath.ratioFor(0));
        double atHigh = DrivetrainMath.engineRpm(10.0, DrivetrainMath.ratioFor(0));
        assertTrue(atHigh > atLow, "faster wheel speed must mean higher RPM at a fixed ratio");
        // A wheel speed comfortably past where either gear's RPM clamps to idle, so the comparison below
        // actually exercises the ratio multiplication rather than two numbers both floored at IDLE_RPM.
        double lowGearRpm = DrivetrainMath.engineRpm(30.0, DrivetrainMath.ratioFor(0));
        double topGearRpm = DrivetrainMath.engineRpm(30.0, DrivetrainMath.ratioFor(4));
        assertTrue(lowGearRpm > topGearRpm, "the same wheel speed must read as higher RPM in a lower (higher-ratio) gear");
    }

    @Test
    void freeRevRpmFollowsThrottleAloneBetweenIdleAndTheLimiter() {
        assertEquals(DrivetrainMath.IDLE_RPM, DrivetrainMath.freeRevRpm(0.0), 1e-9);
        assertEquals(DrivetrainMath.LIMITER_RPM, DrivetrainMath.freeRevRpm(1.0), 1e-9);
        assertTrue(DrivetrainMath.freeRevRpm(0.5) > DrivetrainMath.IDLE_RPM && DrivetrainMath.freeRevRpm(0.5) < DrivetrainMath.LIMITER_RPM);
    }

    @Test
    void freeRevRpmNeverRevsFromTheBrakeInNeutral() {
        // MINECRAFT-401 review: SableCarBody passes the car's SIGNED throttle (accelerator minus brake)
        // straight through. Full brake with no accelerator is a negative value and must read as idle,
        // never as a demand to rev toward the limiter.
        assertEquals(DrivetrainMath.IDLE_RPM, DrivetrainMath.freeRevRpm(-1.0), 1e-9,
                "full brake alone in neutral must not free-rev the engine");
        assertEquals(DrivetrainMath.IDLE_RPM, DrivetrainMath.freeRevRpm(-0.3), 1e-9);
    }

    @Test
    void rawRpmIsNotClampedButEngineRpmIs() {
        double huge = DrivetrainMath.rawRpm(10_000.0, DrivetrainMath.ratioFor(0));
        assertTrue(huge > DrivetrainMath.LIMITER_RPM, "rawRpm must keep climbing past the limiter, unclamped");
        assertEquals(DrivetrainMath.LIMITER_RPM, DrivetrainMath.engineRpm(10_000.0, DrivetrainMath.ratioFor(0)), 1e-9,
                "engineRpm's own display clamp must still cap at the limiter");
    }

    @Test
    void limiterTorqueFactorTapersToZeroAtAndPastTheLimiterButNotBelowIt() {
        assertEquals(1.0, DrivetrainMath.limiterTorqueFactor(DrivetrainMath.IDLE_RPM), 1e-9);
        assertEquals(0.0, DrivetrainMath.limiterTorqueFactor(DrivetrainMath.LIMITER_RPM), 1e-9);
        assertEquals(0.0, DrivetrainMath.limiterTorqueFactor(DrivetrainMath.LIMITER_RPM + 5000.0), 1e-9,
                "torque must stay cut off well past the limiter, not resume");
        double midTaper = DrivetrainMath.limiterTorqueFactor(DrivetrainMath.LIMITER_RPM - DrivetrainMath.LIMITER_TAPER_RPM / 2.0);
        assertTrue(midTaper > 0.0 && midTaper < 1.0, "mid-taper factor must be strictly between 0 and 1");
    }

    @Test
    void automaticShiftLogicReadsUnclampedRpmSoAPinnedWheelStillUpshifts() {
        // A wheel spinning so fast that D1's own RAW rpm is already an order of magnitude past the
        // limiter must still trigger an upshift -- it must not look identical to "merely at the limiter"
        // the way reading the clamped engineRpm would.
        int shifted = DrivetrainMath.automaticGearIndex(0, 50_000.0);
        assertTrue(shifted > 0, "an absurdly fast driven wheel in D1 must upshift, not stay pinned");
    }

    @Test
    void automaticUpshiftsWhenRpmWouldExceedTheUpshiftThreshold() {
        // A wheel speed whose current-gear RPM is at the limiter must upshift at least one gear.
        double fastWheelSpeed = DrivetrainMath.LIMITER_RPM * 2.0 * Math.PI / 60.0 / (DrivetrainMath.ratioFor(0) * DrivetrainMath.FINAL_DRIVE);
        int next = DrivetrainMath.automaticGearIndex(0, fastWheelSpeed);
        assertTrue(next > 0, "pinned at the limiter in D1 must upshift");
    }

    @Test
    void automaticDownshiftsWhenRpmDropsBelowTheDownshiftThreshold() {
        int next = DrivetrainMath.automaticGearIndex(DrivetrainMath.GEAR_RATIOS.length - 1, 0.0);
        assertEquals(0, next, "a stationary driven wheel must downshift all the way back to D1");
    }

    @Test
    void automaticNeverShiftsAboveTopGearOrBelowD1() {
        assertEquals(DrivetrainMath.GEAR_RATIOS.length - 1,
                DrivetrainMath.automaticGearIndex(DrivetrainMath.GEAR_RATIOS.length - 1, 1_000.0));
        assertEquals(0, DrivetrainMath.automaticGearIndex(0, 0.0));
    }

    @Test
    void automaticHasHysteresisSoAUpshiftDoesNotImmediatelyDownshiftBack() {
        // At exactly the upshift point in the OLD gear, the NEW gear's own RPM must already be comfortably
        // below the downshift threshold, or the next call would shift straight back down.
        int shifted = DrivetrainMath.automaticGearIndex(0, 1_000.0);
        if (shifted > 0) {
            double rpmInNewGear = DrivetrainMath.engineRpm(1_000.0, DrivetrainMath.ratioFor(shifted));
            assertTrue(rpmInNewGear > DrivetrainMath.DOWNSHIFT_RPM,
                    "a gear just shifted into must not immediately read as needing a downshift");
        }
    }

    @Test
    void clampRpmMatchesEngineRpmsOwnClamp() {
        assertEquals(DrivetrainMath.IDLE_RPM, DrivetrainMath.clampRpm(0.0), 1e-9);
        assertEquals(DrivetrainMath.LIMITER_RPM, DrivetrainMath.clampRpm(50_000.0), 1e-9);
        assertEquals(3000.0, DrivetrainMath.clampRpm(3000.0), 1e-9);
    }

    @Test
    void boundedSpinTowardSnapClampsALargeInstantSnapToTheFrictionTorqueLimit() {
        // A wheel spinning fast (50 rad/s) with the limiter just cutting torque so WheelMath.spinRate
        // wants to snap it straight to a near-stationary ground speed (2 rad/s) -- that 48 rad/s jump must
        // be bounded by how fast the available friction torque could actually despin it, not applied whole.
        double frictionForce = 3000.0; // N
        double wheelRadius = 0.35;
        double wheelInertia = 1.2;
        double dt = 0.05;
        double maxChange = frictionForce * wheelRadius * dt / wheelInertia;
        double result = DrivetrainMath.boundedSpinTowardSnap(50.0, 2.0, frictionForce, wheelRadius, wheelInertia, dt);
        assertEquals(50.0 - maxChange, result, 1e-9, "the snap must be clamped to exactly the friction-torque-bounded change");
        assertTrue(result > 2.0, "a single bounded step must not reach all the way to the snapped target when the jump is larger than the bound");
    }

    @Test
    void boundedSpinTowardSnapPassesThroughASmallSnapUnclamped() {
        // When the requested snap is already within what friction could achieve this step, the result
        // must be the snapped value exactly -- this must not introduce extra lag for the ordinary small
        // corrections WheelMath.spinRate's own contract was built for.
        double result = DrivetrainMath.boundedSpinTowardSnap(10.0, 9.99, 3000.0, 0.35, 1.2, 0.05);
        assertEquals(9.99, result, 1e-9);
    }

    @Test
    void boundedSpinTowardSnapWorksInEitherDirection() {
        double frictionForce = 3000.0;
        double wheelRadius = 0.35;
        double wheelInertia = 1.2;
        double dt = 0.05;
        double maxChange = frictionForce * wheelRadius * dt / wheelInertia;
        // Snapping DOWN from a fast spin (covered above) and snapping UP (e.g. a reversing wheel) must be
        // bounded symmetrically.
        double resultUp = DrivetrainMath.boundedSpinTowardSnap(-50.0, -2.0, frictionForce, wheelRadius, wheelInertia, dt);
        assertEquals(-50.0 + maxChange, resultUp, 1e-9);
    }

    @Test
    void boundedSpinTowardSnapIgnoresTheSignOfFrictionForce() {
        // Only the MAGNITUDE of the available friction force bounds the rate -- a caller must not need to
        // pre-sign it relative to the spin direction.
        double a = DrivetrainMath.boundedSpinTowardSnap(50.0, 2.0, 3000.0, 0.35, 1.2, 0.05);
        double b = DrivetrainMath.boundedSpinTowardSnap(50.0, 2.0, -3000.0, 0.35, 1.2, 0.05);
        assertEquals(a, b, 1e-9);
    }

    /** {@code SableCarBody}'s own reaction-impulse formula for the recovery branch, reproduced here (not
     * imported - it is inline at the call site, not a named method) so this test exercises the exact same
     * arithmetic rather than a paraphrase of it. */
    private static double reactionImpulse(double priorSpin, double recoveredSpin, double wheelRadius, double wheelInertia) {
        return -(recoveredSpin - priorSpin) * wheelInertia / wheelRadius;
    }

    /**
     * Review, round 7: the empirical per-tick GameTest bound does not by itself prove the recovery
     * reaction's own angular-impulse/chassis-reaction accounting is integrated correctly across
     * sub-steps - this does, the same way {@code WheelMathTest}'s own
     * {@code repeatedSubStepsAtTheSameTotalDtIntegrateToTheSameSpinRegardlessOfSubStepCount} already
     * proves it for {@link WheelMath#spinRate}'s excessForce branch: for a CONSTANT friction headroom and
     * ground-speed target (an idealization that test makes too - a live tick's own force/headroom vary
     * sub-step to sub-step, which is exactly why the GameTest bound above is empirical rather than
     * derived), splitting the SAME total dt into N sub-steps and summing each one's own
     * {@link DrivetrainMath#boundedSpinTowardSnap} + reaction-impulse pair must total the SAME net spin
     * change and reaction impulse as one sub-step at the full dt - never N times either, and never zero.
     */
    @Test
    void recoveryReactionImpulseIntegratesTheSameTotalRegardlessOfSubStepCount() {
        double frictionHeadroom = 2000.0;
        double wheelRadius = 0.35;
        double wheelInertia = 1.2;
        double groundSpin = 5.0;
        double dt = 0.05;
        double priorSpin = 60.0;

        double snappedOneStep = WheelMath.spinRate(priorSpin, wheelRadius, groundSpin * wheelRadius, 0.0, wheelInertia, dt);
        double recoveredOneStep = DrivetrainMath.boundedSpinTowardSnap(priorSpin, snappedOneStep, frictionHeadroom,
                wheelRadius, wheelInertia, dt);
        double oneStepImpulse = reactionImpulse(priorSpin, recoveredOneStep, wheelRadius, wheelInertia);
        assertTrue(recoveredOneStep < priorSpin, "test setup invalid -- the single step must actually recover some spin");

        for (int n = 2; n <= 4; n++) {
            double subDt = dt / n;
            double spin = priorSpin;
            double totalImpulse = 0.0;
            for (int sub = 0; sub < n; sub++) {
                double snapped = WheelMath.spinRate(spin, wheelRadius, groundSpin * wheelRadius, 0.0, wheelInertia, subDt);
                double recovered = DrivetrainMath.boundedSpinTowardSnap(spin, snapped, frictionHeadroom, wheelRadius, wheelInertia, subDt);
                totalImpulse += reactionImpulse(spin, recovered, wheelRadius, wheelInertia);
                spin = recovered;
            }
            assertEquals(recoveredOneStep, spin, 1e-9, "N=" + n + " must recover to the same spin as N=1 over the same total dt");
            assertEquals(oneStepImpulse, totalImpulse, 1e-9, "N=" + n + "'s summed reaction impulse must match N=1's, not N times it or a fraction of it");
        }
    }

    /** As above, but the gap is small enough to close within a single N=1 step -- every sub-step count
     * must still converge to EXACTLY the ground-speed target (not overshoot it, and not stop early), the
     * same "reaches the target exactly once close enough" property {@code boundedSpinTowardSnap} already
     * guarantees for a single call. */
    @Test
    void recoveryReactionConvergesExactlyToGroundSpeedRegardlessOfSubStepCountWhenTheGapIsSmall() {
        double frictionHeadroom = 50_000.0; // deliberately generous: this step closes the gap in one go
        double wheelRadius = 0.35;
        double wheelInertia = 1.2;
        double groundSpin = 5.0;
        double dt = 0.05;
        double priorSpin = 5.5;

        for (int n = 1; n <= 4; n++) {
            double subDt = dt / n;
            double spin = priorSpin;
            for (int sub = 0; sub < n; sub++) {
                double snapped = WheelMath.spinRate(spin, wheelRadius, groundSpin * wheelRadius, 0.0, wheelInertia, subDt);
                spin = DrivetrainMath.boundedSpinTowardSnap(spin, snapped, frictionHeadroom, wheelRadius, wheelInertia, subDt);
            }
            assertEquals(groundSpin, spin, 1e-9, "N=" + n + " must reach ground speed exactly, not approach it asymptotically");
        }
    }

    // --- Clutch, engine inertia and engine-axis reaction torque (MINECRAFT-444) ---------------------

    /** Stock car reference numbers, so these tests exercise the real two-inertia spread across the gearbox
     * rather than invented inertias. */
    private static final double CAR_MASS_KG = 1200.0;
    private static final double DRIVEN_WHEELS = 4;

    private static double reflectedInertia(double ratio) {
        return DrivetrainMath.reflectedWheelInertia((int) DRIVEN_WHEELS, WheelMath.WHEEL_INERTIA, ratio);
    }

    /** The tire reaction a gripping wheel delivering {@code forcePerWheel} puts back on the shaft. */
    private static double tireReaction(double forcePerWheel, double ratio) {
        return DrivetrainMath.wheelSideTorqueAtEngine(forcePerWheel * DRIVEN_WHEELS, CarGeometry.WHEEL_RADIUS, ratio);
    }

    @Test
    void omegaRpmRoundTripsExactly() {
        assertEquals(DrivetrainMath.IDLE_RPM, DrivetrainMath.omegaToRpm(DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM)), 1e-9);
        assertEquals(3000.0, DrivetrainMath.omegaToRpm(DrivetrainMath.rpmToOmega(3000.0)), 1e-9);
    }

    @Test
    void reflectedInertiaFallsWithTheSquareOfTheGearingAndCoversTheEnginesOwnOrderInFirst() {
        double inFirst = reflectedInertia(DrivetrainMath.ratioFor(0));
        double inTop = reflectedInertia(DrivetrainMath.ratioFor(DrivetrainMath.GEAR_RATIOS.length - 1));
        assertTrue(inTop > inFirst * 10.0,
                "a taller gear must reflect far MORE drivetrain inertia to the engine (ratio squared): first="
                        + inFirst + " top=" + inTop);
        assertTrue(inFirst > STOCK.engineInertia() * 2.0 && inFirst < STOCK.engineInertia() * 10.0,
                "in first gear the reflected inertia should be the same order as the engine's own, which is why a low gear revs fast: "
                        + inFirst + " vs " + STOCK.engineInertia());
        double ratio = DrivetrainMath.ratioFor(0);
        double gearing = ratio * DrivetrainMath.FINAL_DRIVE;
        double expected = (CAR_MASS_KG * CarGeometry.WHEEL_RADIUS * CarGeometry.WHEEL_RADIUS
                + DRIVEN_WHEELS * WheelMath.WHEEL_INERTIA) / (gearing * gearing);
        assertEquals(expected, inFirst, 1e-9);
    }

    @Test
    void wheelSideOmegaSignedKeepsTheEnginesOwnRotationSenseUnlikeTheDisplayMagnitude() {
        double ratio = DrivetrainMath.ratioFor(0);
        double rollingForward = 10.0;
        assertTrue(DrivetrainMath.wheelSideOmegaSigned(rollingForward, ratio, 1.0) > 0.0,
                "rolling forward in a forward gear turns the engine forward");
        assertTrue(DrivetrainMath.wheelSideOmegaSigned(rollingForward, ratio, -1.0) < 0.0,
                "rolling FORWARD with REVERSE engaged tries to turn the engine BACKWARD -- the case the display magnitude cannot express");
        assertEquals(DrivetrainMath.wheelSideOmega(rollingForward, ratio),
                Math.abs(DrivetrainMath.wheelSideOmegaSigned(rollingForward, ratio, 1.0)), 1e-9,
                "magnitudes must still agree where the sign is positive");
    }

    @Test
    void engineOutputTorqueSubtractsDragOnlyWhileTheEngineIsTurning() {
        double turning = DrivetrainMath.engineOutputTorque(0.0, 3000.0, DrivetrainMath.rpmToOmega(3000.0), STOCK);
        assertEquals(-STOCK.engineBrakingTorque(), turning, 1e-9, "a closed throttle leaves only the engine's own drag");
        assertEquals(0.0, DrivetrainMath.engineOutputTorque(0.0, 0.0, 0.0, STOCK), 1e-9,
                "a stalled engine has nothing to drag against");
        assertEquals(STOCK.enginePeakTorque() - STOCK.engineBrakingTorque(),
                DrivetrainMath.engineOutputTorque(1.0, DrivetrainMath.IDLE_RPM, DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM), STOCK), 1e-9);
    }

    /**
     * Review round 2's own numeric case, which the previous split design got wrong: engineOmega 400,
     * wheelSideOmega 500, throttle 0 accelerated the engine on 415 N*m of sync torque while handing the
     * wheels only -55. With one exchange the wheels must now be loaded by exactly what accelerates the
     * engine -- same scalar, opposite effect -- and no floor or saturation is involved.
     */
    @Test
    void theReviewersNumericCaseNowLoadsBothShaftsWithTheSameTorque() {
        double ratio = DrivetrainMath.ratioFor(0);
        double engineOmega = 400.0;
        double wheelSideOmega = 500.0;
        double engineTorque = DrivetrainMath.engineOutputTorque(0.0, DrivetrainMath.omegaToRpm(engineOmega), engineOmega, STOCK);
        double exchange = DrivetrainMath.clutchExchangeTorque(engineOmega, wheelSideOmega, engineTorque,
                0.0, reflectedInertia(ratio), TICK_DT, STOCK);
        assertTrue(Math.abs(exchange) < STOCK.clutchTorqueCapacity(), "test premise: this case must not saturate");
        assertTrue(exchange < 0.0, "a wheel side turning faster than the engine must drive the engine, i.e. a negative exchange");
        double next = DrivetrainMath.engineAngularVelocity(engineOmega, engineTorque, exchange, TICK_DT, STOCK);
        assertTrue(next > engineOmega, "the wheels must still spin the engine up here");
        // The identity that was missing: the engine's realized momentum change IS the exchanged impulse.
        assertEquals((engineTorque - exchange) * TICK_DT, (next - engineOmega) * STOCK.engineInertia(), 1e-9,
                "the engine's momentum change must equal the exchange it was integrated against");
    }

    /**
     * The stability property the re-sync gain exists to guarantee, and which the first attempt's tuned
     * gain violated (its per-step error multiplier fell below -1, so "locked" oscillated and grew). Even
     * in the worst case -- an infinitely stiff wheel side that does not respond at all -- the remaining
     * slip must decay monotonically toward zero at every dt, never overshoot, never grow.
     */
    @Test
    void theReSyncGainDecaysSlipMonotonicallyEvenAgainstAnUnmovingWheelSide() {
        for (double dt : new double[] {TICK_DT, TICK_DT / 4.0, 0.2}) {
            for (double ratio : DrivetrainMath.GEAR_RATIOS) {
                double wheelSideOmega = 300.0;
                double omega = 150.0; // 150 rad/s of slip to close
                double previousSlip = Math.abs(omega - wheelSideOmega);
                for (int step = 0; step < 50; step++) {
                    double engineTorque = DrivetrainMath.engineOutputTorque(0.0, DrivetrainMath.omegaToRpm(omega), omega, STOCK);
                    double exchange = DrivetrainMath.clutchExchangeTorque(omega, wheelSideOmega, engineTorque,
                            0.0, reflectedInertia(ratio), dt, STOCK);
                    omega = DrivetrainMath.engineAngularVelocity(omega, engineTorque, exchange, dt, STOCK);
                    double slip = Math.abs(omega - wheelSideOmega);
                    assertTrue(slip <= previousSlip + 1e-9,
                            "slip grew at dt=" + dt + " ratio=" + ratio + " step=" + step + ": " + previousSlip + " -> " + slip);
                    assertTrue(omega <= wheelSideOmega + 1e-9,
                            "the engine overshot past the wheel side at dt=" + dt + " ratio=" + ratio + ": " + omega);
                    previousSlip = slip;
                }
                // What it settles AT is not zero, and that is correct rather than residual error: a
                // dragging engine can only be held at a constant speed by a clutch transmitting exactly
                // its drag, and the only way the re-sync term produces that torque is a small standing
                // slip. Solving feedForward + reSync == engineTorque for the slip gives
                // |engineTorque| * dt / reflectedInertia exactly -- about 4 rad/s (38 RPM) in first gear
                // at a tick's dt, less in a taller gear, and it is what a real engaged clutch does.
                double expectedStandingSlip = STOCK.engineBrakingTorque() * dt / reflectedInertia(ratio);
                assertEquals(expectedStandingSlip, previousSlip, 1e-6,
                        "the engine should settle at exactly the standing slip its own drag demands at dt=" + dt + " ratio=" + ratio);
            }
        }
    }

    @Test
    void anEngagedExchangeDeliversNearlyAllTheEnginesOutputInTopGearAndLessInFirst() {
        // The feed-forward share is I_w/(I_e+I_w): the car dominates in top gear, the engine takes a real
        // cut in first. Both are the locked-clutch result, neither is a tuned fudge.
        double engineTorque = STOCK.enginePeakTorque() - STOCK.engineBrakingTorque();
        double topRatio = DrivetrainMath.ratioFor(DrivetrainMath.GEAR_RATIOS.length - 1);
        double inTop = DrivetrainMath.clutchExchangeTorque(500.0, 500.0, engineTorque, 0.0, reflectedInertia(topRatio), TICK_DT, STOCK);
        double inFirst = DrivetrainMath.clutchExchangeTorque(500.0, 500.0, engineTorque, 0.0, reflectedInertia(DrivetrainMath.ratioFor(0)), TICK_DT, STOCK);
        assertTrue(inTop > engineTorque * 0.95 && inTop <= engineTorque,
                "top gear should pass nearly all of the engine's output to the drivetrain: " + inTop + " of " + engineTorque);
        assertTrue(inFirst < inTop && inFirst > engineTorque * 0.5,
                "first gear should pass a real but smaller share (the engine's own inertia takes a cut): " + inFirst);
    }

    @Test
    void theExchangeSaturatesAtTheConfiguredCapacityInEitherDirection() {
        VehicleSpec.DrivetrainCoupling lowCapacity = new VehicleSpec.DrivetrainCoupling(
                STOCK.engineInertia(), STOCK.enginePeakTorque(), STOCK.engineBrakingTorque(), 60.0);
        double ratio = DrivetrainMath.ratioFor(0);
        double driving = DrivetrainMath.clutchExchangeTorque(500.0, 500.0,
                lowCapacity.enginePeakTorque() - lowCapacity.engineBrakingTorque(), 0.0, reflectedInertia(ratio), TICK_DT, lowCapacity);
        assertEquals(60.0, driving, 1e-9, "a low capacity must bound what the engine can put through the clutch");
        double dragged = DrivetrainMath.clutchExchangeTorque(100.0, 900.0, -lowCapacity.engineBrakingTorque(),
                0.0, reflectedInertia(ratio), TICK_DT, lowCapacity);
        assertEquals(-60.0, dragged, 1e-9, "and must bound it symmetrically when the wheels drive the engine");
    }

    @Test
    void engineBrakingShowsUpAsANegativeExchangeAtAClosedThrottle() {
        double ratio = DrivetrainMath.ratioFor(0);
        double omega = DrivetrainMath.rpmToOmega(3000.0);
        double wheelSideOmega = omega; // locked, coasting
        double engineTorque = DrivetrainMath.engineOutputTorque(0.0, DrivetrainMath.omegaToRpm(omega), omega, STOCK);
        double exchange = DrivetrainMath.clutchExchangeTorque(omega, wheelSideOmega, engineTorque,
                0.0, reflectedInertia(ratio), TICK_DT, STOCK);
        assertTrue(exchange < 0.0, "coasting in gear must retard the drivetrain, not drive it");
        assertTrue(Math.abs(exchange) <= STOCK.engineBrakingTorque() + 1e-9,
                "and never by more than the engine's own drag: " + exchange);
    }

    @Test
    void aForwardRollWithReverseEngagedReadsAsAHardSlipThatDragsTheCarTowardReverse() {
        // The case the unsigned wheel side got wrong. Rolling forward at 10 rad/s of wheel speed with
        // REVERSE engaged, the signed wheel side is strongly NEGATIVE, so the exchange saturates positive:
        // through REVERSE's own gearing that is a rearward force on the car, i.e. it drags it toward
        // reverse instead of holding it forward.
        double wheelSideOmega = DrivetrainMath.wheelSideOmegaSigned(10.0, DrivetrainMath.REVERSE_RATIO, -1.0);
        assertTrue(wheelSideOmega < 0.0, "test premise: the signed wheel side must be negative here");
        double omega = DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM);
        double engineTorque = DrivetrainMath.engineOutputTorque(1.0, DrivetrainMath.omegaToRpm(omega), omega, STOCK);
        double exchange = DrivetrainMath.clutchExchangeTorque(omega, wheelSideOmega, engineTorque,
                0.0, reflectedInertia(DrivetrainMath.REVERSE_RATIO), TICK_DT, STOCK);
        assertEquals(STOCK.clutchTorqueCapacity(), exchange, 1e-9,
                "opposed shafts at speed must saturate the clutch -- it is slipping hard here");
        assertTrue(GearDrive.clutchWheelForce(exchange, DrivetrainMath.REVERSE_RATIO, CarGeometry.WHEEL_RADIUS,
                (int) DRIVEN_WHEELS, CarPhysics.Gear.REVERSE, 1.0) < 0.0,
                "and that saturated exchange must push the car backward, retarding its forward roll");
    }

    @Test
    void engineAngularVelocityNeverGoesNegative() {
        assertEquals(0.0, DrivetrainMath.engineAngularVelocity(0.01, 0.0, 1000.0, 1.0, STOCK), 1e-9,
                "a stalled engine must floor at 0, not spin backwards");
    }

    @Test
    void engineAngularVelocityRisesUnderFullThrottleWithNoClutchLoad() {
        double idleOmega = DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM);
        double torque = DrivetrainMath.engineOutputTorque(1.0, DrivetrainMath.IDLE_RPM, idleOmega, STOCK);
        double next = DrivetrainMath.engineAngularVelocity(idleOmega, torque, 0.0, TICK_DT, STOCK);
        assertTrue(next > idleOmega, "full throttle with no clutch load must accelerate the engine");
        assertTrue(DrivetrainMath.omegaToRpm(next) < DrivetrainMath.LIMITER_RPM,
                "one tick's step must not snap all the way to the limiter -- the engine has real inertia");
    }

    @Test
    void engineAngularVelocityFallsBackTowardIdleAtClosedThrottleFromEngineBrakingAlone() {
        double highOmega = DrivetrainMath.rpmToOmega(4000.0);
        double torque = DrivetrainMath.engineOutputTorque(0.0, 4000.0, highOmega, STOCK);
        assertTrue(DrivetrainMath.engineAngularVelocity(highOmega, torque, 0.0, TICK_DT, STOCK) < highOmega,
                "closed throttle with no clutch load must still decelerate the engine (engine braking)");
    }

    @Test
    void engineReactionTorqueIsZeroWhenTheEngineSpeedDidNotChange() {
        double omega = DrivetrainMath.rpmToOmega(3000.0);
        assertEquals(0.0, DrivetrainMath.engineReactionTorque(omega, omega, TICK_DT, STOCK), 1e-9,
                "an engine whose speed did not change this step has no angular momentum to react with");
    }

    @Test
    void engineReactionTorqueOpposesAnAcceleratingEngine() {
        double priorOmega = DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM);
        double torque = DrivetrainMath.engineOutputTorque(1.0, DrivetrainMath.IDLE_RPM, priorOmega, STOCK);
        double nextOmega = DrivetrainMath.engineAngularVelocity(priorOmega, torque, 0.0, TICK_DT, STOCK);
        assertTrue(nextOmega > priorOmega, "test setup invalid -- the engine must actually speed up here");
        assertTrue(DrivetrainMath.engineReactionTorque(priorOmega, nextOmega, TICK_DT, STOCK) < 0.0,
                "an engine speeding up must react with the OPPOSITE sign on the chassis");
    }

    /**
     * Review round 1: the floor-at-zero case. An engine 0.01 rad/s from a standstill with the throttle shut
     * can only shed 0.01*engineInertia of angular momentum, however much braking torque was nominally
     * available -- so the chassis may only ever be billed for that, not for engineBrakingTorque * dt.
     * Checked at more than one dt, since the whole point is that the clamp binds at some dt and not others.
     */
    @Test
    void engineReactionImpulseNeverExceedsTheEnginesOwnRealizedMomentumChangeAcrossTheZeroFloor() {
        double priorOmega = 0.01;
        for (double dt : new double[] {TICK_DT, 0.025, 1.0 / 60.0, 0.2}) {
            double torque = DrivetrainMath.engineOutputTorque(0.0, DrivetrainMath.omegaToRpm(priorOmega), priorOmega, STOCK);
            double nextOmega = DrivetrainMath.engineAngularVelocity(priorOmega, torque, 0.0, dt, STOCK);
            assertEquals(0.0, nextOmega, 1e-12, "dt=" + dt + ": closed-throttle braking must stop the engine at the floor");
            double reactionImpulse = DrivetrainMath.engineReactionTorque(priorOmega, nextOmega, dt, STOCK) * dt;
            double realizedMomentumChange = (nextOmega - priorOmega) * STOCK.engineInertia();
            assertEquals(-realizedMomentumChange, reactionImpulse, 1e-12,
                    "dt=" + dt + ": the chassis impulse must equal exactly the engine's own realized angular-momentum change");
            assertTrue(Math.abs(reactionImpulse) < STOCK.engineBrakingTorque() * dt,
                    "dt=" + dt + ": the realized impulse must be strictly smaller than the unclipped braking impulse");
        }
    }

    /**
     * The engine-side counterpart of
     * {@link #recoveryReactionImpulseIntegratesTheSameTotalRegardlessOfSubStepCount}: splitting the same
     * total dt into N steps must total the SAME reaction impulse as one step, never N times it.
     */
    @Test
    void engineReactionImpulseIntegratesTheSameTotalRegardlessOfStepCount() {
        double engineTorque = 400.0;
        double clutch = 60.0;
        double startOmega = DrivetrainMath.rpmToOmega(2500.0);

        double oneStepOmega = DrivetrainMath.engineAngularVelocity(startOmega, engineTorque, clutch, TICK_DT, STOCK);
        double oneStepImpulse = DrivetrainMath.engineReactionTorque(startOmega, oneStepOmega, TICK_DT, STOCK) * TICK_DT;

        for (int n = 2; n <= 4; n++) {
            double subDt = TICK_DT / n;
            double omega = startOmega;
            double totalImpulse = 0.0;
            for (int step = 0; step < n; step++) {
                double next = DrivetrainMath.engineAngularVelocity(omega, engineTorque, clutch, subDt, STOCK);
                totalImpulse += DrivetrainMath.engineReactionTorque(omega, next, subDt, STOCK) * subDt;
                omega = next;
            }
            assertEquals(oneStepOmega, omega, 1e-9, "N=" + n + " must reach the same engine speed over the same total dt");
            assertEquals(oneStepImpulse, totalImpulse, 1e-9,
                    "N=" + n + "'s summed reaction impulse must match N=1's, not N times it or a fraction of it");
        }
    }

    @Test
    void engineReactionTorqueTracksOnlyTheEnginesOwnRotationNeverGearOrWheelDirection() {
        // MINECRAFT-444 acceptance: selecting reverse must not flip the engine torque axis. The method
        // takes no gear argument at all -- so an engine accelerating by the same amount must react
        // identically whichever gear produced the exchange that accelerated it.
        double priorOmega = DrivetrainMath.rpmToOmega(2500.0);
        double engineTorque = STOCK.enginePeakTorque() - STOCK.engineBrakingTorque();
        // A wheel side just above the engine in BOTH gears, so the engine is genuinely accelerating in
        // each: forward gear rolling forward, REVERSE rolling backward (its own driving direction).
        double forwardWheelSpin = (priorOmega + 20.0) / (DrivetrainMath.ratioFor(0) * DrivetrainMath.FINAL_DRIVE);
        double reverseWheelSpin = -(priorOmega + 20.0) / (DrivetrainMath.REVERSE_RATIO * DrivetrainMath.FINAL_DRIVE);
        double forwardExchange = DrivetrainMath.clutchExchangeTorque(priorOmega,
                DrivetrainMath.wheelSideOmegaSigned(forwardWheelSpin, DrivetrainMath.ratioFor(0), 1.0), engineTorque,
                0.0, reflectedInertia(DrivetrainMath.ratioFor(0)), TICK_DT, STOCK);
        double reverseExchange = DrivetrainMath.clutchExchangeTorque(priorOmega,
                DrivetrainMath.wheelSideOmegaSigned(reverseWheelSpin, DrivetrainMath.REVERSE_RATIO, -1.0), engineTorque,
                0.0, reflectedInertia(DrivetrainMath.REVERSE_RATIO), TICK_DT, STOCK);
        double forwardNext = DrivetrainMath.engineAngularVelocity(priorOmega, engineTorque, forwardExchange, TICK_DT, STOCK);
        double reverseNext = DrivetrainMath.engineAngularVelocity(priorOmega, engineTorque, reverseExchange, TICK_DT, STOCK);
        assertTrue(forwardNext > priorOmega && reverseNext > priorOmega,
                "test setup invalid -- the engine must be accelerating in both gears here");
        assertTrue(DrivetrainMath.engineReactionTorque(priorOmega, forwardNext, TICK_DT, STOCK) < 0.0,
                "accelerating engine must react negatively in a forward gear");
        assertTrue(DrivetrainMath.engineReactionTorque(priorOmega, reverseNext, TICK_DT, STOCK) < 0.0,
                "the SAME accelerating engine must react with the SAME sign in reverse -- selecting reverse must not flip the engine torque axis");
    }
}
