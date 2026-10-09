package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-wide persistence (MINECRAFT-130 AC4) for the set of {@link EncounterRecord}s
 * this slice uses to mark ambush mobs as encounter-scoped: the PRIMARY
 * durable marking, a persisted id set, so the sweep in
 * {@code PillagerAmbushHandler} looks mobs up by id instead of scanning
 * every loaded entity. (A secondary, per-entity NBT tag also exists - see
 * {@code PillagerAmbushHandler}'s {@code AMBUSH_TAG} - purely as a backstop
 * for a record lost by some other means; this id set is what the sweep
 * actually resolves against.) Mirrors {@link DeliveryContractStorage}'s own
 * attach-to-the-overworld pattern - a distinct {@code SavedData}, never a
 * second contract store, since it tracks mobs, not contracts.
 *
 * <p>Surviving a server restart falls directly out of this being a
 * {@code SavedData}: the id set reloads from disk before any sweep runs, so
 * a mid-encounter restart finds the same records it would have had anyway.
 */
public final class PillagerEncounterStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_encounters";

    private final List<EncounterRecord> records = new ArrayList<>();

    public static PillagerEncounterStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(PillagerEncounterStorage::new, PillagerEncounterStorage::load, null), NAME);
    }

    /** A live, mutation-safe snapshot: iterate this, not {@link #records}, while deciding removals. */
    public List<EncounterRecord> snapshot() {
        return List.copyOf(records);
    }

    /** Used by the join-level orphan check (MINECRAFT-130 review): is this tagged mob still a live, tracked encounter? */
    public Optional<EncounterRecord> find(UUID mobId) {
        return records.stream().filter(record -> record.mobId().equals(mobId)).findFirst();
    }

    public void add(EncounterRecord record) {
        records.add(record);
        setDirty();
    }

    public void remove(UUID mobId) {
        if (records.removeIf(record -> record.mobId().equals(mobId))) {
            setDirty();
        }
    }

    private static PillagerEncounterStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        PillagerEncounterStorage storage = new PillagerEncounterStorage();
        ListTag list = tag.getList("Encounters", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            storage.records.add(new EncounterRecord(
                    new UUID(entry.getLong("MobIdHi"), entry.getLong("MobIdLo")),
                    entry.getString("Dimension"),
                    new UUID(entry.getLong("OwnerIdHi"), entry.getLong("OwnerIdLo")),
                    entry.getLong("SpawnTick")));
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (EncounterRecord record : records) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("MobIdHi", record.mobId().getMostSignificantBits());
            entry.putLong("MobIdLo", record.mobId().getLeastSignificantBits());
            entry.putString("Dimension", record.dimension());
            entry.putLong("OwnerIdHi", record.ownerId().getMostSignificantBits());
            entry.putLong("OwnerIdLo", record.ownerId().getLeastSignificantBits());
            entry.putLong("SpawnTick", record.spawnTick());
            list.add(entry);
        }
        tag.put("Encounters", list);
        return tag;
    }
}
