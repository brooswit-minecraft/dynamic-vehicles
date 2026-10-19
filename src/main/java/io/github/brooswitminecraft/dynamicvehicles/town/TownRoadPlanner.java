package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;

/**
 * MINECRAFT-388 review finding #3: {@code TownPlanner}'s thoroughfare geometry was a plain cross
 * through the town centre that never actually consumed the agreed {@link RoadEdgePlanner}
 * connection points, so a road reaching a region's border generally landed somewhere other than
 * the neighbour-agreed point. This builds the actual connecting path.
 *
 * <p><b>Chosen geometry (documented per the review's "your call"):</b> edge point -&gt; town centre,
 * one path per edge, each an L-shape with a single bend: first leg follows the edge's own free
 * axis until it lines up with the centre, second leg runs straight into the centre. Four such
 * spurs (NORTH/SOUTH/EAST/WEST) share their final leg along the centre lines, which is exactly
 * {@link TownPlanner#isOnThoroughfare}'s existing cross - so this is a strict refinement (the
 * segment nearest each border now demonstrably passes through that edge's agreed point) rather
 * than a competing definition.
 *
 * <p><b>Height interpolation:</b> {@link RoadEdgePlanner.EdgeConnection#heightOffset()} is the
 * SAME agreed-by-both-neighbours value {@code offsetAlongEdge} already is, so both regions sharing
 * a border commit to the identical elevation there regardless of either region's own town-centre
 * height. The path's height then ramps from that edge elevation to the caller-supplied centre
 * reference height, holding flat until enough horizontal run has accumulated to afford one more
 * block of rise/fall without exceeding {@link TownConstants#MAX_ROAD_GRADE_PERCENT} (one block per
 * {@link TownConstants#ROAD_GRADE_RUN_PER_RISE_BLOCK} run). For every span this mod actually
 * produces (edge-to-centre legs are tens to ~192 blocks; {@link TownConstants#ROAD_EDGE_HEIGHT_OFFSET_RANGE}
 * bounds the total rise at 6) there is always enough run to fully reach the centre height well
 * before the bend; a path too short for its own height delta would simply hold the last
 * grade-limited height rather than overshoot.
 */
public final class TownRoadPlanner {
    private TownRoadPlanner() {}

    public record RoadCell(int x, int z, int y) {}

    /**
     * [Review round 2, finding #4] Minor branch roads spawned off a major edge-to-centre route at
     * regular intervals, extending perpendicular to the major route into the zoned area on BOTH
     * sides for {@link TownConstants#MINOR_BRANCH_LENGTH_BLOCKS}. A documented simplification:
     * each branch holds flat at its origin cell's own interpolated height (no further grade
     * ramping along the branch itself) - the major route it springs from has already done the
     * real elevation work getting to that point.
     */
    public static List<List<RoadCell>> minorBranchesForEdge(TownPlan plan, TownEdge edge, int centerReferenceHeight) {
        return minorBranchesFromRoute(routeForEdge(plan, edge, centerReferenceHeight));
    }

    /** As above, but using the real terrain-sampled route (see
     *  {@link #routeForEdge(ServerLevel, TownPlan, TownEdge, int)}) - production's own call path. */
    public static List<List<RoadCell>> minorBranchesForEdge(ServerLevel level, TownPlan plan, TownEdge edge, int centerReferenceHeight) {
        return minorBranchesFromRoute(routeForEdge(level, plan, edge, centerReferenceHeight));
    }

    private static List<List<RoadCell>> minorBranchesFromRoute(List<RoadCell> majorRoute) {
        List<List<RoadCell>> branches = new ArrayList<>();
        int interval = TownConstants.MINOR_BRANCH_INTERVAL_BLOCKS;
        int length = TownConstants.MINOR_BRANCH_LENGTH_BLOCKS;
        for (int i = interval; i < majorRoute.size(); i += interval) {
            RoadCell origin = majorRoute.get(i);
            RoadCell prev = majorRoute.get(i - 1);
            boolean runsAlongX = prev.z() == origin.z() && prev.x() != origin.x();
            for (int side = -1; side <= 1; side += 2) {
                List<RoadCell> branch = new ArrayList<>(length);
                for (int step = 1; step <= length; step++) {
                    int bx = runsAlongX ? origin.x() : origin.x() + side * step;
                    int bz = runsAlongX ? origin.z() + side * step : origin.z();
                    branch.add(new RoadCell(bx, bz, origin.y()));
                }
                branches.add(branch);
            }
        }
        return branches;
    }

