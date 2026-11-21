package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.village.RegionCoord;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

/** Cancellation preserves the published binding/generation API and refuses every bound state. */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownCapacityAdmissionCancellationGameTests {
    private TownCapacityAdmissionCancellationGameTests() {}

    private static ResidentCapacityAdmission admission(ResourceKey<Level> dimension, RegionCoord region,
            UUID beneficiary, ResidentCapacityAdmission.State state) {
        return new ResidentCapacityAdmission(dimension, TownKeys.keyFor(dimension, region), region, beneficiary,
                1, Map.of(VillagerProfession.ARMORER, 1), false, "capacity:test-owner", "profile:test", state,
                state.bound() ? "site:test" : null,
                state.bound() ? new UUID(0L, 1L) : null,
                state.bound() ? "bound-plan:test" : null,
                state.bound() ? new UUID(0L, 2L) : null);
    }

    @GameTest(template = "settlement_test")
    public static void cancellationPreservesRequestProfileAndSurvivesSaveLoad(GameTestHelper helper) {
        var dimension = helper.getLevel().dimension();
        var region = new RegionCoord(3, -2);
        UUID beneficiary = UUID.randomUUID();
        TownStorage storage = new TownStorage();
        storage.createOrReuseCapacityAdmission(admission(dimension, region, beneficiary,
                ResidentCapacityAdmission.State.AWAITING_CLAIM));
        storage.setDirty(false);
        helper.assertTrue(storage.cancelAwaitingCapacityAdmission(dimension, region, beneficiary), "awaiting intent must cancel");
        var expected = admission(dimension, region, beneficiary, ResidentCapacityAdmission.State.CANCELLED);
        helper.assertTrue(storage.capacityAdmissionFor(dimension, region, beneficiary).filter(expected::equals).isPresent(),
                "only state may change; every identity/profile field must survive");
        helper.assertTrue(storage.isDirty(), "successful cancellation must be persisted");
        var reloaded = TownStorage.load(storage.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.capacityAdmissionFor(dimension, region, beneficiary).filter(expected::equals).isPresent(),
                "cancelled admission must survive real save/load");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void repeatedAndAbsentCancellationDoNotMutateStorage(GameTestHelper helper) {
        var dimension = helper.getLevel().dimension();
        var region = new RegionCoord(7, 1);
        UUID beneficiary = UUID.randomUUID();
        TownStorage storage = new TownStorage();
        storage.createOrReuseCapacityAdmission(admission(dimension, region, beneficiary,
                ResidentCapacityAdmission.State.AWAITING_CLAIM));
        helper.assertTrue(storage.cancelAwaitingCapacityAdmission(dimension, region, beneficiary), "setup must cancel once");
        var saved = storage.capacityAdmissionFor(dimension, region, beneficiary).orElseThrow();
        storage.setDirty(false);
        helper.assertTrue(!storage.cancelAwaitingCapacityAdmission(dimension, region, beneficiary), "repeat must report false");
        UUID absent = UUID.randomUUID();
        helper.assertTrue(!storage.cancelAwaitingCapacityAdmission(dimension, region, absent), "absent must report false");
        helper.assertTrue(storage.capacityAdmissionFor(dimension, region, absent).isEmpty()
                && storage.capacityAdmissionCount() == 1 && !storage.isDirty(), "no-op must neither create records nor mark dirty");
        helper.assertTrue(storage.capacityAdmissionFor(dimension, region, beneficiary).filter(saved::equals).isPresent(),
                "repeat must preserve cancelled record");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void buildingAndCompletedTargetsRemainUnchanged(GameTestHelper helper) {
        var dimension = helper.getLevel().dimension();
        var region = new RegionCoord(0, 0);
        for (var state : new ResidentCapacityAdmission.State[]{ResidentCapacityAdmission.State.BUILDING,
                ResidentCapacityAdmission.State.COMPLETED}) {
            UUID beneficiary = UUID.randomUUID();
            TownStorage storage = new TownStorage();
            var before = admission(dimension, region, beneficiary, state);
            storage.createOrReuseCapacityAdmission(before);
            storage.setDirty(false);
            helper.assertTrue(!storage.cancelAwaitingCapacityAdmission(dimension, region, beneficiary), "must reject target state " + state);
            helper.assertTrue(storage.capacityAdmissionFor(dimension, region, beneficiary).filter(before::equals).isPresent()
                    && !storage.isDirty(), "rejected target must remain byte-for-byte equivalent and clean");
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void cancellationIsScopedToExactBeneficiaryRegionAndDimension(GameTestHelper helper) {
        var dimension = helper.getLevel().dimension();
        var region = new RegionCoord(5, 5);
        UUID beneficiary = UUID.randomUUID();
        TownStorage storage = new TownStorage();
        var target = admission(dimension, region, beneficiary, ResidentCapacityAdmission.State.AWAITING_CLAIM);
        var others = new ResidentCapacityAdmission[]{
                admission(dimension, region, UUID.randomUUID(), ResidentCapacityAdmission.State.AWAITING_CLAIM),
                admission(dimension, new RegionCoord(5, 6), beneficiary, ResidentCapacityAdmission.State.AWAITING_CLAIM),
                admission(Level.NETHER, region, beneficiary, ResidentCapacityAdmission.State.AWAITING_CLAIM)};
        storage.createOrReuseCapacityAdmission(target);
        for (var other : others) storage.createOrReuseCapacityAdmission(other);
        helper.assertTrue(storage.cancelAwaitingCapacityAdmission(dimension, region, beneficiary), "exact target must cancel");
        for (var other : others) helper.assertTrue(storage.capacityAdmissionFor(other.dimension(), other.region(), other.beneficiaryId())
                .filter(other::equals).isPresent(), "different beneficiary, region or dimension must remain unchanged");
        helper.assertTrue(storage.capacityAdmissionCount() == 4, "cancellation must preserve record count");
        helper.succeed();
    }
}
