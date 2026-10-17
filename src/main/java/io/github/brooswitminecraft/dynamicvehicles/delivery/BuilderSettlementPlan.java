package io.github.brooswitminecraft.dynamicvehicles.delivery;

/** Minecraft-free Builder donation and placement decisions. */
public final class BuilderSettlementPlan {
    public static final int HOUSE_FOOTPRINT_WIDTH = 5;
    public static final int HOUSE_HEIGHT = 4;
    /** Ten seconds at Minecraft's normal 20 ticks per second. */
    static final long PASSIVE_CHECK_INTERVAL_TICKS = 200L;
    public enum BuildingVariant { OAK_HUT, SPRUCE_HUT }
    private BuilderSettlementPlan() {}

    /** Both Builder level and the completed donation tier contribute; level five/tier five guarantees the roll. */
    public static double donationSettlementChance(int villagerLevel, int tradeTier) {
        return (Math.max(0, Math.min(4, villagerLevel - 1)) / 4.0D)
                * (Math.max(0, Math.min(4, tradeTier - 1)) / 4.0D);
    }

    /**
     * Passive checks run every ten seconds, so their rates are deliberately much lower than donation rates:
     * 0%, .02%, .04%, .06%, and .08% for levels one through five.  At the maximum level, 120 checks in
     * a Minecraft day yield roughly a 9.2% chance of at least one settlement attempt, rather than 10% per check.
     */
    public static double passiveSettlementChance(int villagerLevel) {
        return Math.max(0, Math.min(4, villagerLevel - 1)) / 5000.0D;
    }

    /** The passive clock is server game time: one eligible check per ten seconds, never a catch-up loop. */
    public static boolean shouldRunPassiveCheck(boolean hasLastCheck, long lastCheckGameTime, long gameTime) {
        return !hasLastCheck || (gameTime >= lastCheckGameTime
                && gameTime - lastCheckGameTime >= PASSIVE_CHECK_INTERVAL_TICKS);
    }

    /** Both triggers share this final commit gate; after the first success the consumed anchor rejects a duplicate. */
    public static boolean shouldCompleteSettlement(boolean anchorPresent, boolean rollSucceeded, boolean placementIsSafe) {
        return anchorPresent && rollSucceeded && placementIsSafe;
    }

    /**
     * AC4: the building must stand in the campfire's own chunk. True only when every block column of the
     * inclusive box (minX..maxX, minZ..maxZ) shares the campfire's chunk; chunks are 16 wide and the
     * arithmetic shift floors, so negative coordinates are handled.
     */
    public static boolean isWithinCampfireChunk(int campfireX, int campfireZ, int minX, int minZ, int maxX, int maxZ) {
        int chunkX = campfireX >> 4;
        int chunkZ = campfireZ >> 4;
        return (minX >> 4) == chunkX && (maxX >> 4) == chunkX
                && (minZ >> 4) == chunkZ && (maxZ >> 4) == chunkZ;
    }

    /**
     * The house is centred on the campfire's own chunk, not offset from the campfire itself: chunks are
     * 16 blocks wide (0..15 locally), so the dead centre of the chunk containing {@code campfireCoordinate}
     * is its chunk-floor coordinate times 16 plus 8. The arithmetic shift floors, so this holds for
     * negative coordinates too.
     */
    public static int chunkCenterCoordinate(int campfireCoordinate) {
        return (campfireCoordinate >> 4) * 16 + 8;
    }

    /**
     * Since the house is centred on the chunk rather than offset away from the campfire, the originating
     * campfire itself can now land inside the house's own footprint (for example when it already sits at
     * the chunk's centre). True when (x, z) is within HOUSE_FOOTPRINT_WIDTH's radius of (centerX, centerZ).
     */
    public static boolean isWithinHouseFootprint(int centerX, int centerZ, int x, int z) {
        int radius = (HOUSE_FOOTPRINT_WIDTH - 1) / 2;
        return Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
    }

    /** Vanilla days are 24000 ticks and noon is tick 6000. */
    public static boolean isNoon(long dayTime) {
        return Math.floorMod(dayTime, 24000L) == 6000L;
    }

    /** A persisted day marker makes a noon pass retry-safe across tick and save boundaries. */
    public static boolean shouldRunForDay(long lastProcessedDay, long currentDay) {
        return lastProcessedDay != currentDay;
    }

    /** A campfire is removed only after the replacement structure is known safe. */
    public static boolean shouldReplaceCampfire(boolean placementIsSafe, boolean rollSucceeded) {
        return placementIsSafe && rollSucceeded;
    }

    /** A complete settlement also retires the Builder; failures leave it unchanged. */
    public static boolean shouldResetBuilder(boolean placementIsSafe, boolean rollSucceeded) {
        return shouldReplaceCampfire(placementIsSafe, rollSucceeded);
    }

    /** Maps a supplied bounded random index to a project-owned building variant. */
    public static BuildingVariant selectBuildingVariant(int randomIndex) {
        return BuildingVariant.values()[Math.floorMod(randomIndex, BuildingVariant.values().length)];
    }

    /** The bounded marker prevents a villager from repeatedly founding at one campfire. */
    public static boolean shouldCreateCampfire(boolean unemployed, boolean bedless, boolean darkNight,
            boolean skyVisible, boolean campfireAlreadyRecorded) {
        return unemployed && bedless && darkNight && skyVisible && !campfireAlreadyRecorded;
    }
}
