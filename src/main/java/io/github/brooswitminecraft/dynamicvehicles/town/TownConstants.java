package io.github.brooswitminecraft.dynamicvehicles.town;

/**
 * Every tunable Dynamic Towns constant in one place, per MINECRAFT-388's "resolve/document as
 * implementation owner" instruction (same pattern as MINECRAFT-395). Each value is a deliberate,
 * documented implementation choice, not a guess buried where it's hard to find; see the
 * MINECRAFT-388 PR description's "Design choices made" section for the one-line rationale index.
 */
public final class TownConstants {
    private TownConstants() {}

    /**
     * {@code delivery.RegionCoord} (the type MINECRAFT-388 says to reuse) has NO fixed block
     * size of its own: its granularity is the live vanilla village {@code RandomSpreadStructurePlacement}'s
     * {@code spacing()}, which can differ by dimension/datapack and is only knowable by asking a
     * loaded {@code ServerLevel} (see {@code VillagePlacementService.toRegion}). Town planning
     * needs a fixed, world-independent size to be pure and unit-testable (determinism tests,
     * negative-coordinate tests, edge-agreement tests — none of which can depend on a live level).
     * This is the one genuinely open decision flagged in the PR's "Remaining open decisions"
     * section: a second, DynamicTowns-only region size, deliberately distinct from both
     * {@code delivery.RegionCoord}'s variable structure-placement spacing and
     * {@code pathfinder.PathfinderRegion.SIZE_BLOCKS} (512). 384 blocks (24 chunks) is chosen as a
     * size comfortably larger than a vanilla village's own footprint while staying small enough
     * that road edge points and zoning read as deliberate, not sparse.
     */
    public static final int REGION_SIZE_BLOCKS = 384;

    /** Road width in blocks for the two region-crossing thoroughfares (N-S and E-W). */
    public static final int MAJOR_ROAD_WIDTH = 5;
    /** Road width in blocks for minor roads branching off a thoroughfare into a zone. */
    public static final int MINOR_ROAD_WIDTH = 3;
    /** Maximum rise per block a road may interpolate before the plan calls for a retaining step. */
    public static final int MAX_ROAD_GRADE_PERCENT = 20;

    /** Residential : commercial : industrial footprint ratio, residential-heavy since every
     *  villager needs housing but only some need a workplace. */
    public static final int RESIDENTIAL_ZONE_SHARE = 5;
    public static final int COMMERCIAL_ZONE_SHARE = 3;
    public static final int INDUSTRIAL_ZONE_SHARE = 2;

    /** The storehouse is a single fixed 7x7 template with 3 roof-height variations selected
     *  deterministically from the region's own seed, so neighbouring towns don't all look identical
     *  without needing an actual template catalog (explicitly out of scope per the ticket). */
    public static final int STOREHOUSE_FOOTPRINT_WIDTH = 7;
    public static final int STOREHOUSE_VARIATIONS = 3;

    // --- Donation conversion rates: category credit per single donated item, more processed
    // material contributing more. Mirrors BuilderDonationTrades' tiering (oak_log < cobblestone <
    // sandstone < bricks < quartz_block) but generalized to town categories rather than a single
    // narrow chance roll.
    public static final int DONATION_DIRT_STONE_CREDIT = 1;
    public static final int DONATION_COBBLESTONE_STONE_CREDIT = 2;
    public static final int DONATION_STONE_BRICKS_STONE_CREDIT = 4;
    public static final int DONATION_SMOOTH_STONE_STONE_CREDIT = 4;
    public static final int DONATION_CHISELED_STONE_BRICKS_STONE_CREDIT = 6;
    public static final int DONATION_OAK_LOG_WOOD_CREDIT = 2;
    public static final int DONATION_OAK_PLANKS_WOOD_CREDIT = 1;
    public static final int DONATION_WHEAT_FOOD_CREDIT = 1;
    public static final int DONATION_BREAD_FOOD_CREDIT = 3;
    public static final int DONATION_CARROT_FOOD_CREDIT = 1;

    // --- Clearing/leveling return rates: fraction of a full donation credit, since clearing is
    // incidental salvage rather than a deliberate contribution.
    public static final int CLEARING_STONE_RETURN = 1;
    public static final int CLEARING_WOOD_RETURN = 1;
    /** Dirt returns a SMALL amount of stone (per the ticket), not a full stone credit. */
    public static final int CLEARING_DIRT_STONE_RETURN = 1;
    public static final int CLEARING_DIRT_BLOCKS_PER_STONE_CREDIT = 4;
    /** Leaves return a SMALL amount of wood. */
    public static final int CLEARING_LEAVES_WOOD_RETURN = 1;
    public static final int CLEARING_LEAVES_BLOCKS_PER_WOOD_CREDIT = 8;

    /**
     * Passive production tick interval and trickle amounts. Reuses
     * {@code BuilderSettlementPlan.PASSIVE_CHECK_INTERVAL_TICKS} semantics (a ten-second
     * elapsed-time gate, not a catch-up loop) scaled up to a per-minute town-wide trickle so a
     * town with no donations at all still slowly grows: one credit of each category per minute
     * per claimed, employed villager in the town, capped so an enormous town can't runaway-produce.
     */
    public static final long PASSIVE_PRODUCTION_INTERVAL_TICKS = 1200L; // one Minecraft minute
    public static final int PASSIVE_PRODUCTION_PER_WORKER = 1;
    public static final int PASSIVE_PRODUCTION_WORKER_CAP = 12;

    /** Construction pacing: one planned block consumes this many category-resource credits, and
     *  at most this many blocks are placed per construction tick, so a well-stocked town still
     *  builds visibly rather than instantly, while an empty store stops cold with no partial charge. */
    public static final int CONSTRUCTION_COST_PER_BLOCK = 1;
    public static final int CONSTRUCTION_BLOCKS_PER_TICK = 4;
    public static final long CONSTRUCTION_TICK_INTERVAL_TICKS = 20L; // one second

    /** Search radius (blocks) used to look for a villager-claimed campfire's job-site memory. */
    public static final int CAMPFIRE_CLAIM_SEARCH_RADIUS = 2;

    /** Zoning parcel size: one zoning decision per 16x16 column block, aligned to chunk size so a
     *  parcel never straddles a chunk boundary. */
    public static final int PARCEL_SIZE_BLOCKS = 16;
}
