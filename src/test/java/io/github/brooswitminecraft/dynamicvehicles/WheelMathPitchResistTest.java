package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-445: the anti-dive / anti-squat application-height blend. The production wiring is covered by
 * {@code SuspensionTuningGameTests}; what this pins is the blend itself, and above all that a fraction of
 * 0.0 returns the contact height bit-for-bit, which is what keeps every roster car's pitch behaviour
 * exactly as it was.
 */
class WheelMathPitchResistTest {

    /** A representative contact-patch height in the body frame: below the body, as a wheel always is. */
    private static final double CONTACT_Y = -0.75;
    /** The height a force applied straight into the chassis acts at, matching the production constant. */
    private static final double BODY_Y = -0.1;

    @Test
    void zeroFractionReturnsTheContactHeightBitForBit() {
        // Raw bits, not a tolerance: this is the identity claim that keeps the roster unchanged, and the
        // suspension slice on this ticket already shipped one last-bits difference that a tolerance test
        // would have accepted.
        assertEquals(Double.doubleToRawLongBits(CONTACT_Y),
                Double.doubleToRawLongBits(WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 0.0)));
    }

    @Test
    void fullFractionAppliesTheForceAtBodyHeight() {
        assertEquals(BODY_Y, WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 1.0), 1e-12);
    }

    @Test
    void theBlendMovesMonotonicallyTowardTheBodyAsTheFractionRises() {
        double previous = WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 0.0);
        for (double fraction = 0.1; fraction <= 1.0; fraction += 0.1) {
            double height = WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, fraction);
            assertTrue(height > previous,
                    "a larger anti-dive/anti-squat fraction must move the force application point further "
                            + "toward the body (shorter moment arm): fraction=" + fraction + " height="
                            + height + " previous=" + previous);
            previous = height;
        }
    }

    /** The moment arm about the body centre is what actually produces pitch, so that is what must shrink. */
    @Test
    void aHigherFractionShortensTheMomentArmAboutTheBody() {
        double noResist = Math.abs(WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 0.0) - BODY_Y);
        double halfResist = Math.abs(WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 0.5) - BODY_Y);
        double fullResist = Math.abs(WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 1.0) - BODY_Y);
        assertTrue(halfResist < noResist, "half resistance must shorten the arm: " + halfResist + " vs " + noResist);
        assertEquals(0.0, fullResist, 1e-12, "full resistance must cancel the arm entirely");
    }
}
