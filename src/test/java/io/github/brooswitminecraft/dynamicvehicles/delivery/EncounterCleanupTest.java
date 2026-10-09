package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-130 AC4/AC5/AC7d: the cleanup/expiry predicate over plain state,
 * including the logout and contract-end (COMPLETED/EXPIRED) cases.
 */
class EncounterCleanupTest {

    private static final long SPAWN_TICK = 1000;
    private static final long MAX_LIFETIME = 6000;

    @Test
    void staysAlive_whileOwnerOnlineAndContractStillActive_andNotStale_andNoOneElseNearby() {
        assertFalse(EncounterCleanup.shouldDespawn(SPAWN_TICK, SPAWN_TICK + 100, MAX_LIFETIME, true, true, false));
    }

    @Test
    void despawns_onContractEnd_completedOrExpired_whenNoOneElseNearby() {
        // ContractBook.evaluate already removed the contract by the time this is checked, so
        // ownerContractActive is false for both the COMPLETED and the EXPIRED case alike.
        assertTrue(EncounterCleanup.shouldDespawn(SPAWN_TICK, SPAWN_TICK + 100, MAX_LIFETIME, false, true, false));
    }

    @Test
    void despawns_onLogout_evenWhileTheContractItselfIsStillActive() {
        assertTrue(EncounterCleanup.shouldDespawn(SPAWN_TICK, SPAWN_TICK + 100, MAX_LIFETIME, true, false, false));
    }

    @Test
    void despawns_oncePastTheMaxLifetimeBackstop_evenIfTheOwnerIsStillPresentWithAnActiveContract() {
        assertFalse(EncounterCleanup.shouldDespawn(SPAWN_TICK, SPAWN_TICK + MAX_LIFETIME - 1, MAX_LIFETIME, true, true, false));
        assertTrue(EncounterCleanup.shouldDespawn(SPAWN_TICK, SPAWN_TICK + MAX_LIFETIME, MAX_LIFETIME, true, true, false));
    }

    @Test
    void aNearbyOtherPlayerVetoesDespawnOnContractEnd_doesNotPullAMobOutFromUnderThem() {
        assertFalse(EncounterCleanup.shouldDespawn(SPAWN_TICK, SPAWN_TICK + 100, MAX_LIFETIME, false, true, true));
    }

    @Test
    void aNearbyOtherPlayerVetoesDespawnOnLogout() {
        assertFalse(EncounterCleanup.shouldDespawn(SPAWN_TICK, SPAWN_TICK + 100, MAX_LIFETIME, true, false, true));
    }

    @Test
    void aNearbyOtherPlayerVetoesDespawnEvenPastTheMaxLifetimeBackstop() {
        assertFalse(EncounterCleanup.shouldDespawn(SPAWN_TICK, SPAWN_TICK + MAX_LIFETIME, MAX_LIFETIME, true, true, true));
    }
}
