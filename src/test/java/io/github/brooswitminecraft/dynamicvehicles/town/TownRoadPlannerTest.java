package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-388 review finding #3 regressions: roads must actually connect to the agreed edge
 * points (not just the pre-existing centre cross), must be a contiguous path, and two
 * neighbouring regions' independently generated roads must agree on the elevation at their
 * shared boundary - not just its (x, z).
 */
class TownRoadPlannerTest {
    private static final long SEED = 42L;

    @Test
    void theRouteStartsExactlyAtTheAgreedEdgeConnectionPoint() {
        RegionCoord region = new RegionCoord(2, 2);
        TownPlan plan = TownPlanner.generate(region, SEED);

        for (TownEdge edge : TownEdge.values()) {
            List<TownRoadPlanner.RoadCell> route = TownRoadPlanner.routeForEdge(plan, edge, 64);
            int[] edgeAbsolute = plan.edgeConnectionAbsolute(edge);
            TownRoadPlanner.RoadCell first = route.get(0);
            assertEquals(edgeAbsolute[0], first.x(), "route for " + edge + " must start at the agreed edge X");
            assertEquals(edgeAbsolute[1], first.z(), "route for " + edge + " must start at the agreed edge Z");
        }
    }

    @Test
    void theRouteEndsAtTheTownCentre() {
        RegionCoord region = new RegionCoord(-3, 1);
        TownPlan plan = TownPlanner.generate(region, SEED);

        for (TownEdge edge : TownEdge.values()) {
            List<TownRoadPlanner.RoadCell> route = TownRoadPlanner.routeForEdge(plan, edge, 70);
            TownRoadPlanner.RoadCell last = route.get(route.size() - 1);
            assertEquals(plan.townCenterX(), last.x());
            assertEquals(plan.townCenterZ(), last.z());
        }
    }

    @Test
    void theRouteIsAContiguousPathWithNoGaps() {
        RegionCoord region = new RegionCoord(0, 0);
        TownPlan plan = TownPlanner.generate(region, SEED);
        List<TownRoadPlanner.RoadCell> route = TownRoadPlanner.routeForEdge(plan, TownEdge.EAST, 65);

        for (int i = 1; i < route.size(); i++) {
            TownRoadPlanner.RoadCell prev = route.get(i - 1);
            TownRoadPlanner.RoadCell cur = route.get(i);
            int dx = Math.abs(cur.x() - prev.x());
            int dz = Math.abs(cur.z() - prev.z());
            assertTrue(dx + dz == 1, "consecutive road cells must be adjacent, found " + prev + " -> " + cur);
        }
    }

    @Test
    void neighboringRegionsAgreeOnTheElevationAtTheirSharedBoundary() {
        RegionCoord a = new RegionCoord(6, 6);
        RegionCoord b = new RegionCoord(7, 6); // a's east neighbor
        TownPlan planA = TownPlanner.generate(a, SEED);
        TownPlan planB = TownPlanner.generate(b, SEED);

        int referenceHeightA = 60;
        int referenceHeightB = 75; // deliberately different "own town centre height" for b
        List<TownRoadPlanner.RoadCell> routeFromA = TownRoadPlanner.routeForEdge(planA, TownEdge.EAST, referenceHeightA);
        List<TownRoadPlanner.RoadCell> routeFromB = TownRoadPlanner.routeForEdge(planB, TownEdge.WEST, referenceHeightB);

        TownRoadPlanner.RoadCell boundaryFromA = routeFromA.get(0);
        TownRoadPlanner.RoadCell boundaryFromB = routeFromB.get(0);
        assertEquals(boundaryFromA.y(), boundaryFromB.y(),
                "both regions must build the road to the SAME height at their shared edge, "
                        + "regardless of each region's own town-centre reference height");
    }

    @Test
    void heightNeverChangesFasterThanTheConfiguredMaxGrade() {
        RegionCoord region = new RegionCoord(0, 0);
        TownPlan plan = TownPlanner.generate(region, SEED);
        List<TownRoadPlanner.RoadCell> route = TownRoadPlanner.routeForEdge(plan, TownEdge.NORTH, 90);

        for (int i = 1; i < route.size(); i++) {
            int rise = Math.abs(route.get(i).y() - route.get(i - 1).y());
            assertTrue(rise <= 1, "a single road cell must never rise/fall by more than one block");
        }
        // Over any window of ROAD_GRADE_RUN_PER_RISE_BLOCK consecutive cells, total rise must stay
        // within one block (the 20% grade bound), not just the per-cell check above.
        int window = TownConstants.ROAD_GRADE_RUN_PER_RISE_BLOCK;
        for (int i = window; i < route.size(); i++) {
            int rise = Math.abs(route.get(i).y() - route.get(i - window).y());
            assertTrue(rise <= 1, "grade exceeded max over a " + window + "-cell window at index " + i);
        }
    }

    @Test
    void theRouteEventuallyReachesTheCentreReferenceHeight() {
        RegionCoord region = new RegionCoord(0, 0);
        TownPlan plan = TownPlanner.generate(region, SEED);
        int centerHeight = 80;
        List<TownRoadPlanner.RoadCell> route = TownRoadPlanner.routeForEdge(plan, TownEdge.SOUTH, centerHeight);

        assertEquals(centerHeight, route.get(route.size() - 1).y(),
                "the road should have fully ramped to the centre's own height by the time it arrives there");
    }
}
