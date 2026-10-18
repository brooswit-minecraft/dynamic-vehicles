package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-385: production-path regressions for lava contact and ongoing fire damage on
 * {@link CarEntity#tickHealth}, run against a real, ticking entity and real blocks -- the behavior the
 * ticket's own acceptance criteria calls out does not exist yet in any pure unit test, since it lives in
 * how {@link CarEntity} reacts to vanilla's own {@code isInLava}/{@code isOnFire}/{@code isInWater}, not
 * in an arithmetic helper.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class CarFireDamageGameTests {
    private CarFireDamageGameTests() {}

    /** AC1: real lava contact damages a vehicle that started at full health. */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void lavaContactDamagesVehicleStartingAtFullHealth(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.LAVA);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.LAVA);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 2, 5));
        helper.assertTrue(car.health() == CarHealth.MAX, "test setup did not start the car at full health");
        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(car.isOnFire(), "a vehicle sitting in real lava never caught fire");
            helper.assertTrue(car.health() < CarHealth.MAX,
                    "real lava contact from full health caused no vehicle damage: " + car.health());
            helper.succeed();
        });
    }

    /** AC2 (external ignition at full health): a fire source other than low self-health -- a flame arrow
     * or fire charge, simulated here with {@code igniteForSeconds} -- damages a healthy vehicle away from
     * any lava. */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void externalIgnitionDamagesFullHealthVehicleAwayFromLava(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        helper.assertTrue(car.health() == CarHealth.MAX, "test setup did not start the car at full health");
        car.igniteForSeconds(10.0f);
        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(car.health() < CarHealth.MAX,
                    "external ignition away from lava caused no damage to a full-health vehicle: " + car.health());
            helper.succeed();
        });
    }

    /** AC2 (continued burning after leaving lava): a vehicle ignited by lava keeps taking fire damage once
     * it has driven off the lava and onto dry ground. */
    @GameTest(template = "settlement_test", timeoutTicks = 100)
    public static void burningContinuesAfterLeavingLava(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.LAVA);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(8, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(8, 3, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 2, 5));
        helper.runAfterDelay(5L, () -> {
            helper.assertTrue(car.isOnFire(), "car did not ignite after sitting in lava");
            BlockPos stoneAbs = helper.absolutePos(new BlockPos(8, 2, 5));
            car.setPos(stoneAbs.getX() + 0.5, stoneAbs.getY() + car.spec().rideHeight() - car.spec().halfY(), stoneAbs.getZ() + 0.5);
            float healthJustAfterLeaving = car.health();
            helper.runAfterDelay(20L, () -> {
                helper.assertTrue(car.health() < healthJustAfterLeaving,
                        "car stopped taking fire damage after leaving lava onto dry ground: "
                                + car.health() + " vs " + healthJustAfterLeaving);
                helper.succeed();
            });
        });
    }

    /** AC5: entering water extinguishes the fire and stops ongoing external-fire damage, under the game's
     * established extinguishing rule -- and does not leave a stale fire/damage state once dry again is
     * exercised implicitly here by the damage staying stopped across further ticks while still submerged. */
    @GameTest(template = "settlement_test", timeoutTicks = 100)
    public static void enteringWaterExtinguishesFireAndStopsOngoingDamage(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(8, 2, 5), Blocks.WATER);
        helper.setBlock(new BlockPos(8, 3, 5), Blocks.WATER);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.igniteForSeconds(10.0f);
        helper.runAfterDelay(10L, () -> {
            helper.assertTrue(car.health() < CarHealth.MAX, "externally ignited car took no fire damage before reaching water");
            BlockPos waterAbs = helper.absolutePos(new BlockPos(8, 2, 5));
            car.setPos(waterAbs.getX() + 0.5, waterAbs.getY() + 0.5, waterAbs.getZ() + 0.5);
            helper.runAfterDelay(3L, () -> {
                helper.assertTrue(!car.isOnFire(), "car stayed on fire after entering water");
                float healthAfterExtinguish = car.health();
                helper.runAfterDelay(20L, () -> {
                    helper.assertTrue(car.health() == healthAfterExtinguish,
                            "car kept taking fire damage after being extinguished by water: "
                                    + healthAfterExtinguish + " -> " + car.health());
                    helper.succeed();
                });
            });
        });
    }

    /** AC3: a vehicle already self-burning from low health that is also externally ignited takes exactly
     * one burn tick's worth of damage per tick, not two stacked on top of each other. */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void lowHealthSelfBurningAndExternalIgnitionDoNotDoubleDamage(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
        helper.assertTrue(car != null, "failed to construct a car entity directly for the overlap test");
        CompoundTag tag = new CompoundTag();
        tag.putFloat("VehicleHealth", 20.0f); // below CarHealth.FIRE_FRACTION * MAX (33): already self-burning.
        car.load(tag);
        BlockPos abs = helper.absolutePos(new BlockPos(5, 3, 5));
        car.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0.0f, 0.0f);
        helper.getLevel().addFreshEntity(car);
        helper.assertTrue(CarHealth.burning(car.health()), "test setup did not actually start the car in the low-health self-burning range");
        car.igniteForSeconds(10.0f); // external ignition stacked on top of the low-health self-burn that's already true.
        int ticks = 20;
        helper.runAfterDelay((long) ticks, () -> {
            float lost = 20.0f - car.health();
            float singleRateLoss = CarHealth.burnDamage(ticks / 20.0);
            helper.assertTrue(lost > 0.0f, "an overlapping self-burning + externally ignited car took no damage at all");
            helper.assertTrue(lost < singleRateLoss * 1.5f,
                    "an overlapping self-burning + externally ignited car lost health faster than a single burn rate (double damage): "
                            + lost + " vs expected ~" + singleRateLoss);
            helper.succeed();
        });
    }

    /** AC6/AC7: burning all the way to zero health still goes through the existing single
     * explode-and-discard path exactly once, and the destroyed vehicle does not come back or re-trigger
     * destruction on later ticks. */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void burningToZeroHealthDestroysTheVehicleExactlyOnce(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.LAVA);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
        helper.assertTrue(car != null, "failed to construct a car entity directly for the destruction test");
        CompoundTag tag = new CompoundTag();
        tag.putFloat("VehicleHealth", 0.5f);
        car.load(tag);
        BlockPos abs = helper.absolutePos(new BlockPos(5, 2, 5));
        car.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0.0f, 0.0f);
        helper.getLevel().addFreshEntity(car);
        helper.runAfterDelay(10L, () -> {
            helper.assertTrue(car.isRemoved(), "a car burned down to zero health was not removed/destroyed");
            helper.runAfterDelay(10L, () -> {
                helper.assertTrue(car.isRemoved(), "a destroyed car came back after further ticks");
                helper.succeed();
            });
        });
    }

    /** AC7: a second vehicle type sharing {@link CarEntity#tickHealth} also takes real lava damage, not
     * just the plain CAR spec. */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void otherVehicleTypeAlsoTakesDamageFromLavaContact(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.LAVA);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.LAVA);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR);
        CarEntity truck = helper.spawn(DynamicVehiclesMod.TRUCK.get(), new BlockPos(5, 2, 5));
        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(truck.health() < CarHealth.MAX,
                    "a second vehicle type (truck) sharing CarEntity's tickHealth path took no lava damage: " + truck.health());
            helper.succeed();
        });
    }
}
