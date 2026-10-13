package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Bounded arrival-side Trading Post founder. The layout is a 7x7x5 spruce
 * pavilion: a spruce floor and roof, four stripped-oak corner posts, a central
 * Dispatch Board with Bell, and eight villagers (one Dispatcher). Every cell
 * is proven clear and level before the first world mutation.
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

        BlockPos target = BlockPos.containing((double) contract.destinationStartX(), (double) contract.destinationStartY(),
                (double) contract.destinationStartZ());
        if (!level.hasChunkAt(target)) {
            return false;
        }
        boolean existingVillage = level.structureManager().getStructureWithPieceAt(target, StructureTags.VILLAGE).isValid();
        String key = settlementKey(contract);
        BlockPos boardPos = new BlockPos(target.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ()), target.getZ());
        boolean safeSite = isSafeTradingPostSite(level, boardPos);

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

        placeTradingPost(level, boardPos);
        for (int i = 0; i < SettlementFoundationPlan.villagerCount(key); i++) {
            Villager villager = EntityType.VILLAGER.create(level, null, boardPos, MobSpawnType.EVENT, false, false);
            if (villager != null) {
                villager.moveTo(boardPos.getX() + 0.5D, boardPos.getY() + 1.0D, boardPos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
                if (i == 0) {
                    villager.setVillagerData(villager.getVillagerData().setProfession(DispatcherProfession.DISPATCHER.get()));
                } else {
                    villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
                }
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

    /**
     * A rejected candidate is intentionally left untouched and the contract is
     * restored by {@link ContractTickHandler}, allowing a later, safe retry.
     * Checking the whole footprint prevents us from overwriting player builds,
     * terrain, or entities merely because a delivery reached its target.
     */
    private static boolean isSafeTradingPostSite(ServerLevel level, BlockPos center) {
        int radius = SettlementFoundationPlan.TRADING_POST_RADIUS;
        int floorY = center.getY();
        AABB bounds = new AABB(center.getX() - radius, floorY, center.getZ() - radius,
                center.getX() + radius + 1, floorY + SettlementFoundationPlan.TRADING_POST_HEIGHT, center.getZ() + radius + 1);
        if (!level.getEntities((Entity) null, bounds).isEmpty()) {
            return false;
        }
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int worldX = center.getX() + x;
                int worldZ = center.getZ() + z;
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) != floorY) {
                    return false;
                }
                for (int y = 0; y < SettlementFoundationPlan.TRADING_POST_HEIGHT; y++) {
                    if (!level.isEmptyBlock(new BlockPos(worldX, floorY + y, worldZ))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void placeTradingPost(ServerLevel level, BlockPos center) {
        int radius = SettlementFoundationPlan.TRADING_POST_RADIUS;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos floor = center.offset(x, 0, z);
                level.setBlock(floor, Blocks.SPRUCE_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(floor.above(SettlementFoundationPlan.TRADING_POST_HEIGHT - 1),
                        Blocks.SPRUCE_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (int x : new int[] {-radius, radius}) {
            for (int z : new int[] {-radius, radius}) {
                for (int y = 1; y < SettlementFoundationPlan.TRADING_POST_HEIGHT - 1; y++) {
                    level.setBlock(center.offset(x, y, z), Blocks.STRIPPED_OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        level.setBlock(center, DispatcherBlocks.DISPATCH_BOARD.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(center.above(), Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
    }
}
