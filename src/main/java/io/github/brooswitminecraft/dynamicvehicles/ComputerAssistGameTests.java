package io.github.brooswitminecraft.dynamicvehicles;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

/** Real Sable ticks over world collision geometry, with enabled per-car JSON configuration.
 * The tap observes commands actually handed to the tire, never the clutch predictor's trial commands.
 * Fixtures seed shaft state/velocity but do not inject contacts, loads, or controller outputs. */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class ComputerAssistGameTests {
    private ComputerAssistGameTests() {}

    @GameTest(template = "settlement_test", batch = "computerAssistIsolated")
    public static void enabledTractionControlCutsActualGroundedDriveInBothDirections(GameTestHelper helper) {
        withSubsteps(() -> {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (int direction : new int[] {1, -1}) {
                    for (double level : new double[] {0.0, 0.5}) {
                        try (Fixture f = fixture(helper, level, 0, false, 0, direction * 20)) {
                            List<SableCarBody.AssistSample> samples = new ArrayList<>();
                            f.body.assistObserver = samples::add;
                            f.body.tick(f.car, 1, 0, false,
                                    direction > 0 ? CarPhysics.Gear.D1 : CarPhysics.Gear.REVERSE, 0.05);
                            helper.assertTrue(!samples.isEmpty(), "TC fixture never reached a loaded contact");
                            SableCarBody.AssistSample first = samples.getFirst();
                            helper.assertTrue(first.requestedDrive() * direction > 0,
                                    "TC fixture must request propulsion in selected direction");
                            helper.assertTrue((first.wheelSpeed() - first.contactSpeed()) * direction > 0.75,
                                    "TC fixture must actually have excess wheelspin");
                            helper.assertTrue(Math.abs(first.appliedDrive() / first.requestedDrive() - (1 - level)) < 1e-9,
                                    "enabled/disabled TC was not applied to the actual tire command: " + first);
                            helper.assertTrue(f.body.wheelContactSubSteps()[first.wheel()] > 0,
                                    "TC sample was not consumed by a loaded production tire");
                        }
                    }
                }
            }
        });
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "computerAssistIsolated")
    public static void enabledAbsReleasesServiceBrakeButPreservesRearHandbrake(GameTestHelper helper) {
        withSubsteps(() -> {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (int direction : new int[] {1, -1}) {
                    for (boolean handbrake : new boolean[] {false, true}) {
                        for (double level : new double[] {0.0, 0.5}) {
                            try (Fixture f = fixture(helper, 0, level, false, direction * 8, 0)) {
                                List<SableCarBody.AssistSample> samples = new ArrayList<>();
                                f.body.assistObserver = samples::add;
                                f.body.tick(f.car, -1, 0, handbrake, CarPhysics.Gear.NEUTRAL, 0.05);
                                helper.assertTrue(samples.size() >= 4, "ABS fixture needs all four loaded contacts");
                                // First substep only: later substeps must consume their own changed velocities.
                                for (SableCarBody.AssistSample sample : samples.subList(0, 4)) {
                                    helper.assertTrue(sample.requestedBrake() > 0, "brake fixture requested no brake");
                                    helper.assertTrue(Math.abs(sample.contactSpeed()) > 0.75 && sample.wheelSpeed() == 0,
                                            "ABS fixture must begin rolling with a locked wheel");
                                    double expected = sample.manualHandbrake() ? 1 : 1 - level;
                                    helper.assertTrue(Math.abs(sample.appliedBrake() / sample.requestedBrake() - expected) < 1e-9,
                                            "service ABS or manual handbrake routing incorrect: " + sample);
                                    helper.assertTrue(sample.appliedDrive() == 0, "neutral braking created drive");
                                    boolean rear = f.car.spec().mounts()[sample.wheel()][2] <= 0;
                                    helper.assertTrue(sample.manualHandbrake() == (handbrake && rear),
                                            "handbrake bypass applied to wrong axle");
                                }
                                double speedAfter = f.handle.getLinearVelocity(new Vector3d()).z * direction;
                                helper.assertTrue(speedAfter < 8 && speedAfter >= 0,
                                        "actual brake tick must slow the body without reversing it: " + speedAfter);
                            }
                        }
                    }
                }
            }
        });
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "computerAssistIsolated")
    public static void enabledComputersCannotInterveneWithoutContact(GameTestHelper helper) {
        withSubsteps(() -> {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (boolean braking : new boolean[] {false, true}) {
                    double[] reference = null;
                    for (double level : new double[] {0, 1}) {
                        try (Fixture f = fixture(helper, level, level, true, 8, 50)) {
                            List<SableCarBody.AssistSample> samples = new ArrayList<>();
                            f.body.assistObserver = samples::add;
                            f.body.tick(f.car, braking ? -1 : 1, 0, false,
                                    braking ? CarPhysics.Gear.NEUTRAL : CarPhysics.Gear.D1, 0.05);
                            helper.assertTrue(samples.isEmpty(), "airborne wheel entered grounded assist path");
                            for (int contacts : f.body.wheelContactSubSteps()) {
                                helper.assertTrue(contacts == 0, "airborne fixture touched a block");
                            }
                            double[] spin = f.body.wheelSpinRates();
                            if (reference == null) reference = spin;
                            else for (int wheel = 0; wheel < spin.length; wheel++) {
                                helper.assertTrue(Math.abs(spin[wheel] - reference[wheel]) < 1e-9,
                                        "TC/ABS changed a free wheel's production dynamics");
                            }
                        }
                    }
                }
            }
        });
        helper.succeed();
    }

    /** Actual shaft endpoint measurements, with both aids enabled and real mixed tire contacts. */
    @GameTest(template = "settlement_test", batch = "computerAssistEnergyIsolated")
    public static void enabledAssistsPreserveShaftEnergyAcrossContactsAndShifts(GameTestHelper helper) {
        withSubsteps(() -> {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (var surface : new net.minecraft.world.level.block.Block[] {Blocks.STONE, Blocks.ICE}) {
                    for (boolean partial : new boolean[] {false, true}) {
                        for (double speed : new double[] {8, -8, .1}) {
                            try (Fixture f = fixture(helper, .5, .5, false, speed, Math.copySign(30, speed))) {
                                BlockPos centre = new BlockPos(0, 100, 52_000);
                                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                                    helper.getLevel().setBlock(centre.offset(x, 0, z),
                                            (partial && x < 0 ? Blocks.AIR : surface).defaultBlockState(), 3);
                                }
                                int[] interventions = {0};
                                f.body.assistObserver = sample -> {
                                    if (Math.abs(sample.appliedDrive()) < Math.abs(sample.requestedDrive())
                                            || sample.appliedBrake() < sample.requestedBrake()) interventions[0]++;
                                };
                                for (int tick = 0; tick < 6; tick++) {
                                    double beforeEngine = f.body.engineOmega();
                                    double[] before = f.body.wheelSpinRates();
                                    CarPhysics.Gear gear = speed < 0 ? CarPhysics.Gear.REVERSE
                                            : tick < 3 ? CarPhysics.Gear.D1 : CarPhysics.Gear.D2;
                                    f.body.tick(f.car, tick % 2 == 0 ? 1 : -1, 0, false, gear, .05);
                                    double gearing = f.body.gearRatio() * DrivetrainMath.FINAL_DRIVE
                                            * (gear == CarPhysics.Gear.REVERSE ? -1 : 1);
                                    double energy = .5 * DrivetrainMath.ENGINE_INERTIA
                                            * (f.body.engineOmega()*f.body.engineOmega()-beforeEngine*beforeEngine);
                                    double momentum = DrivetrainMath.ENGINE_INERTIA * (f.body.engineOmega()-beforeEngine);
                                    double[] after = f.body.wheelSpinRates();
                                    int contacts = 0;
                                    for (int w = 0; w < after.length; w++) {
                                        energy += .5 * WheelMath.WHEEL_INERTIA * (after[w]*after[w]-before[w]*before[w])
                                                / f.car.spec().forceScale();
                                        momentum += WheelMath.WHEEL_INERTIA * (after[w]-before[w])
                                                / (gearing * f.car.spec().forceScale());
                                        if (f.body.wheelContactSubSteps()[w] > 0) contacts++;
                                    }
                                    // Initial contacts establish the scenario. Later spring/velocity response may
                                    // unload tires; keep auditing those contact-loss ticks rather than hiding them.
                                    if (tick == 0) helper.assertTrue(contacts == (partial ? 2 : 4),
                                            "initial mixed-contact fixture invalid: contacts="+contacts+" partial="+partial+" speed="+speed);
                                    double externalWork = f.body.engineExternalWork()
                                            + f.body.wheelExternalWork() / f.car.spec().forceScale();
                                    double externalImpulse = f.body.engineOutputTorque()*.05 + f.body.engineStopImpulse()
                                            + f.body.wheelExternalAngularImpulse() / (gearing*f.car.spec().forceScale());
                                    String context = "steps="+steps+" partial="+partial+" speed="+speed+" tick="+tick;
                                    helper.assertTrue(Double.isFinite(energy) && energy-externalWork < 1e-5,
                                            "enabled assists created shaft energy: "+(energy-externalWork)+" "+context);
                                    helper.assertTrue(Math.abs(momentum-externalImpulse)<1e-5,
                                            "enabled assists violated signed shaft impulse: "+(momentum-externalImpulse)+" "+context);
                                }
                                helper.assertTrue(interventions[0] > 0, "energy fixture never exercised an enabled assist");
                            }
                        }
                    }
                }
            }
        });
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, double tc, double abs, boolean airborne,
            double speed, double spin) {
        CarEntity car;
        try {
            Path dir = Files.createTempDirectory("computer-assist-gametest");
            Path cars = dir.resolve("dynamicvehicles/cars");
            Files.createDirectories(cars);
            Files.writeString(cars.resolve(VehicleIds.CAR + ".json"),
                    "{\"computer\":{\"tractionControlLevel\":" + tc + ",\"absLevel\":" + abs + "}}");
            VehicleSpecOverrides.load(dir);
            car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            VehicleSpecOverrides.load(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());
        }
        helper.assertTrue(car != null, "could not create configured car");
        helper.assertTrue(car.spec().computerTuning().tractionControlLevel() == tc
                && car.spec().computerTuning().absLevel() == abs, "JSON did not reach fixture spec");
        BlockPos centre = new BlockPos(0, 100, 52_000);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            helper.getLevel().setBlock(centre.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 1; y <= 5; y++) {
                helper.getLevel().setBlock(centre.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        car.moveTo(centre.getX() + 0.5,
                centre.getY() + 1 + car.spec().rideHeight() - car.spec().halfY() + (airborne ? 4 : 0),
                centre.getZ() + 0.5, 0, 0);
        SableCarBody body = SableCarBody.create(helper.getLevel(), car);
        helper.assertTrue(body != null, "Sable did not construct a body");
        try {
            set(body, "engineOmega", 500.0);
            double[] spins = new double[car.spec().mounts().length];
            Arrays.fill(spins, spin);
            set(body, "wheelSpin", spins);
            Field field = SableCarBody.class.getDeclaredField("body");
            field.setAccessible(true);
            RigidBodyHandle handle = (RigidBodyHandle) field.get(body);
            handle.applyImpulseAtPoint(new Vector3d(), new Vector3d(0, 0, speed * car.spec().massKg()));
            return new Fixture(car, body, handle);
        } catch (ReflectiveOperationException | RuntimeException e) {
            body.remove();
            throw new IllegalStateException("could not seed assist fixture", e);
        }
    }

    private static void set(SableCarBody body, String name, Object value) throws ReflectiveOperationException {
        Field field = SableCarBody.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(body, value);
    }

    private static void withSubsteps(Runnable test) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        try { test.run(); } finally { CarConfig.WHEEL_SUB_STEPS.set(original); }
    }

    private record Fixture(CarEntity car, SableCarBody body, RigidBodyHandle handle) implements AutoCloseable {
        @Override public void close() { body.assistObserver = null; body.remove(); }
    }
}
