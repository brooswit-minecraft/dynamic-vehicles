package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * The plain cleanup/expiry predicate for one tracked ambush mob
 * (MINECRAFT-130 AC4-5, the epic's own singled-out AC). Takes only plain
 * state - owner id is the caller's concern, not this predicate's; this
 * class sees spawn tick, current tick, and three booleans the thin
 * Minecraft layer ({@code PillagerAmbushHandler}) resolves - so it is
 * directly unit-testable including the logout and contract-end cases
 * (AC7d), with no {@code Entity}, {@code UUID}, or server lookup involved.
 *
 * <p>Despawn is wanted when EITHER the mob has outlived
 * {@code maxLifetimeTicks} (the hard backstop against indefinite
 * accumulation, AC4, covering the "wandered far away / unloaded chunk,
 * never resolved otherwise" case) OR the owner is gone - offline (the
 * logout case) or no longer holding an active contract (the contract
 * COMPLETED/EXPIRED case). Either way, a nearby non-owner player vetoes the
 * despawn (AC5: never pull a mob out from under someone fighting it), on
 * the theory that proximity is the only signal available to the thin layer
 * cheaply enough to check every sweep.
 */
public final class EncounterCleanup {

    private EncounterCleanup() {
    }

    public static boolean shouldDespawn(
            long spawnTick,
            long currentTick,
            long maxLifetimeTicks,
            boolean ownerContractActive,
            boolean ownerOnline,
            boolean otherPlayerNearby) {
        boolean stale = currentTick - spawnTick >= maxLifetimeTicks;
        boolean ownerGone = !ownerOnline || !ownerContractActive;
        return (stale || ownerGone) && !otherPlayerNearby;
    }
}
