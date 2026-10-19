package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DrivetrainMathTest {
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

    @Test
    void omegaRpmRoundTripsExactly() {
        assertEquals(DrivetrainMath.IDLE_RPM, DrivetrainMath.omegaToRpm(DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM)), 1e-9);
        assertEquals(3000.0, DrivetrainMath.omegaToRpm(DrivetrainMath.rpmToOmega(3000.0)), 1e-9);
    }

    @Test
    void engineAngularVelocityRisesUnderFullThrottleWithNoClutchLoad() {
        // Disengaged (no clutch torque): the engine must still accelerate itself, bounded by its own
        // inertia -- not snap to the limiter in a single step.
        double idleOmega = DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM);
        double torque = DrivetrainMath.engineDriveTorque(1.0, DrivetrainMath.IDLE_RPM);
        double next = DrivetrainMath.engineAngularVelocity(idleOmega, torque, 0.0, 0.05);
        assertTrue(next > idleOmega, "full throttle with no clutch load must accelerate the engine");
        assertTrue(DrivetrainMath.omegaToRpm(next) < DrivetrainMath.LIMITER_RPM,
                "one 0.05s step must not snap all the way to the limiter -- the engine has real inertia");
    }

    @Test
    void engineAngularVelocityFallsBackTowardIdleAtClosedThrottleFromEngineBrakingAlone() {
        double highOmega = DrivetrainMath.rpmToOmega(4000.0);
        double next = DrivetrainMath.engineAngularVelocity(highOmega, 0.0, 0.0, 0.05);
        assertTrue(next < highOmega, "closed throttle with no clutch load must still decelerate the engine (engine braking)");
    }

    @Test
    void engineAngularVelocityNeverGoesNegative() {
        double lowOmega = 0.01;
        double next = DrivetrainMath.engineAngularVelocity(lowOmega, 0.0, 1000.0, 1.0);
        assertEquals(0.0, next, 1e-9, "a stalled engine must floor at 0, not spin backwards");
    }

    @Test
    void engineAngularVelocityAppliesNoBrakingAgainstAnAlreadyStoppedEngine() {
        // priorOmega == 0 must not have engine braking subtract torque that would drive it negative before
        // the floor -- the braking term itself must already read as zero, not merely be clamped away.
        double next = DrivetrainMath.engineAngularVelocity(0.0, 0.0, 0.0, 0.05);
        assertEquals(0.0, next, 1e-9);
    }

    @Test
    void clutchTorqueIsZeroWhenAlreadyAtTheTargetSpeedWithNoOtherNetTorque() {
        // engineTorque == braking (net torque before the clutch is 0) and no speed gap: nothing for the
        // clutch to correct.
        assertEquals(0.0, DrivetrainMath.clutchTorque(500.0, 500.0, DrivetrainMath.ENGINE_BRAKING_TORQUE, 0.05), 1e-9,
                "a perfectly matched (locked) clutch with no other net torque must transmit no corrective torque");
    }

    @Test
    void clutchTorquePullsTowardZeroRelativeSpeedInEitherDirection() {
        double engineTorque = DrivetrainMath.ENGINE_BRAKING_TORQUE; // net torque before the clutch is 0
        assertTrue(DrivetrainMath.clutchTorque(600.0, 500.0, engineTorque, 0.05) > 0.0,
                "engine faster than the wheel side must transmit torque FORWARD (engine -> drivetrain)");
        assertTrue(DrivetrainMath.clutchTorque(400.0, 500.0, engineTorque, 0.05) < 0.0,
                "engine slower than the wheel side must transmit torque the other way");
    }

    @Test
    void clutchTorqueSaturatesAtItsCapacityForALargeGapInsteadOfGrowingUnbounded() {
        // "Partial coupling"/slip: once the torque needed to land exactly on the wheel-side speed this
        // step is larger than the clutch's own capacity, the result must clamp there, not keep scaling up.
        double dt = 0.05;
        double engineTorque = DrivetrainMath.ENGINE_BRAKING_TORQUE;
        double justUnderCapacityGap = DrivetrainMath.CLUTCH_TORQUE_CAPACITY / (DrivetrainMath.ENGINE_INERTIA / dt) - 1.0;
        double wellPastCapacityGap = justUnderCapacityGap + 10_000.0;
        assertTrue(DrivetrainMath.clutchTorque(justUnderCapacityGap, 0.0, engineTorque, dt) < DrivetrainMath.CLUTCH_TORQUE_CAPACITY);
        assertEquals(DrivetrainMath.CLUTCH_TORQUE_CAPACITY, DrivetrainMath.clutchTorque(wellPastCapacityGap, 0.0, engineTorque, dt), 1e-9,
                "a huge relative speed must saturate at the clutch's own capacity, not scale past it");
        assertEquals(-DrivetrainMath.CLUTCH_TORQUE_CAPACITY, DrivetrainMath.clutchTorque(-wellPastCapacityGap, 0.0, engineTorque, dt), 1e-9,
                "saturation must be symmetric in the other direction");
    }

    @Test
    void wheelSideOmegaIgnoresWheelSpinSignAndScalesWithRatioAndFinalDrive() {
        double atD1 = DrivetrainMath.wheelSideOmega(10.0, DrivetrainMath.ratioFor(0));
        double atTopGear = DrivetrainMath.wheelSideOmega(10.0, DrivetrainMath.ratioFor(DrivetrainMath.GEAR_RATIOS.length - 1));
        assertTrue(atD1 > atTopGear, "the same wheel speed must read as a higher engine-side speed in a lower (higher-ratio) gear");
        assertEquals(DrivetrainMath.wheelSideOmega(10.0, DrivetrainMath.ratioFor(0)), DrivetrainMath.wheelSideOmega(-10.0, DrivetrainMath.ratioFor(0)), 1e-9,
                "a fixed ratio spins the engine the same way regardless of which direction the wheel turns");
    }

    @Test
    void engagedClutchLocksEngineOmegaExactlyToTheWheelSideWithinOneStepWhenWithinCapacity() {
        // A moderate gap, well inside CLUTCH_TORQUE_CAPACITY for this inertia/dt: a real locked clutch
        // doesn't gradually approach the flywheel speed over several ticks, it turns as one with it -- the
        // "engaged" case this method is built to land on exactly, the same single-step-exact contract
        // boundedSpinTowardSnap already guarantees at the wheel for a small-enough gap.
        double wheelSideOmega = DrivetrainMath.rpmToOmega(3000.0);
        double engineOmega = DrivetrainMath.rpmToOmega(3050.0);
        double dt = 0.05;
        double engineTorque = DrivetrainMath.ENGINE_BRAKING_TORQUE; // net torque before the clutch is 0
        double clutch = DrivetrainMath.clutchTorque(engineOmega, wheelSideOmega, engineTorque, dt);
        double next = DrivetrainMath.engineAngularVelocity(engineOmega, engineTorque, clutch, dt);
        assertEquals(wheelSideOmega, next, 1e-6, "a gap within the clutch's own capacity must lock exactly, not merely approach, within this one step");
    }

    @Test
    void slippingClutchReducesButDoesNotEliminateTheGapInOneStepWhenPastCapacity() {
        // A gap large enough to need more torque than CLUTCH_TORQUE_CAPACITY can supply: the clutch must
        // still pull the engine toward the wheel side (by exactly what its capacity allows), but a single
        // step must NOT reach the target -- that persisting gap IS slip.
        double wheelSideOmega = 0.0;
        double engineOmega = DrivetrainMath.rpmToOmega(6000.0);
        double dt = 0.05;
        double engineTorque = DrivetrainMath.ENGINE_BRAKING_TORQUE;
        double clutch = DrivetrainMath.clutchTorque(engineOmega, wheelSideOmega, engineTorque, dt);
        assertEquals(DrivetrainMath.CLUTCH_TORQUE_CAPACITY, clutch, 1e-9, "test setup must actually exercise the saturated branch");
        double next = DrivetrainMath.engineAngularVelocity(engineOmega, engineTorque, clutch, dt);
        assertTrue(next < engineOmega, "a saturated clutch pulling the engine down must still reduce its speed somewhat");
        assertTrue(next > wheelSideOmega, "but a single step under a saturated (slipping) clutch must not reach the wheel-side target");
    }

    @Test
    void disengagedClutchAppliesNoTorqueRegardlessOfSpeedGap() {
        // NEUTRAL (SableCarBody's own disengaged case) passes 0.0 as clutchTorque directly rather than
        // calling clutchTorque() at all -- this documents the contract that call site relies on: the
        // engine's own integration with a 0.0 clutch load is exactly the free-rev case, unaffected by any
        // wheel-side speed, however large the gap.
        double engineOmega = DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM);
        double withNoWheelCoupling = DrivetrainMath.engineAngularVelocity(engineOmega, 200.0, 0.0, 0.05);
        assertTrue(withNoWheelCoupling > engineOmega, "disengaged (0.0 clutch torque) must still let engine torque past braking accelerate the engine");
    }

    @Test
    void engineReactionTorqueIsZeroWhenNetEngineTorqueIsZero() {
        // Steady state: engine torque exactly balances braking plus clutch load -> no net angular
        // acceleration -> no reaction.
        double priorOmega = DrivetrainMath.rpmToOmega(3000.0);
        double engineTorque = DrivetrainMath.ENGINE_BRAKING_TORQUE + 100.0;
        double clutch = 100.0;
        assertEquals(0.0, DrivetrainMath.engineReactionTorque(priorOmega, engineTorque, clutch), 1e-9);
    }

    @Test
    void engineReactionTorqueOpposesAnAcceleratingEngine() {
        double priorOmega = DrivetrainMath.rpmToOmega(DrivetrainMath.IDLE_RPM);
        double engineTorque = DrivetrainMath.ENGINE_PEAK_TORQUE;
        double reaction = DrivetrainMath.engineReactionTorque(priorOmega, engineTorque, 0.0);
        assertTrue(reaction < 0.0, "a positive net engine torque speeding the engine up must react with the OPPOSITE sign on the chassis");
    }

    @Test
    void engineReactionTorqueTracksOnlyNetEngineTorqueNeverGearOrWheelDirection() {
        // MINECRAFT-444 acceptance: selecting reverse must not flip the engine torque axis. The method
        // itself takes no gear argument at all -- the invariant under test is that driving the SAME engine
        // state (priorOmega, engineTorque) through whatever clutch torque a forward gear happens to produce
        // vs. what REVERSE happens to produce (same wheel speed magnitude, different ratio, so a different
        // clutch value) still yields a reaction whose SIGN is governed by engineTorque alone, not by which
        // gear produced that clutch value.
        double priorOmega = DrivetrainMath.rpmToOmega(2500.0);
        double dt = 0.05;
        // Large enough to dominate ENGINE_BRAKING_TORQUE and even a fully-saturated CLUTCH_TORQUE_CAPACITY
        // in both branches below, so net torque (and so the reaction) is unambiguously positive-engine-
        // torque-wins in both gears, not an artifact of which clutch value happened to be larger.
        double engineTorque = DrivetrainMath.ENGINE_BRAKING_TORQUE + DrivetrainMath.CLUTCH_TORQUE_CAPACITY + 200.0;
        double clutchForward = DrivetrainMath.clutchTorque(priorOmega, DrivetrainMath.wheelSideOmega(10.0, DrivetrainMath.ratioFor(0)), engineTorque, dt);
        double clutchReverse = DrivetrainMath.clutchTorque(priorOmega, DrivetrainMath.wheelSideOmega(10.0, DrivetrainMath.REVERSE_RATIO), engineTorque, dt);
        double reactionForward = DrivetrainMath.engineReactionTorque(priorOmega, engineTorque, clutchForward);
        double reactionReverse = DrivetrainMath.engineReactionTorque(priorOmega, engineTorque, clutchReverse);
        assertTrue(reactionForward < 0.0, "accelerating engine must react negatively in forward gear");
        assertTrue(reactionReverse < 0.0, "the SAME accelerating engine must react with the SAME (negative) sign in reverse -- selecting reverse must not flip the engine torque axis");
    }
}
