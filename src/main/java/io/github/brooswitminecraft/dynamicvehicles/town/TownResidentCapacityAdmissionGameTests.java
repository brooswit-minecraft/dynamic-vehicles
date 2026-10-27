package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
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
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
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
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        Optional<ResidentCapacityAdmission> afterReload = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, reloaded);
        helper.assertTrue(afterReload.isPresent() && afterReload.get().equals(first.get())
                        && reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, villager.getUUID())
                                .filter(first.get()::equals).isPresent()
                        && helper.getLevel().getPoiManager().getFreeTickets(helper.absolutePos(RESIDENT)) == 0,
                "save/load and a second request must preserve the single nonterminal admission and create no POI reservation");
        helper.succeed();
    }
}
