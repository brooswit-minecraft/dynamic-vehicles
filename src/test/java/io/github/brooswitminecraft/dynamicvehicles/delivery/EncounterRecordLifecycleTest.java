package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MINECRAFT-130 review round 2: killed/vanilla-despawned mobs must drop their record; merely-unloaded ones must not. */
class EncounterRecordLifecycleTest {

    @Test
    void killed_dropsTheRecord() {
        assertTrue(EncounterRecordLifecycle.shouldDropRecord(EncounterRecordLifecycle.RemovalReason.KILLED));
    }

    @Test
    void discarded_dropsTheRecord() {
        assertTrue(EncounterRecordLifecycle.shouldDropRecord(EncounterRecordLifecycle.RemovalReason.DISCARDED));
    }

    @Test
    void unloadedToChunk_keepsTheRecord_theMobStillExists() {
        assertFalse(EncounterRecordLifecycle.shouldDropRecord(EncounterRecordLifecycle.RemovalReason.UNLOADED_TO_CHUNK));
    }

    @Test
    void unloadedWithPlayer_keepsTheRecord() {
        assertFalse(EncounterRecordLifecycle.shouldDropRecord(EncounterRecordLifecycle.RemovalReason.UNLOADED_WITH_PLAYER));
    }

    @Test
    void changedDimension_keepsTheRecord() {
        assertFalse(EncounterRecordLifecycle.shouldDropRecord(EncounterRecordLifecycle.RemovalReason.CHANGED_DIMENSION));
    }
}
