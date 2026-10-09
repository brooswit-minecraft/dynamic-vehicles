package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-wide persistence (MINECRAFT-110 AC3) for the one-per-player
 * {@link DeliveryContract} invariant: wraps a plain {@link ContractBook}
 * and mirrors {@link DeliveryContractCodec}'s field list and order against
 * real NBT, the same mirroring pattern {@link DispatcherOfferMenu#writeExtraData}
 * uses against {@link DispatcherOfferPayloadCodec}. The plain codec is what
 * is actually unit-tested for the round trip; this class is thin
 * Minecraft-facing glue over the identical field list.
 *
 * <p>Attached to the overworld's own data storage (not any arbitrary
 * level's) because a contract is a per-player, server-wide concept, not
 * tied to the dimension the player happens to be in - the same place
 * vanilla itself keeps cross-dimension, per-server data.
 */
public final class DeliveryContractStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_contracts";

    private final ContractBook book = new ContractBook();

    public static DeliveryContractStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(DeliveryContractStorage::new, DeliveryContractStorage::load, null), NAME);
    }

    public ContractBook book() {
        return book;
    }

    private static DeliveryContractStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        DeliveryContractStorage storage = new DeliveryContractStorage();
        List<DeliveryContract> loaded = new ArrayList<>();
        ListTag list = tag.getList("Contracts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            loaded.add(new DeliveryContract(
                    new UUID(entry.getLong("PlayerIdHi"), entry.getLong("PlayerIdLo")),
                    new UUID(entry.getLong("SourceIdHi"), entry.getLong("SourceIdLo")),
                    entry.getString("Dimension"),
                    entry.getInt("RegionX"),
                    entry.getInt("RegionZ"),
                    entry.getLong("StartX"),
                    entry.getLong("StartY"),
                    entry.getLong("StartZ"),
                    entry.getDouble("Danger"),
                    entry.getDouble("Reward"),
                    entry.getLong("AcceptedAtTick"),
                    entry.getLong("DeadlineTick")));
        }
        storage.book.restore(loaded);
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (DeliveryContract contract : book.snapshot()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("PlayerIdHi", contract.playerId().getMostSignificantBits());
            entry.putLong("PlayerIdLo", contract.playerId().getLeastSignificantBits());
            entry.putLong("SourceIdHi", contract.sourceVillagerId().getMostSignificantBits());
            entry.putLong("SourceIdLo", contract.sourceVillagerId().getLeastSignificantBits());
            entry.putString("Dimension", contract.destinationDimension());
            entry.putInt("RegionX", contract.destinationRegionX());
            entry.putInt("RegionZ", contract.destinationRegionZ());
            entry.putLong("StartX", contract.destinationStartX());
            entry.putLong("StartY", contract.destinationStartY());
            entry.putLong("StartZ", contract.destinationStartZ());
            entry.putDouble("Danger", contract.danger());
            entry.putDouble("Reward", contract.reward());
            entry.putLong("AcceptedAtTick", contract.acceptedAtTick());
            entry.putLong("DeadlineTick", contract.deadlineTick());
            list.add(entry);
        }
        tag.put("Contracts", list);
        return tag;
    }
}
