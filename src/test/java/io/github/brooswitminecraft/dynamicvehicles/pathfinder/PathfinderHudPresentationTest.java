package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathfinderHudPresentationTest {

    @Test
    void sameDimensionShowsTheArrow() {
        assertEquals(PathfinderHudPresentation.Mode.ARROW,
                PathfinderHudPresentation.decide("minecraft:overworld", "minecraft:overworld"));
    }

    @Test
    void differentDimensionShowsCrossDimensionGuidanceInstead() {
        assertEquals(PathfinderHudPresentation.Mode.WRONG_DIMENSION,
                PathfinderHudPresentation.decide("minecraft:overworld", "minecraft:the_nether"));
    }
}
