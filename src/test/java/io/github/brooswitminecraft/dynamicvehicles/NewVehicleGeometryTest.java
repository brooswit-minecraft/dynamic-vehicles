package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-function checks for the three MINECRAFT-380 vehicles' own hitbox half-extents ({@link
 * VehicleGeometry}), used to size each one for registration (EntityType.Builder#sized): mirrors
 * VehicleSpecTest's own driftEntitySizeMatchesItsHalfExtents, since these vehicles carry no {@link
 * VehicleSpec} of their own (see {@link SimpleVehicleEntity}). */
class NewVehicleGeometryTest {

    @Test
    void everyHalfExtentIsPositive() {
        assertTrue(VehicleGeometry.MOTORBOAT_HALF_WIDTH > 0 && VehicleGeometry.MOTORBOAT_HALF_HEIGHT > 0
                && VehicleGeometry.MOTORBOAT_HALF_LENGTH > 0);
        assertTrue(VehicleGeometry.CHOPPER_HALF_WIDTH > 0 && VehicleGeometry.CHOPPER_HALF_HEIGHT > 0
                && VehicleGeometry.CHOPPER_HALF_LENGTH > 0);
        assertTrue(VehicleGeometry.BIPLANE_HALF_WIDTH > 0 && VehicleGeometry.BIPLANE_HALF_HEIGHT > 0
                && VehicleGeometry.BIPLANE_HALF_LENGTH > 0);
    }

    @Test
    void eachVehicleGetsItsOwnDistinctFootprint() {
        assertNotEquals(VehicleGeometry.MOTORBOAT_HALF_WIDTH, VehicleGeometry.CHOPPER_HALF_WIDTH);
        assertNotEquals(VehicleGeometry.CHOPPER_HALF_WIDTH, VehicleGeometry.BIPLANE_HALF_WIDTH);
        assertNotEquals(VehicleGeometry.MOTORBOAT_HALF_LENGTH, VehicleGeometry.BIPLANE_HALF_LENGTH);
    }
}
