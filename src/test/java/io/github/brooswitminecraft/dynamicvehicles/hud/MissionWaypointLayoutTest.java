package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #177 review: two mission markers projected to the same (or an overlapping) screen position
 * must not let the later one paint over the earlier one's panel. Covers coincident and
 * non-overlapping projections, and that clearing a mission's reservation stops it from being
 * avoided by the other.
 */
class MissionWaypointLayoutTest {

    @BeforeEach
    void resetState() {
        MissionWaypointLayout.resetForTesting();
    }

    @Test
    void nonOverlappingPanelsAreReturnedUnchanged() {
        Rect dispatcherBox = new Rect(10, 10, 40, 35);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox);

        Rect pathfinderBox = new Rect(100, 100, 130, 125);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox);

        assertEquals(pathfinderBox, resolved);
    }

    @Test
    void coincidentProjectionsAreSeparatedRatherThanOverlapping() {
        // Both missions' destinations happened to project to the exact same screen point.
        Rect dispatcherBox = new Rect(50, 50, 80, 75);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox);

        Rect pathfinderBox = new Rect(50, 50, 80, 75);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox);

        assertFalse(resolved.overlaps(dispatcherBox), "resolved Pathfinder panel must not overlap Dispatcher's: " + resolved);
        assertEquals(pathfinderBox.width(), resolved.width(), "only the vertical position should change");
    }

    @Test
    void partiallyOverlappingPanelsAreSeparated() {
        Rect dispatcherBox = new Rect(50, 50, 80, 75);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox);

        // Only partially overlaps Dispatcher's bottom.
        Rect pathfinderBox = new Rect(55, 70, 85, 95);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox);

        assertFalse(resolved.overlaps(dispatcherBox));
    }

    @Test
    void clearingAMissionLetsTheOtherReserveItsOriginalSpot() {
        Rect dispatcherBox = new Rect(50, 50, 80, 75);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox);
        MissionWaypointLayout.clear(MissionWaypointLayout.Mission.DISPATCHER);

        Rect pathfinderBox = new Rect(50, 50, 80, 75);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox);

        assertEquals(pathfinderBox, resolved, "a cleared mission must not still be avoided");
    }

    @Test
    void resolveOverlapShiftsStraightDownWithAGap() {
        Rect occupied = new Rect(0, 0, 30, 20);
        Rect candidate = new Rect(5, 10, 35, 30);
        Rect resolved = MissionWaypointLayout.resolveOverlap(occupied, candidate);

        assertFalse(resolved.overlaps(occupied));
        assertTrue(resolved.top() > occupied.bottom(), "must clear the occupied bounds with a gap, not just touch it");
        assertEquals(candidate.left(), resolved.left(), "only the vertical position should change");
    }
}
