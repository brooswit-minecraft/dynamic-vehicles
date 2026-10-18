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

    /** Review (PR #166): a healthy vehicle's short external ignition (a flame arrow, not a standing fire)
     * must expire on its own, under vanilla's own fire-tick countdown, instead of the burning branch
     * re-arming the timer every tick and keeping a merely-ignited car on fire (and taking damage)
     * forever. */
    @GameTest(template = "settlement_test", timeoutTicks = 100)
    public static void healthyCarsShortExternalIgnitionExpiresOnItsOwnAndStopsDamage(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.igniteForSeconds(1.0f); // a short, one-off ignition, not sustained standing fire.
        helper.runAfterDelay(30L, () -> {
            helper.assertTrue(!car.isOnFire(),
                    "a healthy vehicle's short external ignition never expired on its own; "
                            + "the burning branch must not keep re-arming the fire timer for a merely-ignited car");
            float healthAfterExpiry = car.health();
            helper.runAfterDelay(20L, () -> {
                helper.assertTrue(car.health() == healthAfterExpiry,
                        "a healthy vehicle kept taking fire damage after its short external ignition expired: "
                                + healthAfterExpiry + " -> " + car.health());
                helper.succeed();
            });
        });
    }

    /** AC2 (continued burning after leaving lava): a vehicle ignited by lava keeps taking fire damage once
     * the lava is gone from under it (a real block-level fluid exit, the same production {@code isInLava}
     * query a real drive-away would hit) with the SAME Sable physics body staying attached and ticking
     * throughout -- never recreated, never manually repositioned.
     *
     * <p>This does not drive the car there with {@code forceDrive}: {@code NewVehicleGameTests}'
     * {@code existingCarStillMountsAndDismountsAfterTheseChanges} documents a pre-existing Sable/GameTest
     * coordinate bug (confirmed again while writing this test -- the same "Aborting entity get for
     * abnormally large AABB" Sable error, and zero net travel after 100 forced-drive ticks) that caps any
     * forced drive at roughly zero to half a block regardless of ticks given, unrelated to this ticket's
     * own CarEntity/SableCarBody changes. */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void burningContinuesAfterLeavingLava(GameTestHelper helper) {
        BlockPos floor = new BlockPos(5, 2, 5);
        BlockPos cell = new BlockPos(5, 3, 5);
        helper.setBlock(floor, Blocks.STONE);
        helper.setBlock(cell, Blocks.LAVA);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), cell);
        helper.runAfterDelay(5L, () -> {
            helper.assertTrue(car.isInLava(), "test setup did not actually submerge the car in lava");
            helper.assertTrue(car.isOnFire(), "car did not ignite after sitting in lava");
            helper.setBlock(cell, Blocks.AIR); // the lava recedes; the car and its Sable body never move.
            helper.runAfterDelay(2L, () -> {
                helper.assertTrue(!car.isInLava(), "test setup did not actually clear the lava out from under the car");
                float healthJustAfterLeaving = car.health();
                helper.runAfterDelay(20L, () -> {
                    helper.assertTrue(!car.isRemoved(), "car was removed/crashed after the lava receded");
                    helper.assertTrue(car.health() < healthJustAfterLeaving,
                            "car stopped taking fire damage after the lava receded from under it, still on fire: "
                                    + healthJustAfterLeaving + " -> " + car.health());
                    helper.succeed();
                });
            });
        });
    }

    /** AC5: entering water extinguishes the fire and stops ongoing external-fire damage, under the game's
     * established extinguishing rule -- and, per review (PR #166), actually clears the water back out
     * afterward (a real block-level fluid exit through the same production {@code isInWater} query, with
     * the SAME Sable physics body staying attached and ticking throughout -- never recreated, never
     * manually repositioned) to confirm fire and damage both stay stopped rather than merely "stopped
     * while still submerged," which could hide a bug that only reignites on dry land.
     *
     * <p>This does not drive the car out with {@code forceDrive} either -- see
     * {@link #burningContinuesAfterLeavingLava}'s javadoc for the pre-existing drive-distance cap that
     * rules that out in this harness. */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void enteringWaterExtinguishesFireAndStaysStoppedOnceDryAgain(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        BlockPos cell = new BlockPos(5, 3, 5);
        helper.setBlock(cell, Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), cell);
        car.igniteForSeconds(10.0f);
        helper.runAfterDelay(5L, () -> {
            helper.assertTrue(car.health() < CarHealth.MAX, "externally ignited car took no fire damage before reaching water");
            helper.setBlock(cell, Blocks.WATER); // the car's own cell floods; the car and its body never move.
            helper.runAfterDelay(3L, () -> {
                helper.assertTrue(car.isInWater(), "test setup did not actually flood the car's own cell with water");
                helper.assertTrue(!car.isOnFire(), "car stayed on fire after its cell flooded with water");
                float healthWhileSubmerged = car.health();
                // Short submersion, not a long one: a lone water source left for many ticks spreads
                // sideways into the template's own open neighboring space, and draining only the one cell
                // afterward then has that spread flowing water seep straight back in on the next fluid
                // update -- exactly what an earlier version of this test hit (observed: the drained cell
                // reads back as minecraft:water[level=2], a flowing remnant, not the source we placed).
                helper.runAfterDelay(3L, () -> {
                    helper.assertTrue(car.health() == healthWhileSubmerged,
                            "car kept taking fire damage while submerged and extinguished: "
                                    + healthWhileSubmerged + " -> " + car.health());
                    // Drain a small area around the cell, not just the cell itself, to also clear out any
                    // start of that sideways spread before it can seep back into the cell we care about.
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            helper.setBlock(cell.offset(dx, 0, dz), Blocks.AIR);
                        }
                    }
                    helper.runAfterDelay(3L, () -> {
                        helper.assertTrue(!car.isRemoved(), "car was removed/crashed after the water receded");
                        helper.assertTrue(!car.isInWater(), "test setup did not actually dry the car's own cell back out: y="
                                + car.getY() + " fluidHeight=" + car.getFluidHeight(net.minecraft.tags.FluidTags.WATER)
                                + " block@car=" + helper.getLevel().getBlockState(BlockPos.containing(car.getX(), car.getY() + 0.1, car.getZ())));
                        helper.assertTrue(!car.isOnFire(), "car reignited on its own once dry again");
                        helper.assertTrue(car.health() == healthWhileSubmerged,
                                "car took fire damage again once dry again: "
                                        + healthWhileSubmerged + " -> " + car.health());
                        helper.succeed();
                    });
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

    /** AC6: burning all the way to zero health removes the vehicle through the existing explode-and-discard
     * path, and it stays removed on later ticks instead of coming back. This does not assert an explosion
     * count -- only removal, and that removal sticks (see review, PR #166). */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void burningToZeroHealthRemovesTheVehicleAndItStaysRemoved(GameTestHelper helper) {
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
