package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Bootstrap founder for a town region's designated centre, mirroring
 * {@code delivery.SettlementFounder}'s bootstrap-once pattern: a single campfire plus a single
 * unemployed villager, placed exactly once per region, with no retry duplicating it. A vanilla
 * village that already generated at the centre always wins and is never touched or replaced
 * (reuses {@link TownFoundationPlan}, the same decision shape {@code SettlementFoundationPlan}
 * already defines for this exact problem).
 *
 * <p>Bootstrap checks run off nearby online players rather than a chunk-generation event, the
 * same "only do work near where players actually are" shape every other periodic handler in this
 * mod already uses (e.g. {@code delivery.BuilderCampfireHandler}) — so a town's centre bootstraps
 * once a player's own chunk loading has generated the terrain there, never purely from world
 * generation running unobserved far away.
 */
public final class TownFounder {
    private static final int EVALUATE_INTERVAL_TICKS = 100;
    private TownFounder() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % EVALUATE_INTERVAL_TICKS != 0) {
            return;
        }
        TownStorage storage = TownStorage.of(level.getServer());
        for (ServerPlayer player : level.players()) {
            tryFoundNear(level, player, storage);
        }
    }

    /** Exposed {@code public} (rather than kept {@code private}) specifically so it is directly
     *  testable without needing to construct a real {@link LevelTickEvent.Post}, the same shape
     *  {@code delivery.SettlementFounder#onArrival} already uses for its own GameTests. */
    public static void tryFoundNear(ServerLevel level, ServerPlayer player, TownStorage storage) {
        RegionCoord region = TownRegion.fromBlockCoords(player.blockPosition().getX(), player.blockPosition().getZ());
        TownPlan plan = TownPlanner.generate(region, level.getSeed());
        BlockPos target = new BlockPos(plan.townCenterX(), player.blockPosition().getY(), plan.townCenterZ());
        tryFoundAt(level, region, target, storage);
    }

    /**
     * The core bootstrap-once decision and placement, factored out from {@link #tryFoundNear} so
     * it is directly GameTest-able against a controlled, prepared target position — the planner's
     * own choice of town centre (anywhere within a 384-block region) is already covered by
     * {@code TownPlannerTest} and cannot itself be relied on to land inside a GameTest structure's
     * tiny prepared footprint.
     */
    public static void tryFoundAt(ServerLevel level, RegionCoord region, BlockPos target, TownStorage storage) {
        String key = TownKeys.keyFor(level.dimension(), region);
        if (storage.hasFoundedTown(key)) {
            return;
        }
        if (!level.hasChunkAt(target)) {
            return;
        }

        boolean existingVillage = level.structureManager().getStructureWithPieceAt(target, StructureTags.VILLAGE).isValid();
        TownFoundationPlan.Decision decision = TownFoundationPlan.decide(existingVillage, storage.hasFoundedTown(key));
        if (decision != TownFoundationPlan.Decision.FOUND) {
            if (decision == TownFoundationPlan.Decision.EXISTING_VILLAGE) {
                // A vanilla village already claims this centre; mark the region settled without
                // ever placing anything, so this same check does not keep re-evaluating it every
                // interval for the rest of the region's life.
                storage.markTownFounded(key);
                storage.setDirty();
            }
            return;
        }

        BlockPos campfirePos = findSurfacePoint(level, target);
        if (campfirePos == null) {
            return;
        }

        // Persist the one-way claim before mutating the world, exactly like SettlementFounder:
        // repeated arrival, reconnect, and restart paths then cannot create a second bootstrap.
        storage.markTownFounded(key);
        storage.setDirty();

        level.setBlock(campfirePos, Blocks.CAMPFIRE.defaultBlockState(), 3);
        Villager villager = EntityType.VILLAGER.create(level, null, campfirePos, MobSpawnType.EVENT, false, false);
        if (villager != null) {
            villager.moveTo(campfirePos.getX() + 0.5D, campfirePos.getY() + 1.0D, campfirePos.getZ() + 0.5D,
                    level.random.nextFloat() * 360.0F, 0.0F);
            level.addFreshEntity(villager);
        }
    }

    /**
     * The surface at {@code column}'s (x, z), same shape as
     * {@code SettlementFounder#findSurfacePoint}: the first non-motion-blocking position above the
     * highest motion-blocking block (leaves ignored, so a low canopy cannot masquerade as ground),
     * fluids included. {@code null} when there is nothing to stand on, no headroom, or the
     * candidate cell and the villager's two cells above it are not all clear.
     */
    static BlockPos findSurfacePoint(ServerLevel level, BlockPos column) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        if (y <= level.getMinBuildHeight() || y + 2 > level.getMaxBuildHeight() - 1) {
            return null;
        }
        BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
        BlockState cell = level.getBlockState(pos);
        if (!cell.isAir() && cell.getFluidState().isEmpty()) {
            return null;
        }
        if (!level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.above(2)).isAir()) {
            return null;
        }
        return pos;
    }
}
