package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/** Server-side, destructive-only-after-preflight Builder settlement path. */
public final class BuilderSettlementService {
    public static final String CAMPFIRE_POS_TAG = "dynamicvehicles.builderCampfire";
    private static final String PENDING_DONATIONS_TAG = "dynamicvehicles.pendingBuilderDonations";
    private static final int CAMPFIRE_SEARCH_RADIUS = 8;
    private static final int BUILDING_RADIUS = (BuilderSettlementPlan.HOUSE_FOOTPRINT_WIDTH - 1) / 2;
    private static final int BUILDING_HEIGHT = BuilderSettlementPlan.HOUSE_HEIGHT;

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

        static VillageBuilding from(BuilderSettlementPlan.BuildingVariant variant) {
            return switch (variant) {
                case OAK_HUT -> OAK_HUT;
                case SPRUCE_HUT -> SPRUCE_HUT;
            };
        }
    }

    public static void attemptDonationSettlement(Villager villager, int tradeTier) {
        attemptSettlement(villager, BuilderSettlementPlan.donationSettlementChance(
                villager.getVillagerData().getLevel(), tradeTier));
    }

    public static void queueDonationSettlement(Villager villager, int tradeTier) {
        int[] previous = villager.getPersistentData().getIntArray(PENDING_DONATIONS_TAG);
        int[] pending = java.util.Arrays.copyOf(previous, previous.length + 2);
        pending[previous.length] = villager.getVillagerData().getLevel();
        pending[previous.length + 1] = tradeTier;
        villager.getPersistentData().putIntArray(PENDING_DONATIONS_TAG, pending);
    }

    /** MerchantResultSlot awards XP after the trade event; retire only after the transaction finishes. */
    static void processPendingDonations(Villager villager) {
        int[] pending = villager.getPersistentData().getIntArray(PENDING_DONATIONS_TAG);
        villager.getPersistentData().remove(PENDING_DONATIONS_TAG);
        for (int i = 0; i + 1 < pending.length; i += 2) {
            attemptSettlement(villager, BuilderSettlementPlan.donationSettlementChance(pending[i], pending[i + 1]));
        }
    }

    public static void attemptPassiveSettlement(Villager villager) {
        attemptSettlement(villager, BuilderSettlementPlan.passiveSettlementChance(
                villager.getVillagerData().getLevel()));
    }

    private static void attemptSettlement(Villager villager, double settlementChance) {
        if (!(villager.level() instanceof ServerLevel level)) return;
        if (villager.getVillagerData().getProfession() != BuilderProfession.BUILDER.get()) return;
        BlockPos campfire = findCampfireAnchor(level, villager);
        if (campfire == null) return;
        if (level.random.nextDouble() >= settlementChance) return;

        VillageBuilding buildingVariant = VillageBuilding.from(
                BuilderSettlementPlan.selectBuildingVariant(level.random.nextInt(VillageBuilding.values().length)));
        // The house stands dead centre in the campfire's own chunk; only the door-facing rotation varies,
        // tried in random order until one is safe.
        BlockPos center = new BlockPos(BuilderSettlementPlan.chunkCenterCoordinate(campfire.getX()), campfire.getY(),
                BuilderSettlementPlan.chunkCenterCoordinate(campfire.getZ()));
        Direction rotation = null;
        java.util.List<Direction> rotations = new java.util.ArrayList<>(
                java.util.Arrays.asList(Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new)));
        java.util.Collections.shuffle(rotations, new java.util.Random(level.random.nextLong()));
        for (Direction candidate : rotations) {
            if (isInCampfireChunk(campfire, center, candidate)
                    && isSafeBuildingSite(level, campfire, villager, center, candidate)) {
                rotation = candidate;
                break;
            }
        }
        boolean placementIsSafe = rotation != null;
        if (!BuilderSettlementPlan.shouldCompleteSettlement(level.getBlockState(campfire).is(Blocks.CAMPFIRE), true,
                placementIsSafe)) return;

        // The full footprint and entity volume were checked before any mutation. The centred footprint can
        // now contain the originating campfire itself (the house is no longer offset away from it); when it
        // does, placeBuilding already overwrites that block as part of the floor, so removing it afterwards
        // would just punch a hole back into the finished house.
        boolean campfireInsideFootprint = BuilderSettlementPlan.isWithinHouseFootprint(
                center.getX(), center.getZ(), campfire.getX(), campfire.getZ());
        placeBuilding(level, center, rotation, buildingVariant);
        if (!campfireInsideFootprint) level.removeBlock(campfire, false);
        // The entity-occupancy check excludes the settling villager so standing near its own campfire can
        // never block its own house, but nothing moves it: the centred footprint can now overlap wherever
        // it happens to be standing. Relocate it to the entrance tile, whose clearance and sturdy footing
        // isSafeBuildingSite already validated, so it is never left entombed in a wall or floor it didn't move for.
        BlockPos entrance = center.relative(rotation, BUILDING_RADIUS + 1);
        // entrance.above()/above(2) are exactly the headroom isSafeBuildingSite already validated clear;
        // gravity settles the villager onto whatever is solid below if that's lower than this.
        villager.moveTo(entrance.getX() + 0.5D, entrance.above().getY(), entrance.getZ() + 0.5D,
                rotation.getOpposite().toYRot(), 0.0F);
        resetToUnemployed(villager);
        villager.getPersistentData().remove(CAMPFIRE_POS_TAG);
    }

    /** A settled Builder retires completely, including its leveled donation offers. */
    private static void resetToUnemployed(Villager villager) {
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(VillagerProfession.NONE)
                .setLevel(1));
        villager.setVillagerXp(0);
        villager.updateMerchantTimer = 0;
        villager.increaseProfessionLevelOnUpdate = false;
        villager.setOffers(new MerchantOffers());
    }

    /**
     * A campfire placed by the mod keeps its precise marker; otherwise an existing,
     * player-placed vanilla campfire in the bounded loaded neighbourhood is eligible.
     */
    private static BlockPos findCampfireAnchor(ServerLevel level, Villager villager) {
        if (villager.getPersistentData().contains(CAMPFIRE_POS_TAG)) {
            BlockPos recorded = BlockPos.of(villager.getPersistentData().getLong(CAMPFIRE_POS_TAG));
            if (level.hasChunkAt(recorded) && level.getBlockState(recorded).is(Blocks.CAMPFIRE)) return recorded;
        }
        BlockPos origin = villager.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(origin.offset(-CAMPFIRE_SEARCH_RADIUS, -2, -CAMPFIRE_SEARCH_RADIUS),
                origin.offset(CAMPFIRE_SEARCH_RADIUS, 2, CAMPFIRE_SEARCH_RADIUS))) {
            if (!level.hasChunkAt(candidate) || !level.getBlockState(candidate).is(Blocks.CAMPFIRE)) continue;
            double distance = candidate.distSqr(origin);
            if (distance < bestDistance) {
                best = candidate.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The footprint and its entrance block must all lie in the campfire's chunk (AC4). */
    private static boolean isInCampfireChunk(BlockPos campfire, BlockPos center, Direction rotation) {
        BlockPos entrance = center.relative(rotation, BUILDING_RADIUS + 1);
        int minX = Math.min(center.getX() - BUILDING_RADIUS, entrance.getX());
        int maxX = Math.max(center.getX() + BUILDING_RADIUS, entrance.getX());
        int minZ = Math.min(center.getZ() - BUILDING_RADIUS, entrance.getZ());
        int maxZ = Math.max(center.getZ() + BUILDING_RADIUS, entrance.getZ());
        return BuilderSettlementPlan.isWithinCampfireChunk(campfire.getX(), campfire.getZ(), minX, minZ, maxX, maxZ);
    }

    /**
     * The centred footprint can now contain the originating campfire and the Builder standing at it
     * (previously the fixed 4-block offset always kept the house away from both). Settlement is explicitly
     * meant to replace that exact campfire, so it and the settling villager are excluded from the
     * occupancy checks below; every other block and entity is still a strict blocker.
     */
    private static boolean isSafeBuildingSite(ServerLevel level, BlockPos campfire, Villager villager,
            BlockPos center, Direction rotation) {
        BlockPos entrance = center.relative(rotation, BUILDING_RADIUS + 1);
        if (!level.hasChunkAt(entrance) || !isEmptyOrCampfire(level, entrance.above(), campfire)
                || !isEmptyOrCampfire(level, entrance.above(2), campfire)
                || !level.getBlockState(entrance.below()).isFaceSturdy(level, entrance.below(), Direction.UP)) return false;
        // The settling Builder is relocated to stand at entrance.above() once settlement completes: that
        // destination must itself be preflighted, not just assumed safe because the house's own footprint
        // checked out. entrance (its support underfoot) must be solid ground and not another hazard --
        // fire and lava already fail the sturdy check below, but a lit campfire is sturdy AND dangerous, so
        // it needs an explicit exception -- and the landing cells must hold no other entity.
        if (!entrance.equals(campfire)) {
            var supportState = level.getBlockState(entrance);
            if (supportState.is(Blocks.CAMPFIRE) || supportState.is(Blocks.SOUL_CAMPFIRE)) return false;
            if (!supportState.isFaceSturdy(level, entrance, Direction.UP)) return false;
        }
        AABB landing = new AABB(entrance.getX(), entrance.above().getY(), entrance.getZ(),
                entrance.getX() + 1, entrance.above(2).getY() + 1, entrance.getZ() + 1);
        if (!level.getEntities(villager, landing).isEmpty()) return false;
        AABB bounds = new AABB(center.getX() - BUILDING_RADIUS, center.getY(), center.getZ() - BUILDING_RADIUS,
                center.getX() + BUILDING_RADIUS + 1, center.getY() + BUILDING_HEIGHT, center.getZ() + BUILDING_RADIUS + 1);
        if (!level.getEntities(villager, bounds).isEmpty()) return false;
        for (int x = -BUILDING_RADIUS; x <= BUILDING_RADIUS; x++) {
            for (int z = -BUILDING_RADIUS; z <= BUILDING_RADIUS; z++) {
                BlockPos floor = center.offset(x, 0, z);
                if (!level.hasChunkAt(floor) || !level.getWorldBorder().isWithinBounds(floor)
                        || !level.getBlockState(floor.below()).isFaceSturdy(level, floor.below(), Direction.UP)) return false;
                for (int y = 0; y < BUILDING_HEIGHT; y++) {
                    if (!isEmptyOrCampfire(level, center.offset(x, y, z), campfire)) return false;
                }
            }
        }
        return true;
    }

    private static boolean isEmptyOrCampfire(ServerLevel level, BlockPos pos, BlockPos campfire) {
        return level.isEmptyBlock(pos) || pos.equals(campfire);
    }

    private static void placeBuilding(ServerLevel level, BlockPos center, Direction rotation, VillageBuilding buildingVariant) {
        for (int x = -BUILDING_RADIUS; x <= BUILDING_RADIUS; x++) {
            for (int z = -BUILDING_RADIUS; z <= BUILDING_RADIUS; z++) {
                level.setBlock(center.offset(x, 0, z), buildingVariant.plank.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(center.offset(x, 3, z), buildingVariant.plank.defaultBlockState(), Block.UPDATE_ALL);
                if (Math.abs(x) == BUILDING_RADIUS || Math.abs(z) == BUILDING_RADIUS) {
                    for (int y = 1; y < 3; y++)
                        level.setBlock(center.offset(x, y, z), buildingVariant.plank.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (int x : new int[] {-BUILDING_RADIUS, BUILDING_RADIUS})
            for (int z : new int[] {-BUILDING_RADIUS, BUILDING_RADIUS})
                for (int y = 1; y < 3; y++)
                    level.setBlock(center.offset(x, y, z), buildingVariant.post.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos doorway = center.relative(rotation, BUILDING_RADIUS);
        level.setBlock(doorway.above(), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, rotation).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(doorway.above(2), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, rotation).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);

        BlockPos bedFoot = center.relative(rotation.getOpposite());
        level.setBlock(bedFoot.above(), Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, rotation).setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(bedFoot.relative(rotation).above(), Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, rotation).setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        level.setBlock(center.relative(rotation.getClockWise()).above(), Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
    }
}
