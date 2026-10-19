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

    /**
     * MINECRAFT-388 review round 3, finding #6: the old behaviour let each of the four edges
     * independently climb from the ~{@code ROAD_EDGE_BASE_HEIGHT}-ish boundary height toward the
     * SAME raw terrain-derived centre height, stopping early (grade-limited) if its own route
     * happened to be too short - meaning two edges with different route lengths could land at
     * different Y on the same X/Z at the town centre. {@link TownRoadPlanner#resolveSharedCenterHeight}
     * must resolve ONE height every edge's own route can actually reach, so all four
     * {@link TownRoadPlanner#routeForEdge} calls fed that SAME resolved value always agree -
     * exercised here with height deltas (high AND low) far larger than the mild ones the other
     * tests in this class use.
     */
    @Test
    void allFourEdgesConvergeOnTheSameSharedCenterHeightEvenWithALargeHeightDelta() {
        RegionCoord region = new RegionCoord(4, -9);
        TownPlan plan = TownPlanner.generate(region, SEED);

        // Deliberately extreme, far outside ROAD_EDGE_HEIGHT_OFFSET_RANGE of the ~64 base - the
        // exact "high/low terrain-derived centre" scenario the review flagged.
        assertAllFourEdgesAgreeAtTheCenter(plan, 300);
        assertAllFourEdgesAgreeAtTheCenter(plan, -40);
    }

    private static void assertAllFourEdgesAgreeAtTheCenter(TownPlan plan, int desiredCenterHeight) {
        int resolved = TownRoadPlanner.resolveSharedCenterHeight(plan, desiredCenterHeight);
        Integer commonHeight = null;
        for (TownEdge edge : TownEdge.values()) {
            List<TownRoadPlanner.RoadCell> route = TownRoadPlanner.routeForEdge(plan, edge, resolved);
            int endpointHeight = route.get(route.size() - 1).y();
            if (commonHeight == null) {
                commonHeight = endpointHeight;
            } else {
                assertEquals(commonHeight, endpointHeight,
                        "edge " + edge + " must end at the same centre height every other edge does for desired height "
                                + desiredCenterHeight);
            }
            // Every edge's own grade limit must still be honoured along the way to that shared height.
            for (int i = 1; i < route.size(); i++) {
                int rise = Math.abs(route.get(i).y() - route.get(i - 1).y());
                assertTrue(rise <= 1, "a single road cell must never rise/fall by more than one block");
            }
        }
    }

    /** MINECRAFT-388 review round 2, finding #4: minor branch roads must actually exist (production
     *  had none at all before the fix), spaced off the major route, each a contiguous path of the
     *  configured length. */
    @Test
    void minorBranchesExistAndAreContiguousPathsOfTheConfiguredLength() {
        RegionCoord region = new RegionCoord(1, -2);
        TownPlan plan = TownPlanner.generate(region, SEED);
        List<List<TownRoadPlanner.RoadCell>> branches = TownRoadPlanner.minorBranchesForEdge(plan, TownEdge.NORTH, 70);

        assertTrue(!branches.isEmpty(), "expected at least one minor branch off a region-length major route");
        for (List<TownRoadPlanner.RoadCell> branch : branches) {
            assertEquals(TownConstants.MINOR_BRANCH_LENGTH_BLOCKS, branch.size());
            for (int i = 1; i < branch.size(); i++) {
                int dx = Math.abs(branch.get(i).x() - branch.get(i - 1).x());
                int dz = Math.abs(branch.get(i).z() - branch.get(i - 1).z());
                assertTrue(dx + dz == 1, "branch cells must be contiguous, found " + branch.get(i - 1) + " -> " + branch.get(i));
            }
        }
    }

    /** Branches must run PERPENDICULAR to the major route they spring from, both to the left and
     *  the right - never doubling back along the major route's own axis. */
    @Test
    void minorBranchesRunPerpendicularToTheMajorRouteOnBothSides() {
        RegionCoord region = new RegionCoord(0, 0);
        TownPlan plan = TownPlanner.generate(region, SEED);
        List<TownRoadPlanner.RoadCell> majorRoute = TownRoadPlanner.routeForEdge(plan, TownEdge.EAST, 65);
        List<List<TownRoadPlanner.RoadCell>> branches = TownRoadPlanner.minorBranchesForEdge(plan, TownEdge.EAST, 65);
        assertTrue(branches.size() >= 2, "expected branches on both sides of at least one interval point");

        int interval = TownConstants.MINOR_BRANCH_INTERVAL_BLOCKS;
        TownRoadPlanner.RoadCell origin = majorRoute.get(interval);
        TownRoadPlanner.RoadCell prev = majorRoute.get(interval - 1);
        boolean majorRunsAlongX = prev.z() == origin.z() && prev.x() != origin.x();

        for (List<TownRoadPlanner.RoadCell> branch : branches.subList(0, 2)) {
            TownRoadPlanner.RoadCell first = branch.get(0);
            if (majorRunsAlongX) {
                assertEquals(origin.x(), first.x(), "a branch off an X-running major route must hold X fixed");
                assertTrue(first.z() != origin.z(), "a branch off an X-running major route must vary Z");
            } else {
                assertEquals(origin.z(), first.z(), "a branch off a Z-running major route must hold Z fixed");
                assertTrue(first.x() != origin.x(), "a branch off a Z-running major route must vary X");
            }
        }
    }

    /**
     * MINECRAFT-388 review round 2, finding #5: zoning/thoroughfare distance must be measured
     * against the REAL L-shaped route, not the obsolete infinite centre cross those routes
     * superseded. A point sitting exactly on a route's own first leg (near the region's edge, away
     * from the centre) is the clearest place the two formulas can disagree: the real route reads
     * it as distance 0, while the old cross - unless this edge happens to be degenerate for this
     * seed/region - reads it as far away.
     */
    @Test
    void distanceToNearestRouteReadsZeroExactlyOnTheRoutesFirstLegUnlikeTheObsoleteCenterCross() {
        RegionCoord region = new RegionCoord(9, -4);
        TownPlan plan = TownPlanner.generate(region, 321L);
        TownEdge edge = TownEdge.NORTH;
        int[] edgeAbsolute = plan.edgeConnectionAbsolute(edge);
        int edgeX = edgeAbsolute[0];
        int edgeZ = edgeAbsolute[1];
        int centerX = plan.townCenterX();
        int centerZ = plan.townCenterZ();
        int midZ = (edgeZ + centerZ) / 2;

        int onRouteDistance = TownRoadPlanner.distanceToNearestRoute(plan, edgeX, midZ);
        assertEquals(0, onRouteDistance, "a point exactly on the route's own first leg must read as distance 0 from it");

        org.junit.jupiter.api.Assumptions.assumeTrue(edgeX != centerX,
                "this edge's connection point happens to sit exactly on the old centre line for this seed/region - degenerate, proves nothing");
        int obsoleteCrossDistance = Math.min(Math.abs(edgeX - centerX), Math.abs(midZ - centerZ));
        assertTrue(obsoleteCrossDistance > onRouteDistance,
                "the obsolete centre-cross formula must disagree with the real route at this offset edge point (old="
                        + obsoleteCrossDistance + " new=" + onRouteDistance + ")");
        assertTrue(TownPlanner.isOnThoroughfare(plan, edgeX, midZ),
                "a point exactly on the real route must read as on the thoroughfare");
    }
}
