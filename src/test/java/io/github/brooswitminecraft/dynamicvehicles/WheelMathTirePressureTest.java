package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-445: the pure tire-pressure arithmetic. The production wiring (both grip sites and the
 * rolling site in {@code SableCarBody.tick}) is covered separately by {@code SuspensionTuningGameTests};
 * what these pin is the maths itself, and above all the exact identity at nominal pressure that lets the
 * whole roster keep its current feel.
 */
class WheelMathTirePressureTest {

    /** The pressure every roster car carries, at which both multipliers must be exactly 1.0. */
    private static final double NOMINAL = WheelMath.NOMINAL_TIRE_PRESSURE_BAR;

    @Test
    void bothMultipliersAreExactlyOneAtNominalPressure() {
        // Raw-bit equality, not a tolerance: this is what makes an unconfigured car's grip and rolling
        // resistance bit-for-bit what they were before tire pressure existed. The suspension slice on this
        // same ticket shipped a last-bits difference that a tolerance-based test would have waved through,
        // so the identity claim here is pinned the strict way from the start.
        assertEquals(Double.doubleToRawLongBits(1.0),
                Double.doubleToRawLongBits(WheelMath.tirePressureGripMultiplier(NOMINAL)));
        assertEquals(Double.doubleToRawLongBits(1.0),
                Double.doubleToRawLongBits(WheelMath.tirePressureRollingMultiplier(NOMINAL)));
    }

    @Test
    void underInflationBuysGripAndOverInflationCostsIt() {
        assertTrue(WheelMath.tirePressureGripMultiplier(NOMINAL / 2) > 1.0,
                "a softer tire must have a larger contact patch and therefore more grip");
        assertTrue(WheelMath.tirePressureGripMultiplier(NOMINAL * 2) < 1.0,
                "a harder tire must have a smaller contact patch and therefore less grip");
    }

    @Test
    void underInflationCostsRollingDragAndOverInflationSavesIt() {
        assertTrue(WheelMath.tirePressureRollingMultiplier(NOMINAL / 2) > 1.0,
                "a softer tire must deform more and cost more rolling drag");
        assertTrue(WheelMath.tirePressureRollingMultiplier(NOMINAL * 2) < 1.0,
                "a harder tire must deform less and cost less rolling drag");
    }

    /** The trade-off is the whole point of exposing one knob: the same change cannot be a free win. */
    @Test
    void thePressureTradeOffPullsGripAndRollingDragInOppositeDirections() {
        for (double pressure : new double[] {NOMINAL / 2, NOMINAL * 0.8, NOMINAL * 1.5, NOMINAL * 2}) {
            double grip = WheelMath.tirePressureGripMultiplier(pressure);
            double rolling = WheelMath.tirePressureRollingMultiplier(pressure);
            assertTrue((grip - 1.0) * (rolling - 1.0) > 0.0 || pressure == NOMINAL,
                    "grip and rolling multipliers must move together in SIGN away from nominal (both up "
                            + "when softer, both down when harder), so more grip always costs drag: "
                            + "pressure=" + pressure + " grip=" + grip + " rolling=" + rolling);
        }
    }

    /** Grip must respond far less than proportionally, or deflating a tire becomes a pure cheat code. */
    @Test
    void gripRespondsMuchLessThanProportionallyToPressure() {
        double halfPressureGrip = WheelMath.tirePressureGripMultiplier(NOMINAL / 2);
        assertTrue(halfPressureGrip < 1.5,
                "halving pressure must not come close to doubling grip: " + halfPressureGrip);
        double halfPressureRolling = WheelMath.tirePressureRollingMultiplier(NOMINAL / 2);
        assertTrue(halfPressureRolling > halfPressureGrip,
                "rolling drag must respond more strongly than grip, or under-inflation is a free win: "
                        + "grip=" + halfPressureGrip + " rolling=" + halfPressureRolling);
    }

    /** A degenerate pressure must never reach the friction circle as a divide-by-zero or NaN. */
    @Test
    void nonPositiveOrNonFinitePressureFallsBackToNeutral() {
        for (double bad : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(1.0, WheelMath.tirePressureGripMultiplier(bad), "grip, pressure=" + bad);
            assertEquals(1.0, WheelMath.tirePressureRollingMultiplier(bad), "rolling, pressure=" + bad);
        }
    }
}
