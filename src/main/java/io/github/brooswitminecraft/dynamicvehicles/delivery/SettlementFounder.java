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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Arrival-side destination founder. When a delivery arrives and no village exists at the
 * destination, the destination is marked with a single Dispatch Board and a single Dispatcher
 * villager placed on the surface, found but never forced: no terrain is cleared or filled, and
 * no shack, platform, or extra villagers are generated (MINECRAFT-393). The top of water counts
 * as a surface. The only reasons to defer are an unloaded destination chunk, a column with
 * nothing to stand on at all, or a column whose candidate board cell or villager clearance is
 * actually blocked (e.g. a low tree canopy); all three leave the delivery active so a later sweep
 * can retry without losing its contract, and none of them mutate the world first.
 */
public final class SettlementFounder {
    private SettlementFounder() {
    }

    /**
     * @return whether arrival can complete. An unloaded destination chunk, or a destination
     *         column with no usable surface, leaves the delivery active so a later sweep can
     *         retry without losing its contract.
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

        SettlementFoundationPlan.Decision decision = SettlementFoundationPlan.decide(existingVillage, storage.hasFoundedSettlement(key));
        if (decision != SettlementFoundationPlan.Decision.FOUND) {
            return true;
        }

        BlockPos boardPos = findSurfacePoint(level, target);
        if (boardPos == null) {
            // No usable surface at this column - nothing to stand on, no room within the build
            // height limit, or the candidate board/villager cells are actually occupied (e.g. a
            // low canopy). A single, bounded check, not a scan or retry loop, and no destructive
            // placement is attempted. The contract stays active so a later sweep retries, exactly
            // like the unloaded-chunk case above.
            return false;
        }

        // Persist the one-way claim before mutating the world. Repeated arrival,
        // reconnect, and restart paths then cannot create a second settlement or celebration.
        storage.markSettlementFounded(key);
        storage.setDirty();

        level.setBlock(boardPos, DispatcherBlocks.DISPATCH_BOARD.get().defaultBlockState(), Block.UPDATE_ALL);
        Villager villager = EntityType.VILLAGER.create(level, null, boardPos, MobSpawnType.EVENT, false, false);
        if (villager != null) {
            villager.moveTo(boardPos.getX() + 0.5D, boardPos.getY() + 1.0D, boardPos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
            villager.setVillagerData(villager.getVillagerData().setProfession(DispatcherProfession.DISPATCHER.get()));
            level.addFreshEntity(villager);
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
     * The surface at {@code column}'s (x, z): the first non-motion-blocking position above the
     * highest motion-blocking block, fluids included, so the top of water is an acceptable
     * surface. {@code MOTION_BLOCKING_NO_LEAVES} deliberately ignores leaves, so that candidate
     * height alone is not proof the board cell is actually empty - a low canopy can leave real
     * leaf blocks sitting exactly there. {@code null} when the column has nothing to stand on at
     * all (e.g. an unbroken void), when there is no room above it within the build height limit,
     * when the board cell itself is occupied by something other than air or a fluid (so placing
     * the board would destroy it rather than just claim a found surface), or when the villager's
     * two cells above the board are not both clear for it to stand in.
     */
    static BlockPos findSurfacePoint(ServerLevel level, BlockPos column) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        if (y <= level.getMinBuildHeight() || y + 2 > level.getMaxBuildHeight() - 1) {
            return null;
        }
        BlockPos boardPos = new BlockPos(column.getX(), y, column.getZ());
        BlockState boardCell = level.getBlockState(boardPos);
        if (!boardCell.isAir() && boardCell.getFluidState().isEmpty()) {
            return null;
        }
        if (!level.getBlockState(boardPos.above()).isAir() || !level.getBlockState(boardPos.above(2)).isAir()) {
            return null;
        }
        return boardPos;
    }
}
