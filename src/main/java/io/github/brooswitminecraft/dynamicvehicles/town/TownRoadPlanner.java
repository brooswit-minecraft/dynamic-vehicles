package io.github.brooswitminecraft.dynamicvehicles.town;

import java.util.ArrayList;
import java.util.List;

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

    public static List<RoadCell> routeForEdge(TownPlan plan, TownEdge edge, int centerReferenceHeight) {
        int[] edgePos = plan.edgeConnectionAbsolute(edge);
        int edgeX = edgePos[0];
        int edgeZ = edgePos[1];
        int centerX = plan.townCenterX();
        int centerZ = plan.townCenterZ();
        // Anchored to a FIXED base (TownConstants.ROAD_EDGE_BASE_HEIGHT), never to the caller's own
        // centerReferenceHeight: that is what makes two neighbouring regions agree on the elevation
        // at their shared boundary even when their own local terrain (and so their own centre
        // heights) genuinely differ - see the class javadoc.
        int edgeHeight = TownConstants.ROAD_EDGE_BASE_HEIGHT + plan.edgeConnections().get(edge).heightOffset();

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
        return interpolateHeights(xz, edgeHeight, centerReferenceHeight);
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
