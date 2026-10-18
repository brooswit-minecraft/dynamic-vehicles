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
    void smoothedRpmNeverJumpsMoreThanTheMaxChangePerTick() {
        double result = DrivetrainMath.smoothedRpm(DrivetrainMath.IDLE_RPM, DrivetrainMath.LIMITER_RPM);
        assertEquals(DrivetrainMath.IDLE_RPM + DrivetrainMath.MAX_RPM_CHANGE_PER_TICK, result, 1e-9,
                "a target far above the current value must only move by the max change per tick, not jump straight to it");
    }

    @Test
    void smoothedRpmReachesTheTargetExactlyOnceCloseEnough() {
        double small = DrivetrainMath.MAX_RPM_CHANGE_PER_TICK / 2.0;
        double result = DrivetrainMath.smoothedRpm(1000.0, 1000.0 + small);
        assertEquals(1000.0 + small, result, 1e-9, "a target already within one tick's reach must be met exactly, not overshot or approached forever");
    }

    @Test
    void smoothedRpmTracksDownwardAtTheSameBoundedRate() {
        // MINECRAFT-401 review (round 2): the whole point of this function is that a wheel's own spin can
        // snap to a much LOWER value instantly (WheelMath.spinRate's grip snap) without the engine's RPM
        // following it in a single tick -- downward must be bounded exactly like upward.
        double result = DrivetrainMath.smoothedRpm(DrivetrainMath.LIMITER_RPM, 0.0);
        assertEquals(DrivetrainMath.LIMITER_RPM - DrivetrainMath.MAX_RPM_CHANGE_PER_TICK, result, 1e-9,
                "a target far below the current value must decay at the bounded rate, not snap straight down");
    }

    @Test
    void smoothedRpmConvergesToASustainedTargetOverSeveralTicks() {
        double rpm = DrivetrainMath.IDLE_RPM;
        for (int i = 0; i < 50; i++) {
            rpm = DrivetrainMath.smoothedRpm(rpm, DrivetrainMath.LIMITER_RPM);
        }
        assertEquals(DrivetrainMath.LIMITER_RPM, rpm, 1e-9, "a sustained target must eventually be reached exactly");
    }

    @Test
    void clampRpmMatchesEngineRpmsOwnClamp() {
        assertEquals(DrivetrainMath.IDLE_RPM, DrivetrainMath.clampRpm(0.0), 1e-9);
        assertEquals(DrivetrainMath.LIMITER_RPM, DrivetrainMath.clampRpm(50_000.0), 1e-9);
        assertEquals(3000.0, DrivetrainMath.clampRpm(3000.0), 1e-9);
    }
}
