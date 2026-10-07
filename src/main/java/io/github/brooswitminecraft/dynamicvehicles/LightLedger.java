package io.github.brooswitminecraft.dynamicvehicles;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension record of the invisible light blocks the headlamps placed, so a crash cannot leave them behind. */
final class LightLedger extends SavedData {
    private static final String NAME = "dynamicvehicles_lamps";
    private final LongOpenHashSet placed = new LongOpenHashSet();

    static LightLedger of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(LightLedger::new, LightLedger::load, null), NAME);
    }

    private static LightLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        LightLedger ledger = new LightLedger();
        for (long pos : tag.getLongArray("Placed")) {
            ledger.placed.add(pos);
        }
        return ledger;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("Placed", placed.toLongArray());
        return tag;
    }

    void add(BlockPos pos) {
        if (placed.add(pos.asLong())) {
            setDirty();
        }
    }

    void remove(BlockPos pos) {
        if (placed.remove(pos.asLong())) {
            setDirty();
        }
    }

    /** On server start: remove every light block recorded by a previous run, then clear the record. */
    static void sweep(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            LightLedger ledger = of(level);
            if (ledger.placed.isEmpty()) {
                continue;
            }
            for (long packed : ledger.placed.toLongArray()) {
                BlockPos pos = BlockPos.of(packed);
                if (level.getBlockState(pos).is(Blocks.LIGHT)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                }
            }
            DynamicVehiclesMod.LOGGER.info("Removed {} leftover headlamp light blocks in {}", ledger.placed.size(), level.dimension().location());
            ledger.placed.clear();
            ledger.setDirty();
        }
    }
}
