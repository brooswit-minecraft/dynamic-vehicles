package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.client.Minecraft;

/**
 * Client lifecycle for one car's radio audio (MINECRAFT-394): starts a {@link RadioSoundInstance} when the
 * car's synced station/on-off state calls for one that isn't already playing, and stops/replaces it the
 * instant that state changes -- a station switch, the radio going off (no controlling passenger), or the
 * car disappearing -- so no stream, socket or decode thread ever outlives the state that started it.
 * Mirrors how {@link CarSoundsClient#start} is wired from {@link CarEntity#tick}'s client branch, except
 * this is checked every tick (not started once) because, unlike the engine/tire loops that just change
 * volume and pitch, a station change here must swap out the instance (and its network connection)
 * entirely rather than have the same instance keep playing with different numbers.
 */
final class CarRadioClient {
    private CarRadioClient() {}

    /**
     * Called every client tick from {@link CarEntity#tick}. {@code current} is whatever this returned
     * last tick for this car (or {@code null} before the first call); the return value is what the
     * entity should hold onto for next tick.
     */
    static Object tick(CarEntity car, Object current) {
        RadioSoundInstance playing = current instanceof RadioSoundInstance instance ? instance : null;
        String wantedUrl = car.radioOn() ? car.radioUrl() : "";
        if (playing != null && (playing.isStopped() || !playing.url().equals(wantedUrl))) {
            stop(playing);
            playing = null;
        }
        if (playing == null && !wantedUrl.isEmpty()) {
            playing = new RadioSoundInstance(car, wantedUrl);
            Minecraft.getInstance().getSoundManager().play(playing);
        }
        return playing;
    }

    /** Stops whatever this car's radio is currently holding, if anything -- safe to call with the field's
     * raw {@code Object} (including {@code null}) directly, e.g. from {@link CarEntity#remove}. Calls
     * both halves of teardown: {@code instance.stop()} cancels/closes a still-connecting (or
     * already-connected-but-unclaimed) {@link RadioAudioStream} (MINECRAFT-394 review finding #4), and
     * {@code SoundManager.stop} tears down the vanilla channel for whatever is already attached/playing. */
    static void stop(Object current) {
        if (current instanceof RadioSoundInstance instance) {
            instance.stop();
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }
}
