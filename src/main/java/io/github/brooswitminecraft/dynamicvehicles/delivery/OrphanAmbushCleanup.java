package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * The plain backstop predicate for a tagged ambush mob rejoining a level
 * (chunk load) with no matching {@link EncounterRecord} (MINECRAFT-130
 * review): {@code PillagerAmbushHandler}'s primary mechanism is the
 * persisted id set in {@link PillagerEncounterStorage}, resolved by
 * {@link EncounterCleanup} every sweep while the mob is loaded - but a mob
 * whose record was lost by some other means (never meant to happen under
 * normal operation, but cheap to guard against) would otherwise sit
 * forever as an untracked, un-cleaned-up pillager once its chunk reloads.
 * The entity tag ({@code dynamicvehicles_ambush}, added at spawn time and
 * persisted in the entity's own NBT) is what lets the join-level listener
 * recognize it as "one of ours" even with no record to consult.
 */
public final class OrphanAmbushCleanup {

    private OrphanAmbushCleanup() {
    }

    /**
     * @param hasLiveRecord     whether a matching {@link EncounterRecord} was found for this tagged mob
     * @param otherPlayerNearby whether any player is within the configured nearby-player radius (AC5: never discard out from under someone)
     * @return true only when there is no live record AND no one is nearby - a mob with a live record is left to the normal sweep/predicate instead
     */
    public static boolean shouldDiscardOrphan(boolean hasLiveRecord, boolean otherPlayerNearby) {
        return !hasLiveRecord && !otherPlayerNearby;
    }
}
