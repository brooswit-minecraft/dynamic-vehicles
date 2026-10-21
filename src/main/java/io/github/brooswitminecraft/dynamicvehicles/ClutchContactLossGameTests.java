package io.github.brooswitminecraft.dynamicvehicles;

import java.lang.reflect.Field;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class ClutchContactLossGameTests {
    @GameTest(template = "settlement_test", batch = "fwdUndrivenShaftIsolated")
    public static void undrivenRearSpinCannotChangeFwdPropulsionOrAutomaticGear(GameTestHelper helper) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (CarPhysics.Gear gear : new CarPhysics.Gear[] {
                        CarPhysics.Gear.D1, CarPhysics.Gear.REVERSE, CarPhysics.Gear.AUTOMATIC}) {
                    for (boolean priorContact : new boolean[] {false, true}) {
                        double[] reference = null;
                        for (double rearSpin : new double[] {0.0, 100.0, -100.0}) {
                            CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
                            helper.assertTrue(car != null, "could not create FWD car");
                            helper.assertTrue(car.vehicleTuning().frontTorqueSplit() == 1.0,
                                    "regression requires the registered base car's FWD layout");
                            car.moveTo(0.0, 200.0, 46_000.0, 0.0f, 0.0f);
                            SableCarBody body = SableCarBody.create(helper.getLevel(), car);
                            helper.assertTrue(body != null, "could not create FWD body");
                            try {
                                set(body, "engineOmega", 100.0);
                                set(body, "contactConstraintHeld", priorContact);
                                double[] spins = new double[car.spec().mounts().length];
                                for (int wheel = 0; wheel < spins.length; wheel++) {
                                    if (car.spec().mounts()[wheel][2] <= 0.0) spins[wheel] = rearSpin;
                                }
                                set(body, "wheelSpin", spins);
                                body.tick(car, 1.0, 0.0, false, gear, 0.05);
                                double[] result = body.wheelSpinRates();
                                double[] measured = new double[result.length + 2];
                                measured[0] = body.engineOmega();
                                measured[1] = body.gearRatio();
                                for (int wheel = 0; wheel < result.length; wheel++) {
                                    helper.assertTrue(body.wheelContactSubSteps()[wheel] == 0,
                                            "fixture must remain airborne");
                                    if (car.spec().mounts()[wheel][2] > 0.0) {
                                        measured[wheel + 2] = result[wheel];
                                        helper.assertTrue(Math.abs(result[wheel]) > 0.0,
                                                "FWD drive must reach front wheels");
                                    }
                                }
                                if (reference == null) reference = measured;
                                else for (int i = 0; i < measured.length; i++) {
                                    helper.assertTrue(Math.abs(reference[i] - measured[i]) < 1e-9,
                                            "undriven rear spin changed FWD response: steps=" + steps
                                                    + " gear=" + gear + " priorContact=" + priorContact
                                                    + " rearSpin=" + rearSpin + " component=" + i);
                                }
                            } finally {
                                body.remove();
                            }
                        }
                    }
                }
            }
        } finally {
            CarConfig.WHEEL_SUB_STEPS.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "weightedShaftIsolated")
    public static void asymmetricShaftFeedbackUsesActualTorqueSharesIncludingZeroVectoringShare(GameTestHelper helper) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (double split : new double[] {0.2, 0.4, 0.5, 1.0}) {
                    for (double vectoring : new double[] {0, 1}) {
                        for (int direction : new int[] {-1, 1}) {
                            CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
                            helper.assertTrue(car != null, "could not create weighted shaft fixture");
                            car.setVehicleTuning("front_torque_split", split);
                            car.setVehicleTuning("vectoring_gain", vectoring);
                            car.setVehicleTuning("lsd_lock_percent", 0);
                            car.moveTo(0, 200, 48_000, 0, 0);
                            SableCarBody body = SableCarBody.create(helper.getLevel(), car);
                            helper.assertTrue(body != null, "could not construct weighted shaft body");
                            try {
                                double[] spins = new double[car.spec().mounts().length];
                                for (int wheel = 0; wheel < spins.length; wheel++) {
                                    double[] mount = car.spec().mounts()[wheel];
                                    if (mount[2] > 0 && mount[0] < 0) spins[wheel] = direction * 100;
                                }
                                set(body, "wheelSpin", spins);
                                set(body, "engineOmega", 500.0);
                                body.tick(car, 1, 0.5, false,
                                        direction > 0 ? CarPhysics.Gear.D1 : CarPhysics.Gear.REVERSE, 0.05);
                                // Positive steering transfers the front axle's share toward the left wheel.
                                double expected = 100 * split * (1 + vectoring) / 2
                                        * body.gearRatio() * DrivetrainMath.FINAL_DRIVE;
                                helper.assertTrue(Math.abs(body.clutchWheelSideOmega() - expected) < 1e-9,
                                        "real tick used equal-wheel feedback instead of configured torque shares: expected "
                                                + expected + " got " + body.clutchWheelSideOmega());
                            } finally {
                                body.remove();
                            }
                        }
                    }
                }
            }
        } finally {
            CarConfig.WHEEL_SUB_STEPS.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "handbrakeShaftIsolated")
    public static void handbrakedRearShaftCannotSuppressFrontPropulsion(GameTestHelper helper) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (double split : new double[] {0.0, 0.2, 0.4}) {
                    double[] reference = null;
                    for (double rearSpin : new double[] {0, 100, -100}) {
                        CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
                        helper.assertTrue(car != null, "could not create handbrake fixture");
                        car.setVehicleTuning("front_torque_split", split);
                        car.setVehicleTuning("lsd_lock_percent", 0);
                        car.moveTo(0, 200, 49_000, 0, 0);
                        SableCarBody body = SableCarBody.create(helper.getLevel(), car);
                        helper.assertTrue(body != null, "could not create handbrake body");
                        try {
                            double[] spins = new double[car.spec().mounts().length];
                            for (int w = 0; w < spins.length; w++) {
                                if (car.spec().mounts()[w][2] <= 0) spins[w] = rearSpin;
                            }
                            set(body, "wheelSpin", spins);
                            set(body, "engineOmega", 500.0);
                            body.tick(car, 1, 0, true, CarPhysics.Gear.D1, .05);
                            double[] measured = {body.engineOmega(), body.wheelSpinRates()[0], body.wheelSpinRates()[1]};
                            helper.assertTrue(Double.isFinite(measured[0]), "all-braked layout produced nonfinite engine state");
                            if (split > 0) helper.assertTrue(measured[1] > 0 && measured[2] > 0,
                                    "fast handbraked rear wheels suppressed front drive");
                            else helper.assertTrue(measured[1] == 0 && measured[2] == 0,
                                    "rear-only layout drove disconnected front wheels");
                            if (reference == null) reference = measured;
                            else for (int i = 0; i < measured.length; i++) {
                                helper.assertTrue(Math.abs(reference[i] - measured[i]) < 1e-9,
                                        "handbraked rear spin affected active front shaft or engine");
                            }
                        } finally { body.remove(); }
                    }
                }
            }
        } finally { CarConfig.WHEEL_SUB_STEPS.set(original); }
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "zeroShareContactIsolated")
    public static void contactOnZeroVectoringShareCannotHoldOrReactAgainstDriveShaft(GameTestHelper helper) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
                helper.assertTrue(car != null, "could not create partial-contact fixture");
                car.setVehicleTuning("front_torque_split", 1);
                car.setVehicleTuning("vectoring_gain", 1);
                car.setVehicleTuning("lsd_lock_percent", 0);
                net.minecraft.core.BlockPos origin = helper.absolutePos(new net.minecraft.core.BlockPos(5, 2, 5));
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    for (int y = 0; y <= 4; y++) helper.getLevel().setBlock(origin.offset(x, y, z),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
                // Only the front RIGHT wheel has ground; positive full vectoring sends all drive LEFT.
                helper.getLevel().setBlock(origin.offset(1, 0, 1),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                car.moveTo(origin.getX() + .5, origin.getY() + 1 + car.spec().rideHeight() - car.spec().halfY(),
                        origin.getZ() + .5, 0, 0);
                SableCarBody body = SableCarBody.create(helper.getLevel(), car);
                helper.assertTrue(body != null, "could not create partial-contact body");
                try {
                    set(body, "wheelSpin", new double[] {0, 100, 0, 0});
                    set(body, "engineOmega", 500.0);
                    body.tick(car, 1, .5, false, CarPhysics.Gear.D1, .05);
                    helper.assertTrue(body.wheelContactSubSteps()[1] > 0 && body.wheelContactSubSteps()[0] == 0,
                            "fixture must load only the zero-drive front wheel");
                    helper.assertTrue(body.drivenTireForce() == 0,
                            "zero-share contact fed tire reaction back into the clutch");
                    Field held = SableCarBody.class.getDeclaredField("contactConstraintHeld");
                    held.setAccessible(true);
                    helper.assertTrue(!held.getBoolean(body), "zero-share contact held the active airborne shaft");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                } finally { body.remove(); }
            }
        } finally { CarConfig.WHEEL_SUB_STEPS.set(original); }
        helper.succeed();
    }

    /** Seed the exact reviewed prior-contact state, then run the real tick on a currently free shaft.
     * Measurements come from both shafts' actual before/after velocities, never the command ledger. */
    @GameTest(template = "settlement_test", batch = "clutchContactLossIsolated")
    public static void currentFreePartnerCannotCreateShaftEnergyAfterContactLoss(GameTestHelper helper) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (CarPhysics.Gear gear : new CarPhysics.Gear[] {CarPhysics.Gear.D2, CarPhysics.Gear.REVERSE}) {
                    CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
                    helper.assertTrue(car != null, "could not construct fixture car");
                    car.moveTo(0.0, 200.0, 45_000.0, 0.0f, 0.0f);
                    SableCarBody body = SableCarBody.create(helper.getLevel(), car);
                    helper.assertTrue(body != null, "could not construct fixture body");
                    try {
                        set(body, "engineOmega", 258.3333333333333);
                        set(body, "contactConstraintHeld", true);
                        set(body, "drivenTireForce", 0.0);
                        for (int tick = 0; tick < 20; tick++) {
                            // Include an upshift while the partner remains free.
                            CarPhysics.Gear selected = gear == CarPhysics.Gear.D2 && tick >= 10
                                    ? CarPhysics.Gear.D3 : gear;
                            double beforeEngine = body.engineOmega();
                            double[] beforeWheels = body.wheelSpinRates();
                            body.tick(car, 1.0, 0.0, false, selected, 0.05);
                            double afterEngine = body.engineOmega();
                            double[] afterWheels = body.wheelSpinRates();
                            double energyDelta = 0.5 * DrivetrainMath.ENGINE_INERTIA
                                    * (afterEngine * afterEngine - beforeEngine * beforeEngine);
                            double momentumDelta = DrivetrainMath.ENGINE_INERTIA * (afterEngine - beforeEngine);
                            double gearing = body.gearRatio() * DrivetrainMath.FINAL_DRIVE
                                    * (selected == CarPhysics.Gear.REVERSE ? -1.0 : 1.0);
                            for (int wheel = 0; wheel < afterWheels.length; wheel++) {
                                helper.assertTrue(body.wheelContactSubSteps()[wheel] == 0,
                                        "fixture wheel unexpectedly contacted ground");
                                energyDelta += 0.5 * WheelMath.WHEEL_INERTIA
                                        * (afterWheels[wheel] * afterWheels[wheel] - beforeWheels[wheel] * beforeWheels[wheel]);
                                momentumDelta += WheelMath.WHEEL_INERTIA * (afterWheels[wheel] - beforeWheels[wheel]) / gearing;
                            }
                            double externalImpulse = body.engineOutputTorque() * 0.05;
                            // The non-reversing crank stop is an external constraint. Account for its
                            // impulse only when the unbounded integrator would cross zero; the reviewed
                            // first-loss counterexample never touches this stop.
                            double stopImpulse = body.engineStopImpulse();
                            if (tick == 0) {
                                helper.assertTrue(stopImpulse == 0.0, "first-loss fixture must not hit the crank stop");
                            }
                            externalImpulse += stopImpulse;
                            double externalWork = body.engineExternalWork();
                            String context = "steps=" + steps + " gear=" + selected + " tick=" + tick;
                            helper.assertTrue(Double.isFinite(energyDelta) && energyDelta - externalWork <= 1e-6,
                                    "clutch created shaft energy: " + (energyDelta - externalWork) + " J; " + context);
                            helper.assertTrue(Math.abs(momentumDelta - externalImpulse) < 1e-6,
                                    "measured shaft momentum imbalance: " + (momentumDelta - externalImpulse) + "; " + context);
                        }
                    } finally {
                        body.remove();
                    }
                }
            }
        } finally {
            CarConfig.WHEEL_SUB_STEPS.set(original);
        }
        helper.succeed();
    }

    /** Unequal axle speeds and asymmetric shares must preserve the real two-shaft energy budget.
     * This guards passivity only; it does not prove the current equal-wheel shift proposal is correct. */
    @GameTest(template = "settlement_test", batch = "asymmetricClutchIsolated")
    public static void asymmetricLayoutsCannotCreateShaftEnergyAfterContactLoss(GameTestHelper helper) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            for (double split : new double[] {0.2, 0.4, 0.5, 1.0}) {
            for (double lock : new double[] {0.0, 1.0}) {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (CarPhysics.Gear gear : new CarPhysics.Gear[] {CarPhysics.Gear.D2, CarPhysics.Gear.REVERSE}) {
                    CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
                    helper.assertTrue(car != null, "could not construct fixture car");
                    car.setVehicleTuning("front_torque_split", split);
                    car.setVehicleTuning("lsd_lock_percent", lock);
                    car.moveTo(0.0, 200.0, 47_000.0, 0.0f, 0.0f);
                    SableCarBody body = SableCarBody.create(helper.getLevel(), car);
                    helper.assertTrue(body != null, "could not construct fixture body");
                    try {
                        set(body, "engineOmega", 258.3333333333333);
                        set(body, "contactConstraintHeld", true);
                        set(body, "drivenTireForce", 0.0);
                        double[] seededSpins = new double[car.spec().mounts().length];
                        for (int wheel = 0; wheel < seededSpins.length; wheel++) {
                            seededSpins[wheel] = (car.spec().mounts()[wheel][2] > 0 ? 12 : 3)
                                    * (car.spec().mounts()[wheel][0] < 0 ? 1 : 0.25)
                                    * (gear == CarPhysics.Gear.REVERSE ? -1 : 1);
                        }
                        set(body, "wheelSpin", seededSpins);
                        for (int tick = 0; tick < 20; tick++) {
                            // Include an upshift while the partner remains free.
                            CarPhysics.Gear selected = gear == CarPhysics.Gear.D2 && tick >= 10
                                    ? CarPhysics.Gear.D3 : gear;
                            double beforeEngine = body.engineOmega();
                            double[] beforeWheels = body.wheelSpinRates();
                            body.tick(car, 1.0, 0.0, false, selected, 0.05);
                            double afterEngine = body.engineOmega();
                            double[] afterWheels = body.wheelSpinRates();
                            double energyDelta = 0.5 * DrivetrainMath.ENGINE_INERTIA
                                    * (afterEngine * afterEngine - beforeEngine * beforeEngine);
                            double momentumDelta = DrivetrainMath.ENGINE_INERTIA * (afterEngine - beforeEngine);
                            double gearing = body.gearRatio() * DrivetrainMath.FINAL_DRIVE
                                    * (selected == CarPhysics.Gear.REVERSE ? -1.0 : 1.0);
                            for (int wheel = 0; wheel < afterWheels.length; wheel++) {
                                helper.assertTrue(body.wheelContactSubSteps()[wheel] == 0,
                                        "fixture wheel unexpectedly contacted ground");
                                energyDelta += 0.5 * WheelMath.WHEEL_INERTIA
                                        * (afterWheels[wheel] * afterWheels[wheel] - beforeWheels[wheel] * beforeWheels[wheel]);
                                momentumDelta += WheelMath.WHEEL_INERTIA * (afterWheels[wheel] - beforeWheels[wheel]) / gearing;
                            }
                            double externalImpulse = body.engineOutputTorque() * 0.05;
                            // The non-reversing crank stop is an external constraint. Account for its
                            // impulse only when the unbounded integrator would cross zero; the reviewed
                            // first-loss counterexample never touches this stop.
                            double stopImpulse = body.engineStopImpulse();
                            if (tick == 0) {
                                helper.assertTrue(stopImpulse == 0.0, "first-loss fixture must not hit the crank stop");
                            }
                            externalImpulse += stopImpulse;
                            double externalWork = body.engineExternalWork();
                            String context = "split=" + split + " lock=" + lock + " steps=" + steps + " gear=" + selected + " tick=" + tick;
                            helper.assertTrue(Math.abs(body.clutchTorque())
                                            <= car.spec().drivetrainCoupling().clutchTorqueCapacity() + 1e-6,
                                    "realized transfer exceeded clutch capacity; " + context);
                            helper.assertTrue(Double.isFinite(energyDelta) && energyDelta - externalWork <= 1e-6,
                                    "clutch created shaft energy: " + (energyDelta - externalWork) + " J; " + context);
                            helper.assertTrue(Math.abs(momentumDelta - externalImpulse) < 1e-6,
                                    "measured shaft momentum imbalance: " + (momentumDelta - externalImpulse) + "; " + context);
                        }
                    } finally {
                        body.remove();
                    }
                }
            }
            }
            }
        } finally {
            CarConfig.WHEEL_SUB_STEPS.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "clutchPartialOne", timeoutTicks = 300)
    public static void partialContactEnergyAtOneSubstep(GameTestHelper helper) {
        VehicleLanes.restoreSubStepsOnEveryOutcome(helper);
        CarConfig.WHEEL_SUB_STEPS.set(1);
        CarDrivetrainGameTests.aPartiallyUnloadedDrivenWheelKeepsTurningWithoutBreakingTheBooksOrGroundRecovery(helper);
    }

    @GameTest(template = "settlement_test", batch = "clutchPartialFour", timeoutTicks = 300)
    public static void partialContactEnergyAtFourSubsteps(GameTestHelper helper) {
        VehicleLanes.restoreSubStepsOnEveryOutcome(helper);
        CarConfig.WHEEL_SUB_STEPS.set(4);
        CarDrivetrainGameTests.aPartiallyUnloadedDrivenWheelKeepsTurningWithoutBreakingTheBooksOrGroundRecovery(helper);
    }

    @GameTest(template = "settlement_test", batch = "clutchReverseOne", timeoutTicks = 400)
    public static void reverseEnergyAtOneSubstep(GameTestHelper helper) {
        VehicleLanes.restoreSubStepsOnEveryOutcome(helper);
        CarConfig.WHEEL_SUB_STEPS.set(1);
        CarDrivetrainGameTests.theRealizedClutchExchangeBalancesSignedThroughOpposedRollingAndTheReverseGovernor(helper);
    }

    @GameTest(template = "settlement_test", batch = "clutchReverseFour", timeoutTicks = 400)
    public static void reverseEnergyAtFourSubsteps(GameTestHelper helper) {
        VehicleLanes.restoreSubStepsOnEveryOutcome(helper);
        CarConfig.WHEEL_SUB_STEPS.set(4);
        CarDrivetrainGameTests.theRealizedClutchExchangeBalancesSignedThroughOpposedRollingAndTheReverseGovernor(helper);
    }
    private static void set(SableCarBody body, String name, Object value) {
        try {
            Field field = SableCarBody.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(body, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not seed contact-loss fixture", e);
        }
    }
}
