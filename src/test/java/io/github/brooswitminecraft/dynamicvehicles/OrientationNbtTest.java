package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-410: {@link OrientationNbt} is the shared pure logic behind both {@code CarEntity}'s and the
 * new {@code MotorboatEntity}'s orientation NBT round-trip -- this pins down its own contract directly
 * (no prior test existed for it), since the motorboat is now the second real caller relying on it.
 */
class OrientationNbtTest {
    @Test
    void readsNothingWhenOrientationWIsAbsent() {
        assertNull(OrientationNbt.read(false, 0f, 0f, 0f, 0f));
    }

    @Test
    void readsAllFourValuesWhenOrientationWIsPresent() {
        OrientationNbt.Orientation orientation = OrientationNbt.read(true, 0.1f, 0.2f, 0.3f, 0.9f);
        assertEquals(0.1f, orientation.x());
        assertEquals(0.2f, orientation.y());
        assertEquals(0.3f, orientation.z());
        assertEquals(0.9f, orientation.w());
    }
}
