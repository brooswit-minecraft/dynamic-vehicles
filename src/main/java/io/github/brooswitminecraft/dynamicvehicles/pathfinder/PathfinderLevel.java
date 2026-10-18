package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * The five Pathfinder profession levels and the region ring each one's
 * circuit runs through, per MINECRAFT-379's settled design (the user's final
 * change, "no five-region-out now", removed what would have been a 6th,
 * 40-region ring — it is not represented here).
 *
 * <p>Ring size follows the standard Chebyshev-ring perimeter formula
 * (8 * ring for ring &gt;= 1), which is exactly the 8/16/24/32 progression
 * the design specifies for levels 2-5.
 *
 * <p>Level 1 is the one case NOT modeled as a ring of regions: its circuit
 * runs entirely inside the origin region ({@link #ring} is 0), as an
 * eight-checkpoint local loop — John approved this count/layout on
 * 2026-10-18, so it is a settled count, not a placeholder (see git log /
 * ticket comments for the approval).
 */
public enum PathfinderLevel {
    LEVEL_1(1, 0, 8),
    LEVEL_2(2, 1, 8),
    LEVEL_3(3, 2, 16),
    LEVEL_4(4, 3, 24),
    LEVEL_5(5, 4, 32);

    private final int level;
    private final int ring;
    private final int checkpointCount;

    PathfinderLevel(int level, int ring, int checkpointCount) {
        this.level = level;
        this.ring = ring;
        this.checkpointCount = checkpointCount;
    }

    public int level() {
        return level;
    }

    /** The Chebyshev ring distance (in regions) from the origin region this level's circuit runs through. */
    public int ring() {
        return ring;
    }

    /** Number of regional checkpoints in this level's circuit (one per ring region, except level 1). */
    public int checkpointCount() {
        return checkpointCount;
    }

    public boolean isConfinedToOriginRegion() {
        return this == LEVEL_1;
    }
}
