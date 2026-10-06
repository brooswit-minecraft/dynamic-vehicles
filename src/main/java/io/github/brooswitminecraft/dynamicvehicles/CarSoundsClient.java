package io.github.brooswitminecraft.dynamicvehicles;

import java.util.function.DoubleSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import io.github.brooswitminecraft.dynamicterrain.Surfaces;

/**
 * Client side of the car's sound: looping engine, tire and skid sounds that follow the car and are faded and
 * pitched from what it is doing. Everything is positional, so other players hear the car too. Only loaded on
 * the client; the entity reaches it through {@link CarEntity#tick}'s client branch.
 */
final class CarSoundsClient {
    private CarSoundsClient() {}

    /** Start this car's loops. Called once per car on the client. */
    static Object start(CarEntity car) {
        var manager = Minecraft.getInstance().getSoundManager();
        var loops = new Loop[] {
                new Loop(ModSounds.ENGINE_IDLE.get(), car, () -> engineOn(car) ? CarSoundMath.engineMix(car.clientSpeed())[0] * CarSoundMath.engineVolume(car.clientThrottle()) : 0.0,
                        () -> CarSoundMath.enginePitch(car.clientSpeed(), car.clientThrottle())),
                new Loop(ModSounds.ENGINE_MID.get(), car, () -> engineOn(car) ? CarSoundMath.engineMix(car.clientSpeed())[1] * CarSoundMath.engineVolume(car.clientThrottle()) : 0.0,
                        () -> CarSoundMath.enginePitch(car.clientSpeed(), car.clientThrottle())),
                new Loop(ModSounds.ENGINE_HIGH.get(), car, () -> engineOn(car) ? CarSoundMath.engineMix(car.clientSpeed())[2] * CarSoundMath.engineVolume(car.clientThrottle()) : 0.0,
                        () -> CarSoundMath.enginePitch(car.clientSpeed(), car.clientThrottle())),
                new Loop(ModSounds.TIRE_ROUGH.get(), car, () -> surface(car) == Surface.ROUGH ? rolling(car) : 0.0, () -> CarSoundMath.rollingPitch(car.clientSpeed())),
                new Loop(ModSounds.TIRE_SMOOTH.get(), car, () -> surface(car) == Surface.SMOOTH ? rolling(car) : 0.0, () -> CarSoundMath.rollingPitch(car.clientSpeed())),
                new Loop(ModSounds.TIRE_SNOW.get(), car, () -> surface(car) == Surface.SNOW ? rolling(car) : 0.0, () -> CarSoundMath.rollingPitch(car.clientSpeed())),
                new Loop(ModSounds.TIRE_SKID_LIGHT.get(), car, () -> !CarSoundMath.hardSkid(car.clientSlip()) ? CarSoundMath.skidVolume(car.clientSlip()) : 0.0, () -> 1.0),
                new Loop(ModSounds.TIRE_SKID_HARD.get(), car, () -> CarSoundMath.hardSkid(car.clientSlip()) ? CarSoundMath.skidVolume(car.clientSlip()) : 0.0, () -> 1.0),
        };
        for (Loop loop : loops) {
            manager.play(loop);
        }
        return loops;
    }

    private static boolean engineOn(CarEntity car) {
        return car.getControllingPassenger() != null;
    }

    private static double rolling(CarEntity car) {
        return car.isRigidBody() || car.clientSpeed() > 0.5 ? CarSoundMath.rollingVolume(car.clientSpeed()) : 0.0;
    }

    private enum Surface { ROUGH, SMOOTH, SNOW }

    private static Surface surface(CarEntity car) {
        BlockPos below = car.blockPosition().below();
        BlockState state = car.level().getBlockState(below);
        if (state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(Blocks.POWDER_SNOW)) {
            return Surface.SNOW;
        }
        return Surfaces.at(car.level(), below).roughness() >= 0.45 ? Surface.ROUGH : Surface.SMOOTH;
    }

    /** One looping, positional sound that follows the car and takes its loudness and pitch from suppliers. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private final CarEntity car;
        private final DoubleSupplier volumeFn;
        private final DoubleSupplier pitchFn;

        Loop(SoundEvent event, CarEntity car, DoubleSupplier volumeFn, DoubleSupplier pitchFn) {
            super(event, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.car = car;
            this.volumeFn = volumeFn;
            this.pitchFn = pitchFn;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.001f;
            this.x = car.getX();
            this.y = car.getY();
            this.z = car.getZ();
        }

        @Override
        public void tick() {
            if (car.isRemoved()) {
                stop();
                return;
            }
            x = car.getX();
            y = car.getY() + 0.5;
            z = car.getZ();
            volume = (float) Math.max(0.001, Math.min(1.0, volumeFn.getAsDouble() * ClientConfig.SOUND_VOLUME.get()));
            pitch = (float) Math.max(0.5, Math.min(2.0, pitchFn.getAsDouble()));
        }
    }
}