    public static List<RoadCell> routeForEdge(TownPlan plan, TownEdge edge, int centerReferenceHeight) {
        int edgeHeight = edgeHeightFor(plan, edge);
        List<int[]> xz = buildEdgeXZ(plan, edge);
        return interpolateHeights(xz, edgeHeight, centerReferenceHeight);
    }

    /**
     * [Review round 4, finding #4] Production's own terrain-aware route: the edge's own real
     * (tree-excluded) terrain height, graded toward {@code centerReferenceHeight} (itself already
     * resolved from real centre terrain by {@link #resolveSharedCenterHeight(ServerLevel, TownPlan, int)}) -
     * so a genuinely high or low column at either end of the route is reflected in the plan, rather
     * than starting from an arbitrary fixed ~{@code ROAD_EDGE_BASE_HEIGHT}. Mid-route terrain that
     * undulates between those two real endpoints is still followed by the SAME grade-limited walk
     * as the pure overload (never a straight unlimited jump) - a documented, bounded simplification:
     * this does not re-sample terrain at every intermediate cell, so an unusually dramatic mid-route
     * feature (a short, deep ravine or a sharp spike between two otherwise-ordinary endpoints) can
     * still be graded past rather than literally hugging it; {@link TownRoadConstructionService}'s
     * own per-cell support fill (now digging down to real solid ground, not just one block) is what
     * keeps the placed surface itself from floating over such a feature even when the planned grade
     * line runs above it.
     */
    public static List<RoadCell> routeForEdge(ServerLevel level, TownPlan plan, TownEdge edge, int centerReferenceHeight) {
        int edgeHeight = edgeHeightFor(level, plan, edge);
        List<int[]> xz = buildEdgeXZ(plan, edge);
        return interpolateHeights(xz, edgeHeight, centerReferenceHeight);
    }

    /** The agreed-by-both-neighbours elevation this edge's route starts from - see the class
     *  javadoc's "Height interpolation" section. Pure/level-free fallback, used by unit tests and
     *  by {@link #resolveSharedCenterHeight(TownPlan, int)}'s own pure overload. */
    public static int edgeHeightFor(TownPlan plan, TownEdge edge) {
        // Anchored to a FIXED base (TownConstants.ROAD_EDGE_BASE_HEIGHT), never to either region's
        // own town-centre height: that is what makes two neighbouring regions agree on the
        // elevation at their shared boundary even when their own local terrain genuinely differs.
        return TownConstants.ROAD_EDGE_BASE_HEIGHT + plan.edgeConnections().get(edge).heightOffset();
    }

    /**
     * [Review round 4, finding #4] The REAL (tree-excluded) terrain height at this edge's own
     * agreed absolute column, rather than an arbitrary fixed base - production's own source of
     * "deterministic shared-edge surface height" the review asks for. This is automatically
     * agreed-by-both-neighbours (never needing a fixed base to force the agreement) because both
     * regions sharing this edge resolve the exact SAME absolute (x, z) via
     * {@link TownPlan#edgeConnectionAbsolute} and therefore sample the exact same world column.
     */
    public static int edgeHeightFor(ServerLevel level, TownPlan plan, TownEdge edge) {
        int[] edgePos = plan.edgeConnectionAbsolute(edge);
        return TownTerrain.estimateGroundHeight(level, edgePos[0], edgePos[1]);
    }

    /**
     * [Review round 3, finding #6 / round 9, finding #1] The grade-limited climb from an edge's own
     * fixed-ish boundary height (near {@link TownConstants#ROAD_EDGE_BASE_HEIGHT}) can run out of
     * route length before reaching a terrain-derived town-centre height that happens to be far from
     * that base - exactly the case a high/low real centre height exposes. Rather than let each of
     * the four edges independently climb toward the raw terrain height (and so risk disagreeing
     * with each other at the shared centre point), this resolves ONE shared centre height every
     * edge's own route can actually reach within the grade policy: it clamps the desired (terrain-
     * derived) height into the intersection of what all four edges can reach from their own edge
     * height within their own route length, so passing the SAME resolved value to every
     * {@link #routeForEdge}/{@link #minorBranchesForEdge} call for this region guarantees they all
     * converge on one consistent Y at the centre. When that intersection is EMPTY (no single height
     * is reachable by all four edges at all - only possible for implausibly short routes or large
     * offsets given this mod's own constants, i.e. genuinely extreme real terrain), this safely
     * REJECTS (returns empty) rather than picking any value: a chosen height reachable by only SOME
     * edges would still leave the others ending at a genuinely different Y, exactly the divergence
     * this whole mechanism exists to prevent - "most edges agree, one doesn't" is not an acceptable
     * weaker form of "they all agree". The caller must not build any road for this region at all
     * until this returns present - retried every tick, so a later pass (different nearby terrain
     * sampling, e.g. if this mod ever supports edge-height re-evaluation) can still recover.
     */
    public static OptionalInt resolveSharedCenterHeight(TownPlan plan, int desiredCenterHeight) {
        return computeSharedCenterHeight(plan, desiredCenterHeight, edge -> edgeHeightFor(plan, edge));
    }

