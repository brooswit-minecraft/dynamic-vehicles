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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
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
        CompoundTag malformedWorkplace = saved.copy();
        malformedWorkplace.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND).getCompound(0)
                .getList("Workplaces", Tag.TAG_COMPOUND).getCompound(0).putString("Profession", "not a resource location");
        TownStorage malformedWorkplaceReload = TownStorage.load(malformedWorkplace, helper.getLevel().registryAccess());
        CompoundTag malformedDimension = saved.copy();
        malformedDimension.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND).getCompound(0)
                .putString("Dimension", "not a resource location");
        TownStorage malformedDimensionReload = TownStorage.load(malformedDimension, helper.getLevel().registryAccess());
        helper.assertTrue(malformedWorkplaceReload.hasFoundedTown(townKey)
                        && malformedWorkplaceReload.storeFor(townKey).balance(TownResourceCategory.WOOD) == 7
                        && admissionCount(helper, malformedWorkplaceReload) == 0
                        && malformedDimensionReload.hasFoundedTown(townKey)
                        && malformedDimensionReload.storeFor(townKey).balance(TownResourceCategory.WOOD) == 7
                        && admissionCount(helper, malformedDimensionReload) == 0,
                "malformed nested admission values must skip only that record and preserve unrelated town data");
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        Optional<ResidentCapacityAdmission> loaded = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, villager.getUUID());
        helper.assertTrue(loaded.filter(first.get()::equals).isPresent()
                        && loaded.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && loaded.get().requestedHomes() == 1
                        && loaded.get().requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))
                        && admissionCount(helper, reloaded) == 1,
                "load must retain the exact durable admission before any request can reuse or recreate it");
        Optional<ResidentCapacityAdmission> afterReload = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, reloaded);
        helper.assertTrue(afterReload.isPresent() && afterReload.get().equals(first.get())
                        && reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, villager.getUUID())
                                .filter(first.get()::equals).isPresent()
                        && helper.getLevel().getPoiManager().getFreeTickets(helper.absolutePos(RESIDENT)) == 0,
                "save/load and a second request must preserve the single nonterminal admission and create no POI reservation");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void admissionAttributesTownDemandToTheUnfulfilledResident(GameTestHelper helper) {
        Villager fulfilled = resident(helper, new BlockPos(3, 2, 3), VillagerProfession.ARMORER);
        Villager unfulfilled = resident(helper, new BlockPos(7, 2, 7), VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(fulfilled.blockPosition().getX(), fulfilled.blockPosition().getZ());
        TownStorage storage = foundedStorage(helper, region);
        BlockPos home = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos job = helper.absolutePos(new BlockPos(2, 2, 4));
        takeHome(helper, home);
        takeArmorerWorkplace(helper, job);
        fulfilled.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));
        fulfilled.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), job));
        TownResidentDemand townDemand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);

        Optional<ResidentCapacityAdmission> fulfilledAdmission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, fulfilled.getUUID(), townDemand, storage);
        Optional<ResidentCapacityAdmission> unfulfilledAdmission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, unfulfilled.getUUID(), townDemand, storage);
        helper.assertTrue(fulfilledAdmission.isEmpty()
                        && unfulfilledAdmission.filter(value -> value.requestedHomes() == 1
                                && value.requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))
                                && value.beneficiaryId().equals(unfulfilled.getUUID())).isPresent(),
                "shared town demand must be attributed only to the beneficiary still missing the matching home and workplace");
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "residentAdmissionProfileIsolated")
    public static void awaitingAdmissionReconcilesSameIdentityWhenProfessionChanges(GameTestHelper helper) {
        Villager villager = resident(helper, RESIDENT, VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
        TownStorage storage = foundedStorage(helper, region);
        TownResidentDemand originalDemand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        Optional<ResidentCapacityAdmission> original = TownConstructionService.requestResidentCapacity(helper.getLevel(), region,
                villager.getUUID(), originalDemand, storage);
        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        Optional<ResidentCapacityAdmission> loadedOriginal = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, villager.getUUID());
        helper.assertTrue(original.isPresent() && loadedOriginal.filter(original.get()::equals).isPresent()
                        && loadedOriginal.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && loadedOriginal.get().requestedHomes() == 1
                        && loadedOriginal.get().requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))
                        && admissionCount(helper, reloaded) == 1,
                "the saved ARMORER admission must be observed intact before the resident profile changes");
        BlockPos home = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos oldJob = helper.absolutePos(new BlockPos(2, 2, 4));
        takeHome(helper, home);
        takeArmorerWorkplace(helper, oldJob);
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), oldJob));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        TownResidentDemand changedDemand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, reloaded);
        boolean inProductionIterable = false;
        for (Entity entity : helper.getLevel().getEntities().getAll()) {
            if (entity == villager) {
                inProductionIterable = true;
                break;
            }
        }
        boolean exactHome = villager.getBrain().getMemory(MemoryModuleType.HOME)
                .map(value -> value.dimension().equals(helper.getLevel().dimension()) && value.pos().equals(home)).orElse(false);
        boolean homeTicketHeld = helper.getLevel().getPoiManager().getType(home)
                .map(type -> type.is(PoiTypes.HOME)
                        && helper.getLevel().getPoiManager().getFreeTickets(home) < type.value().maxTickets()).orElse(false);
        boolean exactOldJob = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .map(value -> value.dimension().equals(helper.getLevel().dimension()) && value.pos().equals(oldJob)).orElse(false);
        boolean oldJobMatchesFarmer = helper.getLevel().getPoiManager().getType(oldJob)
                .map(type -> VillagerProfession.FARMER.heldJobSite().test(type)).orElse(false);
        boolean sameRegion = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ()).equals(region);
        helper.assertTrue(helper.getLevel().getEntity(villager.getUUID()) == villager && villager.isAlive()
                        && inProductionIterable && sameRegion && exactHome && homeTicketHeld && exactOldJob
                        && villager.getVillagerData().getProfession() == VillagerProfession.FARMER && !oldJobMatchesFarmer
                        && changedDemand.missingWorkplaces().getOrDefault(VillagerProfession.FARMER, 0) >= 1,
                "request boundary must retain the named live same-region FARMER with its held HOME and no matching workplace; "
                        + "profession=" + villager.getVillagerData().getProfession() + ", sameRegion=" + sameRegion
                        + ", exactHome=" + exactHome + ", homeTicketHeld=" + homeTicketHeld
                        + ", exactOldJob=" + exactOldJob + ", oldJobMatchesFarmer=" + oldJobMatchesFarmer
                        + ", aggregateDemand=" + changedDemand);
        helper.assertTrue(changedDemand.missingHomes() == 0
                        && changedDemand.missingWorkplaces().equals(Map.of(VillagerProfession.FARMER, 1)),
                "the isolated changed-profile query must report exactly zero homes and one FARMER workplace; aggregateDemand="
                        + changedDemand);
        Optional<ResidentCapacityAdmission> replacement = TownConstructionService.requestResidentCapacity(helper.getLevel(), region,
                villager.getUUID(), changedDemand, reloaded);
        helper.assertTrue(original.isPresent() && replacement.filter(value -> value.requestedHomes() == 0
                                && value.requestedWorkplaces().equals(Map.of(VillagerProfession.FARMER, 1))
                                && value.beneficiaryId().equals(villager.getUUID())
                                && value.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM).isPresent()
                        && admissionCount(helper, reloaded) == 1
                        && reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, villager.getUUID())
                                .filter(replacement.get()::equals).isPresent(),
                "an unbound loaded admission must reconcile current resident need at the same durable identity; "
                        + "aggregateDemand=" + changedDemand + ", replacement=" + replacement.map(Object::toString).orElse("absent")
                        + ", persisted=" + reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, villager.getUUID())
                                .map(Object::toString).orElse("absent") + ", admissionCount=" + admissionCount(helper, reloaded));
        helper.succeed();
    }

    private static TownStorage foundedStorage(GameTestHelper helper, RegionCoord region) {
        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        return storage;
    }

    private static int admissionCount(GameTestHelper helper, TownStorage storage) {
        return storage.save(new CompoundTag(), helper.getLevel().registryAccess())
                .getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND).size();
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
