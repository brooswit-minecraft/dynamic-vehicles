package io.github.brooswitminecraft.dynamicvehicles;

import java.lang.reflect.Field;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class ClutchContactLossGameTests {
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