    /** As above, but resolving each edge's own real terrain-sampled height (see
     *  {@link #edgeHeightFor(ServerLevel, TownPlan, TownEdge)}) - production's own call path, so the
     *  convergence invariant (every edge reaches the SAME centre height) holds against real terrain
     *  too, not just the pure fixed-base edge heights. */
    public static OptionalInt resolveSharedCenterHeight(ServerLevel level, TownPlan plan, int desiredCenterHeight) {
        return computeSharedCenterHeight(plan, desiredCenterHeight, edge -> edgeHeightFor(level, plan, edge));
    }

    /** Package-private, and deliberately NOT an overload of {@code resolveSharedCenterHeight}
     *  (a same-arity overload sharing that name forces javac's overload resolution to also resolve
     *  the {@code ServerLevel}-based overload's signature even when calling THIS one, which fails
     *  the Minecraft-free {@code src/test} compilation unit that needs to inject a deliberately-
     *  disjoint {@code edgeHeight} function directly - real {@link TownPlan}-generated edge offsets
     *  are bounded tightly enough (see {@link TownConstants#ROAD_EDGE_HEIGHT_OFFSET_RANGE}) relative
     *  to a route's own achievable grade delta that the {@code lo > hi} branch below is only
     *  reachable at all via genuinely extreme REAL terrain (production's own
     *  {@link #edgeHeightFor(ServerLevel, TownPlan, TownEdge)}), which a GameTest cannot easily
     *  construct across a region's full ~192-block edge-to-centre span. */
    static OptionalInt computeSharedCenterHeight(TownPlan plan, int desiredCenterHeight, ToIntFunction<TownEdge> edgeHeight) {
        int lo = Integer.MIN_VALUE;
        int hi = Integer.MAX_VALUE;
        for (TownEdge edge : TownEdge.values()) {
            int height = edgeHeight.applyAsInt(edge);
            int steps = Math.max(0, buildEdgeXZ(plan, edge).size() - 1);
            int maxDelta = steps / TownConstants.ROAD_GRADE_RUN_PER_RISE_BLOCK;
            lo = Math.max(lo, height - maxDelta);
            hi = Math.min(hi, height + maxDelta);
        }
        if (lo > hi) {
            // [Review round 9, finding #1] No single height is reachable by ALL four edges within
            // the grade policy - genuine convergence is provably impossible here. Any single chosen
            // value (an arbitrary midpoint, or the height reachable by the most-but-not-all edges)
            // would still leave at least one edge's own route ending at a DIFFERENT Y than the
            // others - exactly the divergence this mechanism exists to prevent. Reject safely
            // instead of picking a value that only partially solves the problem.
            return OptionalInt.empty();
        }
        return OptionalInt.of(clamp(desiredCenterHeight, lo, hi));
    }

    private static List<int[]> buildEdgeXZ(TownPlan plan, TownEdge edge) {
        int[] edgePos = plan.edgeConnectionAbsolute(edge);
        int edgeX = edgePos[0];
        int edgeZ = edgePos[1];
        int centerX = plan.townCenterX();
        int centerZ = plan.townCenterZ();
        List<int[]> xz = new ArrayList<>();
        switch (edge) {
            case NORTH, SOUTH -> {
                addLineAlongZ(xz, edgeX, edgeZ, centerZ, false);
                addLineAlongX(xz, centerZ, edgeX, centerX, true);
            }
            case EAST, WEST -> {
                addLineAlongX(xz, edgeZ, edgeX, centerX, false);
                addLineAlongZ(xz, centerX, edgeZ, centerZ, true);
            }
        }
        return xz;
    }

