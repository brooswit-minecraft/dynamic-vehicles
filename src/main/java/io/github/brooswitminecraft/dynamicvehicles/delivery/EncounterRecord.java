package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.Objects;
import java.util.UUID;

/**
 * One tracked ambush mob (MINECRAFT-130 AC4): the durable marking this
 * slice chose is a persisted id set - this record, kept by
 * {@code PillagerEncounterStorage} - rather than an NBT tag on the entity
 * itself, so cleanup can look mobs up directly by id instead of scanning
 * every loaded entity every sweep. Plain and Minecraft-free (no
 * {@code Entity}/{@code ResourceKey}) so it mirrors {@link DeliveryContract}'s
 * pattern of staying constructible from a unit test.
 *
 * @param mobId     the spawned pillager's entity UUID
 * @param dimension the dimension it was spawned in, as a plain string (e.g. {@code "minecraft:overworld"})
 * @param ownerId   the player whose contract this encounter belongs to
 * @param spawnTick the server game tick it was spawned on
 */
public record EncounterRecord(UUID mobId, String dimension, UUID ownerId, long spawnTick) {
    public EncounterRecord {
        Objects.requireNonNull(mobId, "mobId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(ownerId, "ownerId");
    }
}
