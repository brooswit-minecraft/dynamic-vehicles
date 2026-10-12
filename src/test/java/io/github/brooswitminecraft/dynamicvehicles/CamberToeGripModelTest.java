package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-function coverage for MINECRAFT-314's bounded static alignment grip model. */
class CamberToeGripModelTest {
    @Test
    void zeroCamberAndToeAreExactlyIdentity() {
        assertEquals(CamberToeGripModel.GripModifiers.IDENTITY, CamberToeGripModel.modifiers(0.0, 0.0));
    }

    @Test
    void camberIsSymmetricPeaksThenReturnsItsLateralGain() {
        assertEquals(CamberToeGripModel.modifiers(2.0, 0.0), CamberToeGripModel.modifiers(-2.0, 0.0));
        assertTrue(CamberToeGripModel.modifiers(3.0, 0.0).lateralScale() > 1.0);
        assertEquals(1.0, CamberToeGripModel.modifiers(8.0, 0.0).lateralScale(), 1e-12);
        assertTrue(CamberToeGripModel.modifiers(8.0, 0.0).longitudinalMuScale() < 1.0);
    }

    @Test
    void toeIsSymmetricAndBothKnobsStayWithinSaneBounds() {
        assertEquals(CamberToeGripModel.modifiers(0.0, 2.0), CamberToeGripModel.modifiers(0.0, -2.0));
        CamberToeGripModel.GripModifiers bounded = CamberToeGripModel.modifiers(80.0, -50.0);
        assertTrue(bounded.longitudinalMuScale() >= 0.89 && bounded.longitudinalMuScale() <= 1.0);
        assertTrue(bounded.lateralScale() >= 1.0 && bounded.lateralScale() <= 1.12);
    }
}
