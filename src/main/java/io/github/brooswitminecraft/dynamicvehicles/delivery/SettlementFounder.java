package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Bounded arrival-side settlement founder. It owns only the dispatch-board
 * interface, its Bell, and the small initial population; it deliberately does
 * not generate a hut or any other structure.
 */
public final class SettlementFounder {
    private SettlementFounder() {
    }

    /**
     * @return whether arrival can complete. An unsafe unloaded/occupied site remains
     *         an active delivery so a later sweep can retry without losing its contract.
     */
    public static boolean onArrival(ServerPlayer player, DeliveryContract contract, DeliveryContractStorage storage) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        BlockPos target = new BlockPos(contract.destinationStartX(), contract.destinationStartY(), contract.destinationStartZ());
        if (!level.hasChunkAt(target)) {
            return false;
        }
        boolean existingVillage = level.structureManager().getStructureWithPieceAt(target, StructureTags.VILLAGE).isValid();
        String key = settlementKey(contract);
        BlockPos boardPos = new BlockPos(target.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ()), target.getZ());
        BlockPos bellPos = boardPos.above();
        boolean safeSite = level.isEmptyBlock(boardPos) && level.isEmptyBlock(bellPos);

        SettlementFoundationPlan.Decision decision = SettlementFoundationPlan.decide(existingVillage, storage.hasFoundedSettlement(key), safeSite);
        if (decision == SettlementFoundationPlan.Decision.EXISTING_VILLAGE
                || decision == SettlementFoundationPlan.Decision.ALREADY_FOUNDED) {
            return true;
        }
        if (decision == SettlementFoundationPlan.Decision.UNSAFE_SITE) {
            return false;
        }

        // Persist the one-way claim before mutating the world. Repeated arrival,
        // reconnect, and restart paths then cannot create a second settlement or celebration.
        storage.markSettlementFounded(key);
        storage.setDirty();

        level.setBlock(boardPos, DispatcherBlocks.DISPATCH_BOARD.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(bellPos, Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
        for (int i = 0; i < SettlementFoundationPlan.villagerCount(key); i++) {
            Villager villager = EntityType.VILLAGER.create(level, null, boardPos, MobSpawnType.EVENT, false, false);
            if (villager != null) {
                villager.moveTo(boardPos.getX() + 0.5D, boardPos.getY() + 1.0D, boardPos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
                level.addFreshEntity(villager);
            }
        }
        // A short, server-visible fireworks burst is intentionally tied to the same durable claim.
        level.sendParticles(ParticleTypes.FIREWORK, boardPos.getX() + 0.5D, boardPos.getY() + 2.0D, boardPos.getZ() + 0.5D,
                24, 0.5D, 0.5D, 0.5D, 0.12D);
        return true;
    }

    static String settlementKey(DeliveryContract contract) {
        return contract.destinationDimension() + ":" + contract.destinationRegionX() + ":" + contract.destinationRegionZ();
    }
}
