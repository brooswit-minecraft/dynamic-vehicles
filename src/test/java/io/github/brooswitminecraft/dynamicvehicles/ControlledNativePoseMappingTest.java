package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure regressions for the controlled native-fixture pose representation boundary. */
class ControlledNativePoseMappingTest {

    @Test
    void mapsBothRecordedFixtureAxesBeforeNativeConstruction() {
        assertBinary32(-9_907_200.0,
                ControlledNativePoseMapping.toBinary32(-9_907_200.5)); // 51066 X
        assertBinary32(-8_791_550.0,
                ControlledNativePoseMapping.toBinary32(-8_791_550.5)); // 51082 Z
    }

    @Test
    void mapsPositiveAndNegativeCoordinatesAtBinary32PrecisionBoundaries() {
        assertBinary32(8_388_608.0,
                ControlledNativePoseMapping.toBinary32(8_388_608.5));
        assertBinary32(-8_388_608.0,
                ControlledNativePoseMapping.toBinary32(-8_388_608.5));
        assertBinary32(16_777_216.0,
                ControlledNativePoseMapping.toBinary32(16_777_217.0));
        assertBinary32(-16_777_216.0,
                ControlledNativePoseMapping.toBinary32(-16_777_217.0));
    }

    private static void assertBinary32(double expected, double actual) {
        assertEquals(expected, actual, 0.0);
        assertTrue(Double.isFinite(actual) && (double) (float) actual == actual,
                () -> "not a finite binary32-exact coordinate: " + actual);
    }
}
