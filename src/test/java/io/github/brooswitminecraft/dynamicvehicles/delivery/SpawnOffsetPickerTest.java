package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-130 AC2/AC7c: spawn offsets land near the traveling entity and,
 * over many seeded draws, within the configured arc of its heading -
 * demonstrating "ahead matters" with an angle assertion rather than a PR claim.
 */
class SpawnOffsetPickerTest {

    @Test
    void pick_everyOffsetFallsWithinDistanceBounds() {
        Random random = new Random(7);
        double min = 10.0;
        double max = 20.0;
        for (int i = 0; i < 5000; i++) {
            SpawnOffset offset = SpawnOffsetPicker.pick(0.0, 1.0, min, max, Math.PI / 2, random);
            double distance = offset.distance();
            assertTrue(distance >= min - 1e-9 && distance <= max + 1e-9,
                    "offset distance " + distance + " out of [" + min + ", " + max + "]");
        }
    }

    @Test
    void pick_everyOffsetFallsWithinHalfTheArcOfHeading() {
        Random random = new Random(7);
        double arc = Math.PI / 2; // 90 degrees total, so +-45 degrees from heading
        double forwardX = 1.0;
        double forwardZ = 0.0;
        for (int i = 0; i < 5000; i++) {
            SpawnOffset offset = SpawnOffsetPicker.pick(forwardX, forwardZ, 10.0, 20.0, arc, random);
            double angle = SpawnOffsetPicker.angleFromHeading(forwardX, forwardZ, offset.dx(), offset.dz());
            assertTrue(angle <= arc / 2 + 1e-9,
                    "offset angle " + Math.toDegrees(angle) + " exceeds half-arc " + Math.toDegrees(arc / 2));
        }
    }

    @Test
    void pick_followsHeadingRegardlessOfDirection() {
        Random random = new Random(11);
        // heading due "south" in this plain coordinate system (forwardX=0, forwardZ=1)
        for (int i = 0; i < 1000; i++) {
            SpawnOffset offset = SpawnOffsetPicker.pick(0.0, 1.0, 10.0, 20.0, Math.PI / 3, random);
            double angle = SpawnOffsetPicker.angleFromHeading(0.0, 1.0, offset.dx(), offset.dz());
            assertTrue(angle <= Math.PI / 6 + 1e-9);
        }
    }

    @Test
    void angleFromHeading_isZero_forAnOffsetExactlyOnHeading() {
        assertTrue(SpawnOffsetPicker.angleFromHeading(1.0, 0.0, 5.0, 0.0) < 1e-9);
    }

    @Test
    void angleFromHeading_isPi_forAnOffsetDirectlyBehindHeading() {
        double angle = SpawnOffsetPicker.angleFromHeading(1.0, 0.0, -5.0, 0.0);
        assertTrue(Math.abs(angle - Math.PI) < 1e-9);
    }
}
