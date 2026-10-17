package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Arrival-side Trading Post founder. When a delivery arrives and no village
 * exists at the destination, the site is prepared by force and the post is
 * placed: the footprint is cleared of whatever occupies it (terrain, water,
 * foliage, builds) and every column is filled down to solid ground. The layout
 * is a 7x7x5 spruce pavilion: a spruce floor and roof, four stripped-oak corner
 * posts, a central Dispatch Board with Bell, and eight villagers (one Dispatcher).
 * The only reason to defer is an unloaded destination chunk.
 */
public final class SettlementFounder {
    private SettlementFounder() {
    }

    /**
     * @return whether arrival can complete. An unloaded destination chunk leaves the
     *         delivery active so a later sweep can retry without losing its contract.
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

        SettlementFoundationPlan.Decision decision = SettlementFoundationPlan.decide(existingVillage, storage.hasFoundedSettlement(key));
        if (decision != SettlementFoundationPlan.Decision.FOUND) {
            return true;
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

    static void placeTradingPost(ServerLevel level, BlockPos center) {
        int radius = SettlementFoundationPlan.TRADING_POST_RADIUS;
        int floorY = center.getY();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos floor = center.offset(x, 0, z);
                int terrainY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, floor.getX(), floor.getZ());
                // Clear everything above the floor, including hillside that rises past the roof.
                int clearTop = Math.min(maxY, Math.max(floorY + SettlementFoundationPlan.TRADING_POST_HEIGHT - 1, terrainY));
                for (int y = floorY; y <= clearTop; y++) {
                    BlockPos cell = new BlockPos(floor.getX(), y, floor.getZ());
                    if (!level.isEmptyBlock(cell)) {
                        level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                // Fill downward until solid ground, so the floor never hangs over air, water or lava.
                int bottom = Math.max(minY, floorY - SettlementFoundationPlan.MAX_FOUNDATION_DEPTH);
                for (int y = floorY - 1; y >= bottom; y--) {
                    BlockPos support = new BlockPos(floor.getX(), y, floor.getZ());
                    BlockState state = level.getBlockState(support);
                    if (state.getFluidState().isEmpty() && state.isFaceSturdy(level, support, Direction.UP)) {
                        break;
                    }
                    level.setBlock(support, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                }
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
