package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #177 review: two mission markers projected to the same (or an overlapping) screen position
 * must not let the later one paint over the earlier one's panel, and the resolved position must
 * itself stay fully on screen - re-review follow-up: a naive unconditional downward shift could
 * push an already bottom-edge-clamped panel below the viewport, which is exactly the scenario
 * where two panels are near the bottom in the first place.
 */
class MissionWaypointLayoutTest {
    private static final int VIEWPORT_HEIGHT = 240;

    @BeforeEach
    void resetState() {
        MissionWaypointLayout.resetForTesting();
    }

    @Test
    void nonOverlappingPanelsAreReturnedUnchanged() {
        Rect dispatcherBox = new Rect(10, 10, 40, 35);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox, VIEWPORT_HEIGHT);

        Rect pathfinderBox = new Rect(100, 100, 130, 125);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox, VIEWPORT_HEIGHT);

        assertEquals(pathfinderBox, resolved);
    }

    @Test
    void coincidentProjectionsAreSeparatedRatherThanOverlapping() {
        // Both missions' destinations happened to project to the exact same screen point.
        Rect dispatcherBox = new Rect(50, 50, 80, 75);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox, VIEWPORT_HEIGHT);

        Rect pathfinderBox = new Rect(50, 50, 80, 75);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox, VIEWPORT_HEIGHT);

        assertFalse(resolved.overlaps(dispatcherBox), "resolved Pathfinder panel must not overlap Dispatcher's: " + resolved);
        assertEquals(pathfinderBox.width(), resolved.width(), "only the vertical position should change");
    }

    @Test
    void partiallyOverlappingPanelsAreSeparated() {
        Rect dispatcherBox = new Rect(50, 50, 80, 75);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox, VIEWPORT_HEIGHT);

        // Only partially overlaps Dispatcher's bottom.
        Rect pathfinderBox = new Rect(55, 70, 85, 95);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox, VIEWPORT_HEIGHT);

        assertFalse(resolved.overlaps(dispatcherBox));
    }

    @Test
    void clearingAMissionLetsTheOtherReserveItsOriginalSpot() {
        Rect dispatcherBox = new Rect(50, 50, 80, 75);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox, VIEWPORT_HEIGHT);
        MissionWaypointLayout.clear(MissionWaypointLayout.Mission.DISPATCHER);

        Rect pathfinderBox = new Rect(50, 50, 80, 75);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox, VIEWPORT_HEIGHT);

        assertEquals(pathfinderBox, resolved, "a cleared mission must not still be avoided");
    }

    @Test
    void reviewsOwnBottomEdgeExampleStaysOnScreenByMovingAbove() {
        // The re-review's exact scenario: both targets clamped near the bottom at GUI height 240.
        // Dispatcher's own panel (0 extra lines) clamps to [215, 240); Pathfinder's (2 extra lines)
        // would independently clamp to [195, 240), which overlaps Dispatcher's. A naive downward
        // shift (240 - 195 + gap) pushes Pathfinder to [244, 289) - off screen. Shifting up instead
        // must keep the whole resolved panel on screen.
        Rect dispatcherBox = new Rect(140, 215, 180, 240);
        MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.DISPATCHER, dispatcherBox, VIEWPORT_HEIGHT);

        Rect pathfinderBox = new Rect(140, 195, 180, 240);
        Rect resolved = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderBox, VIEWPORT_HEIGHT);

        assertTrue(resolved.top() >= 0, "resolved panel top must stay on screen: " + resolved);
        assertTrue(resolved.bottom() <= VIEWPORT_HEIGHT, "resolved panel bottom must stay on screen: " + resolved);
        assertFalse(resolved.overlaps(dispatcherBox), "resolved panel must still clear the other mission's: " + resolved);
    }

    @Test
    void resolveOverlapPrefersShiftingDownWhenThatStaysOnScreen() {
        Rect occupied = new Rect(0, 0, 30, 20);
        Rect candidate = new Rect(5, 10, 35, 30);
        Rect resolved = MissionWaypointLayout.resolveOverlap(occupied, candidate, 1000);

        assertFalse(resolved.overlaps(occupied));
        assertTrue(resolved.top() > occupied.bottom(), "must clear the occupied bounds with a gap, not just touch it");
        assertEquals(candidate.left(), resolved.left(), "only the vertical position should change");
    }

    @Test
    void resolveOverlapShiftsUpInsteadWhenShiftingDownWouldLeaveTheScreen() {
        Rect occupied = new Rect(0, 150, 30, 200);
        Rect candidate = new Rect(5, 130, 35, 200);
        int viewportHeight = 200;

        Rect resolved = MissionWaypointLayout.resolveOverlap(occupied, candidate, viewportHeight);

        assertFalse(resolved.overlaps(occupied));
        assertTrue(resolved.bottom() <= viewportHeight);
        assertTrue(resolved.top() >= 0);
        assertTrue(resolved.bottom() <= occupied.top(), "shifting up must end at or above the occupied bounds' top, minus the gap");
    }
}