    private static void addLineAlongZ(List<int[]> out, int fixedX, int fromZ, int toZ, boolean skipFirst) {
        int step = Integer.compare(toZ, fromZ);
        if (step == 0) {
            if (!skipFirst) {
                out.add(new int[] {fixedX, fromZ});
            }
            return;
        }
        for (int z = skipFirst ? fromZ + step : fromZ; ; z += step) {
            out.add(new int[] {fixedX, z});
            if (z == toZ) {
                break;
            }
        }
    }

    private static void addLineAlongX(List<int[]> out, int fixedZ, int fromX, int toX, boolean skipFirst) {
        int step = Integer.compare(toX, fromX);
        if (step == 0) {
            if (!skipFirst) {
                out.add(new int[] {fromX, fixedZ});
            }
            return;
        }
        for (int x = skipFirst ? fromX + step : fromX; ; x += step) {
            out.add(new int[] {x, fixedZ});
            if (x == toX) {
                break;
            }
        }
    }

    /**
     * [Review round 2, finding #5] The Chebyshev distance from an absolute block column to the
     * NEAREST point on any of the four edges' real two-leg L-shaped routes - the single shared
     * source of "how far is this column from an actual road" that {@link TownPlanner#zoneAt} and
     * {@link TownPlanner#isOnThoroughfare} both now defer to, replacing the old (and, after the
     * L-shaped routes were added, no-longer-accurate) "distance to the infinite centre cross".
     * Computed geometrically from each leg's own fixed-axis/range endpoints (the exact same
     * segments {@link #routeForEdge} itself walks, just without needing a height to do it), so
     * this is a strict read of the real route rather than a second, independently-drifting notion
     * of where the road is.
     */
    public static int distanceToNearestRoute(TownPlan plan, int blockX, int blockZ) {
        int centerX = plan.townCenterX();
        int centerZ = plan.townCenterZ();
        int best = Integer.MAX_VALUE;
        for (TownEdge edge : TownEdge.values()) {
            int[] edgePos = plan.edgeConnectionAbsolute(edge);
            int edgeX = edgePos[0];
            int edgeZ = edgePos[1];
            switch (edge) {
                case NORTH, SOUTH -> {
                    best = Math.min(best, distanceToSegment(blockX, blockZ, edgeX, edgeZ, edgeX, centerZ));
                    best = Math.min(best, distanceToSegment(blockX, blockZ, edgeX, centerZ, centerX, centerZ));
                }
                case EAST, WEST -> {
                    best = Math.min(best, distanceToSegment(blockX, blockZ, edgeX, edgeZ, centerX, edgeZ));
                    best = Math.min(best, distanceToSegment(blockX, blockZ, centerX, edgeZ, centerX, centerZ));
                }
            }
        }
        return best;
    }

    /** Chebyshev distance from a point to an axis-aligned segment (either {@code x1 == x2} or
     *  {@code z1 == z2}, exactly the shape every leg {@link #routeForEdge} builds). */
    private static int distanceToSegment(int px, int pz, int x1, int z1, int x2, int z2) {
        int nearestX = x1 == x2 ? x1 : clamp(px, Math.min(x1, x2), Math.max(x1, x2));
        int nearestZ = z1 == z2 ? z1 : clamp(pz, Math.min(z1, z2), Math.max(z1, z2));
        return Math.max(Math.abs(px - nearestX), Math.abs(pz - nearestZ));
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static List<RoadCell> interpolateHeights(List<int[]> xz, int startHeight, int endHeight) {
        List<RoadCell> cells = new ArrayList<>(xz.size());
        if (xz.isEmpty()) {
            return cells;
        }
        int prevHeight = startHeight;
        int lastChangeIndex = 0;
        cells.add(new RoadCell(xz.get(0)[0], xz.get(0)[1], prevHeight));
        for (int i = 1; i < xz.size(); i++) {
            int direction = Integer.compare(endHeight, prevHeight);
            int height = prevHeight;
            if (direction != 0 && i - lastChangeIndex >= TownConstants.ROAD_GRADE_RUN_PER_RISE_BLOCK) {
                height = prevHeight + direction;
                lastChangeIndex = i;
            }
            int[] point = xz.get(i);
            cells.add(new RoadCell(point[0], point[1], height));
            prevHeight = height;
        }
        return cells;
    }
}
