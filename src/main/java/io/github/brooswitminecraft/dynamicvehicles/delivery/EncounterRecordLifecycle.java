package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * The plain predicate deciding whether an ambush mob's permanent removal
 * from the world should drop its {@link EncounterRecord} (MINECRAFT-130
 * review, round 2): the record must be dropped once the mob is genuinely
 * gone forever (killed, or discarded by our own cleanup) so
 * {@link PillagerEncounterStorage} doesn't grow without bound over a
 * long-lived world - but must NOT be dropped for a removal that is really
 * just the mob leaving this particular loaded view of it (its chunk
 * unloading, the player carrying it across a server-side unload, or a
 * dimension change), since the mob still exists and the normal sweep needs
 * the record to find it again later.
 *
 * <p>Mirrors {@code Entity.RemovalReason}'s own cases as a plain enum
 * (rather than taking the real Minecraft type) so this stays
 * Minecraft-free and the mapping from vanilla's reason to "drop or keep"
 * is explicit and independently testable, instead of trusting vanilla's
 * own {@code shouldDestroy()} flag blindly.
 */
public final class EncounterRecordLifecycle {

    public enum RemovalReason {
        KILLED,
        DISCARDED,
        UNLOADED_TO_CHUNK,
        UNLOADED_WITH_PLAYER,
        CHANGED_DIMENSION
    }

    private EncounterRecordLifecycle() {
    }

    /** @return true only for a removal that means the mob is gone forever (KILLED, DISCARDED) */
    public static boolean shouldDropRecord(RemovalReason reason) {
        return reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED;
    }
}
