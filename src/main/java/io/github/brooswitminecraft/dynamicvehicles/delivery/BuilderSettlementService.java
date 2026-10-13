package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.AABB;

/** Server-side, destructive-only-after-preflight Builder settlement path. */
public final class BuilderSettlementService {
    public static final String CAMPFIRE_POS_TAG = "dynamicvehicles.builderCampfire";
    private static final int BUILDING_RADIUS = 1;
    private static final int BUILDING_HEIGHT = 4;

    private BuilderSettlementService() {}

    /** Project-owned building variants; never resolves or places another mod's structures. */
    private enum VillageBuilding {
        OAK_HUT(Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.OAK_STAIRS),
        SPRUCE_HUT(Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_SPRUCE_LOG, Blocks.SPRUCE_STAIRS);

        final net.minecraft.world.level.block.Block plank;
        final net.minecraft.world.level.block.Block post;
        final net.minecraft.world.level.block.Block stair;

        VillageBuilding(net.minecraft.world.level.block.Block plank, net.minecraft.world.level.block.Block post,
                net.minecraft.world.level.block.Block stair) {
            this.plank = plank;
            this.post = post;
            this.stair = stair;
        }
    }

    public static void attemptSettlement(Villager villager, int tradeLevel) {
        if (!(villager.level() instanceof ServerLevel level)) return;
        BlockPos campfire = BlockPos.of(villager.getPersistentData().getLong(CAMPFIRE_POS_TAG));
        if (!level.getBlockState(campfire).is(Blocks.CAMPFIRE)) return;
        int villagerLevel = villager.getVillagerData().getLevel();
        if (level.random.nextDouble() >= BuilderSettlementPlan.settlementChance(villagerLevel, tradeLevel)) return;

        VillageBuilding buildingVariant = VillageBuilding.values()[level.random.nextInt(VillageBuilding.values().length)];
        Direction rotation = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
        BlockPos building = campfire.relative(rotation, 4);
        if (!isSafeBuildingSite(level, building, new ChunkPos(campfire), buildingVariant)) return;

        // The full footprint and entity volume were checked before either mutation.
        placeBuilding(level, building, rotation, buildingVariant);
        level.removeBlock(campfire, false);
        villager.getPersistentData().remove(CAMPFIRE_POS_TAG);
    }

    private static boolean isSafeBuildingSite(ServerLevel level, BlockPos center, ChunkPos campfireChunk,
            VillageBuilding buildingVariant) {
        if (!level.hasChunkAt(center) || !new ChunkPos(center).equals(campfireChunk)) return false;
        AABB bounds = new AABB(center.getX() - BUILDING_RADIUS, center.getY(), center.getZ() - BUILDING_RADIUS,
                center.getX() + BUILDING_RADIUS + 1, center.getY() + BUILDING_HEIGHT, center.getZ() + BUILDING_RADIUS + 1);
        if (!level.getEntities((Entity) null, bounds).isEmpty()) return false;
        for (int x = -BUILDING_RADIUS; x <= BUILDING_RADIUS; x++) {
            for (int z = -BUILDING_RADIUS; z <= BUILDING_RADIUS; z++) {
                for (int y = 0; y < BUILDING_HEIGHT; y++) {
                    if (!level.isEmptyBlock(center.offset(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    private static void placeBuilding(ServerLevel level, BlockPos center, Direction rotation, VillageBuilding buildingVariant) {
        for (int x = -BUILDING_RADIUS; x <= BUILDING_RADIUS; x++) {
            for (int z = -BUILDING_RADIUS; z <= BUILDING_RADIUS; z++) {
                level.setBlock(center.offset(x, 0, z), buildingVariant.plank.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(center.offset(x, 3, z), buildingVariant.plank.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (int x : new int[] {-1, 1}) for (int z : new int[] {-1, 1})
            for (int y = 1; y < 3; y++) level.setBlock(center.offset(x, y, z), buildingVariant.post.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(center.relative(rotation).above(), buildingVariant.stair.defaultBlockState().setValue(StairBlock.FACING, rotation), Block.UPDATE_ALL);
    }
}
