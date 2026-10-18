package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MINECRAFT-411: the shared active/dimension decision a mission waypoint overlay uses to pick
 * between hiding, showing the marker, and showing a wrong-dimension status - covering the
 * inactive-mission and dimension-mismatch states a GameTest harness cannot exercise (no real
 * client renders here), per {@code pathfinder.PathfinderHudPresentationTest}'s existing coverage
 * of the dimension comparison this generalizes.
 */
class MissionPresentationTest {

    @Test
    void inactiveMissionIsHiddenRegardlessOfDimension() {
        assertEquals(MissionPresentation.Mode.HIDDEN, MissionPresentation.decide(false, true));
        assertEquals(MissionPresentation.Mode.HIDDEN, MissionPresentation.decide(false, false));
    }

    @Test
    void activeMissionInTheSameDimensionShowsTheMarker() {
        assertEquals(MissionPresentation.Mode.MARKER, MissionPresentation.decide(true, true));
    }

    @Test
    void activeMissionInADifferentDimensionShowsWrongDimensionStatus() {
        assertEquals(MissionPresentation.Mode.WRONG_DIMENSION, MissionPresentation.decide(true, false));
    }
}
