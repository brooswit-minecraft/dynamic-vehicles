package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class DrivingFeedbackGameTests {
    @GameTest(template = "settlement_test")
    public static void noBrakeSlipAndStopSurviveEntityDataSynchronization(GameTestHelper helper) {
        CarEntity server = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        CarEntity replica = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
        helper.assertTrue(replica != null, "missing receiver");
        for (double signedSpin : new double[] {2, -2}) {
            double slip = TireAudioMath.slipSpeed(3000, 3000, signedSpin, 0, 0,
                    0, 1, 0, 0, 1, 300, 0.05);
            server.publishSoundState(1, slip, 4000);
            transfer(server, replica);
            helper.assertTrue(CarSoundMath.skidVolume(replica.clientSlip()) > 0.1,
                    "no-brake wheelspin lost in entity-data synchronization");
        }
        // Same physical grip budget, with a tick-comparable report at both force substep sizes.
        for (int steps : new int[] {1, 4}) {
            var tire = WheelMath.tire(10, 0.8, 3000, 0.2, 0, 1, 0, 0, 1, 300, 0.05 / steps);
            double slip = TireAudioMath.slipSpeed(3000, tire.frictionLimit(), 0, 10, 0.8,
                    0, 1, 0, 0, 1, 300, 0.05);
            server.publishSoundState(0, slip, 2000);
            transfer(server, replica);
            helper.assertTrue(CarSoundMath.skidVolume(replica.clientSlip()) > 0.05,
                    "no-brake lateral sliding lost at " + steps + " substeps");
        }
        server.publishSoundState(0, 0.08, 900);
        transfer(server, replica);
        helper.assertTrue(replica.clientSlip() > 0, "quiet tail missing");
        server.publishSoundState(0, 0, 900);
        transfer(server, replica);
        helper.assertTrue(replica.clientSlip() == 0, "quiet tail never stopped");
        double airborne = TireAudioMath.slipSpeed(0, 0, 100, 0, 10, 0, 1, 10000, 0, 1, 300, 0.05);
        server.publishSoundState(1, airborne, 6000);
        transfer(server, replica);
        helper.assertTrue(replica.clientSlip() == 0, "airborne free-wheel squeal");
        server.publishDrivingTelemetry(4200, 0);
        transfer(server, replica);
        helper.assertTrue(replica.clientDrivingRpm() == 4200 && replica.clientDrivingSpeed() == 0,
                "stationary engine RPM must not be derived from road speed");
        server.publishDrivingTelemetry(400, 10);
        transfer(server, replica);
        helper.assertTrue(replica.clientDrivingRpm() == 400 && replica.clientDrivingSpeed() == 10,
                "raw telemetry was clamped to audio idle or changed units");
        var saved = new net.minecraft.nbt.CompoundTag();
        saved.putFloat("VehicleHealth", 37.0f);
        server.readAdditionalSaveData(saved);
        server.publishSoundState(1, 4, 6000);
        server.publishDrivingTelemetry(6000, server.spec().maxSpeed());
        transfer(server, replica);
        helper.assertTrue(Math.abs(replica.clientHealthFraction() - .37) < .0001,
                "HUD health must follow persisted server car health, not player health");
        helper.assertTrue(Math.abs(replica.clientTopSpeed() - server.spec().maxSpeed()) < .0001,
                "HUD must receive the server's intended top-speed scale");
        helper.assertTrue(replica.clientSlip() == 1 && replica.clientTireSlip() == 0,
                "HUD tire slip must not borrow the separately published audio slip value");
        helper.succeed();
    }

    private static void transfer(CarEntity source, CarEntity receiver) {
        var dirty = source.getEntityData().packDirty();
        if (dirty != null) receiver.getEntityData().assignValues(dirty);
    }
}
