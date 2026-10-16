package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EnvironmentMathTest {
    @Test
    void zeroHardnessFoliageStillResistsSlightly() {
        assertEquals(EnvironmentMath.FOLIAGE_BASELINE, EnvironmentMath.foliageDragCoefficient(0.0), 0);
    }

    @Test
    void tougherFoliageResistsMore() {
        assertTrue(EnvironmentMath.foliageDragCoefficient(0.2) > EnvironmentMath.foliageDragCoefficient(0.0));
    }

    @Test
    void negativeHardnessNeverReducesBelowBaseline() {
        assertEquals(EnvironmentMath.FOLIAGE_BASELINE, EnvironmentMath.foliageDragCoefficient(-1.0), 0);
    }

    @Test
    void notSubmergedGetsNoBuoyancyOrDrag() {
        assertEquals(0.0, EnvironmentMath.buoyantForce(1200.0, 9.81, 0.0, EnvironmentMath.WATER_BUOYANCY), 0);
        assertEquals(0.0, EnvironmentMath.fluidDragCoefficient(0.0, EnvironmentMath.WATER_DRAG), 0);
    }

    @Test
    void fullySubmergedWaterCounteractsMostButNotAllOfWeight() {
        double weight = 1200.0 * 9.81;
        double buoyancy = EnvironmentMath.buoyantForce(1200.0, 9.81, 1.0, EnvironmentMath.WATER_BUOYANCY);
        assertTrue(buoyancy < weight);
        assertTrue(buoyancy > weight * 0.5);
    }

    @Test
    void lavaBuoysMoreThanWaterAndCanExceedWeight() {
        double weight = 1200.0 * 9.81;
        double waterBuoyancy = EnvironmentMath.buoyantForce(1200.0, 9.81, 1.0, EnvironmentMath.WATER_BUOYANCY);
        double lavaBuoyancy = EnvironmentMath.buoyantForce(1200.0, 9.81, 1.0, EnvironmentMath.LAVA_BUOYANCY);
        assertTrue(lavaBuoyancy > waterBuoyancy);
        assertTrue(lavaBuoyancy > weight);
    }

    @Test
    void lavaDragsMoreThanWaterAtTheSameSubmersion() {
        double water = EnvironmentMath.fluidDragCoefficient(0.5, EnvironmentMath.WATER_DRAG);
        double lava = EnvironmentMath.fluidDragCoefficient(0.5, EnvironmentMath.LAVA_DRAG);
        assertTrue(lava > water);
    }

    @Test
    void submersionFractionIsClampedToZeroOne() {
        assertEquals(EnvironmentMath.fluidDragCoefficient(1.0, EnvironmentMath.WATER_DRAG),
                EnvironmentMath.fluidDragCoefficient(2.0, EnvironmentMath.WATER_DRAG), 0);
        assertEquals(0.0, EnvironmentMath.fluidDragCoefficient(-1.0, EnvironmentMath.WATER_DRAG), 0);
    }

    @Test
    void submersionFractionIsRawDepthOverVehicleHeight() {
        assertEquals(0.5, EnvironmentMath.submersionFraction(1.0, 2.0), 1e-9);
        assertEquals(1.0, EnvironmentMath.submersionFraction(2.0, 2.0), 1e-9);
    }

    @Test
    void aShortVehicleReachesFullBuoyancyAtAShallowerAbsoluteDepthThanATallOne() {
        double depth = 1.0; // one block of fluid above the entity's own Y
        double shortVehicleHeight = 1.0;
        double tallVehicleHeight = 4.0;
        assertEquals(1.0, EnvironmentMath.submersionFraction(depth, shortVehicleHeight), 1e-9);
        assertTrue(EnvironmentMath.submersionFraction(depth, tallVehicleHeight) < 1.0);
    }

    @Test
    void submersionFractionNeverExceedsOneEvenWhenFluidIsDeeperThanTheVehicle() {
        assertEquals(1.0, EnvironmentMath.submersionFraction(10.0, 2.0), 0);
    }

    @Test
    void submersionFractionWithNoVehicleHeightIsZeroNotInfinite() {
        assertEquals(0.0, EnvironmentMath.submersionFraction(1.0, 0.0), 0);
    }
}
