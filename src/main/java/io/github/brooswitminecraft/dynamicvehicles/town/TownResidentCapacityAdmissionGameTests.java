package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Optional;

/** Focused persistence/idempotency evidence for the M454-owned admission boundary. */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownResidentCapacityAdmissionGameTests {
    private TownResidentCapacityAdmissionGameTests() {}

    private static final BlockPos RESIDENT = new BlockPos(5, 2, 5);

    @GameTest(template = "settlement_test")
    public static void residentCapacityAdmissionPersistsAndDuplicateRequestReusesIt(GameTestHelper helper) {
        helper.setBlock(RESIDENT.below(), Blocks.DIRT);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, helper.absolutePos(RESIDENT),
                MobSpawnType.EVENT, false, false);
        helper.assertTrue(villager != null, "test setup: beneficiary villager must be constructible");
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.ARMORER));

        RegionCoord region = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
        TownStorage storage = new TownStorage();
        String townKey = TownKeys.keyFor(helper.getLevel().dimension(), region);
        storage.markTownFounded(townKey);
        storage.creditStore(townKey, TownResourceCategory.WOOD, 7);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of(VillagerProfession.ARMORER, 1));

        Optional<ResidentCapacityAdmission> first = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, storage);
        Optional<ResidentCapacityAdmission> duplicate = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, storage);
        helper.assertTrue(first.isPresent() && duplicate.isPresent() && first.get().equals(duplicate.get())
                        && first.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && first.get().beneficiaryId().equals(villager.getUUID())
                        && first.get().dimension().equals(helper.getLevel().dimension())
                        && first.get().region().equals(region)
                        && first.get().requestedHomes() == 1
                        && first.get().requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1)),
                "a duplicate tick must reuse one dimension-qualified beneficiary admission without advancing it to a POI/build effect");

        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        CompoundTag malformed = saved.copy();
        ListTag malformedAdmissions = malformed.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        malformedAdmissions.getCompound(0).getList("Workplaces", Tag.TAG_COMPOUND).getCompound(0)
                .putString("Profession", "not a resource location");
        TownStorage malformedReload = TownStorage.load(malformed, helper.getLevel().registryAccess());
        helper.assertTrue(malformedReload.hasFoundedTown(townKey)
                        && malformedReload.storeFor(townKey).balance(TownResourceCategory.WOOD) == 7
                        && malformedReload.capacityAdmissionCount() == 0,
                "a malformed nested profession must reject only that admission without aborting unrelated TownStorage data");
        CompoundTag malformedDimension = saved.copy();
        malformedDimension.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND).getCompound(0)
                .putString("Dimension", "not a resource location");
        TownStorage malformedDimensionReload = TownStorage.load(malformedDimension, helper.getLevel().registryAccess());
        helper.assertTrue(malformedDimensionReload.hasFoundedTown(townKey)
                        && malformedDimensionReload.storeFor(townKey).balance(TownResourceCategory.WOOD) == 7
                        && malformedDimensionReload.capacityAdmissionCount() == 0,
                "a malformed admission Dimension must reject only that admission without aborting unrelated TownStorage data");
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        Optional<ResidentCapacityAdmission> loaded = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, villager.getUUID());
        helper.assertTrue(loaded.filter(first.get()::equals).isPresent() && reloaded.capacityAdmissionCount() == 1,
                "save/load must restore the admission before any retry is allowed to recreate it");
        Optional<ResidentCapacityAdmission> afterReload = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, reloaded);
        helper.assertTrue(afterReload.isPresent() && afterReload.get().equals(first.get())
                        && reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, villager.getUUID())
                                .filter(first.get()::equals).isPresent()
                        && reloaded.capacityAdmissionCount() == 1
                        && helper.getLevel().getPoiManager().getFreeTickets(helper.absolutePos(RESIDENT)) == 0,
                "save/load and a second request must preserve the single nonterminal admission and create no POI reservation");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void capacityAdmissionTargetsOnlyTheBeneficiaryWhoseCapacityIsMissing(GameTestHelper helper) {
        Villager fulfilledA = resident(helper, new BlockPos(3, 2, 3), VillagerProfession.ARMORER);
        Villager unfulfilledB = resident(helper, new BlockPos(7, 2, 7), VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(fulfilledA.blockPosition().getX(), fulfilledA.blockPosition().getZ());
        TownStorage storage = foundedStorage(helper, region);
        BlockPos home = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos workplace = helper.absolutePos(new BlockPos(2, 2, 4));
        takeHome(helper, home);
        takeArmorerWorkplace(helper, workplace);
        fulfilledA.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));
        fulfilledA.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), workplace));

        TownResidentDemand observedDemand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of(VillagerProfession.ARMORER, 1));
        Optional<ResidentCapacityAdmission> forFulfilledA = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, fulfilledA.getUUID(), demand, storage);
        Optional<ResidentCapacityAdmission> forUnfulfilledB = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, unfulfilledB.getUUID(), demand, storage);
        helper.assertTrue(observedDemand.missingHomes() >= 1
                        && observedDemand.missingWorkplaces().getOrDefault(VillagerProfession.ARMORER, 0) >= 1
                        && forFulfilledA.isEmpty()
                        && forUnfulfilledB.filter(admission -> admission.requestedHomes() == 1
                                && admission.requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))
                                && admission.beneficiaryId().equals(unfulfilledB.getUUID())).isPresent(),
                "town-wide unmet demand must not create capacity for fulfilled A; it must be attributed to unfulfilled B");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void capacityAdmissionRejectsAChangedProfessionsStaleWorkplace(GameTestHelper helper) {
        Villager villager = resident(helper, new BlockPos(5, 2, 5), VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
        TownStorage storage = foundedStorage(helper, region);
        TownResidentDemand originalDemand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        Optional<ResidentCapacityAdmission> original = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), originalDemand, storage);
        CompoundTag savedBeforeProfileChange = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(savedBeforeProfileChange, helper.getLevel().registryAccess());
        Optional<ResidentCapacityAdmission> loadedOriginal = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, villager.getUUID());
        BlockPos home = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos oldArmorerWorkplace = helper.absolutePos(new BlockPos(2, 2, 4));
        takeHome(helper, home);
        takeArmorerWorkplace(helper, oldArmorerWorkplace);
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), oldArmorerWorkplace));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));

        TownResidentDemand demand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, reloaded);
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, reloaded);
        helper.assertTrue(original.isPresent() && loadedOriginal.filter(original.get()::equals).isPresent()
                        && original.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && original.get().requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))
                        && demand.missingHomes() == 0 && demand.missingWorkplaces().equals(Map.of(VillagerProfession.FARMER, 1))
                        && admission.filter(value -> value.requestedHomes() == 0
                                && value.requestedWorkplaces().equals(Map.of(VillagerProfession.FARMER, 1))
                                && value.beneficiaryId().equals(villager.getUUID())
                                && value.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM).isPresent()
                        && reloaded.capacityAdmissionCount() == 1
                        && reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, villager.getUUID())
                                .filter(admission.get()::equals).isPresent(),
                "a loaded, unbound ARMORER admission must reconcile at its same identity to current FARMER need, never return stale success or create a second site");
        helper.succeed();
    }

    private static TownStorage foundedStorage(GameTestHelper helper, RegionCoord region) {
        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        return storage;
    }

    private static Villager resident(GameTestHelper helper, BlockPos relativePos, VillagerProfession profession) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, helper.absolutePos(relativePos),
                MobSpawnType.EVENT, false, false);
        helper.assertTrue(villager != null, "test setup: resident villager must be constructible");
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(profession));
        return villager;
    }

    private static void takeHome(GameTestHelper helper, BlockPos pos) {
        helper.getLevel().getPoiManager().add(pos, poiType(helper, PoiTypes.HOME));
        helper.getLevel().getPoiManager().take(type -> type.is(PoiTypes.HOME), (type, candidate) -> candidate.equals(pos), pos, 1);
    }

    private static void takeArmorerWorkplace(GameTestHelper helper, BlockPos pos) {
        helper.getLevel().getPoiManager().add(pos, poiType(helper, PoiTypes.ARMORER));
        helper.getLevel().getPoiManager().take(type -> type.is(PoiTypes.ARMORER), (type, candidate) -> candidate.equals(pos), pos, 1);
    }

    private static Holder<PoiType> poiType(GameTestHelper helper, net.minecraft.resources.ResourceKey<PoiType> key) {
        HolderLookup.RegistryLookup<PoiType> registry = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE);
        return registry.getOrThrow(key);
    }
}
