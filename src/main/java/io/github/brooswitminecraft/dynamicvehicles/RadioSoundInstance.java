package io.github.brooswitminecraft.dynamicvehicles;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * One car's radio audio (MINECRAFT-394): a vehicle-positioned, continuously streamed sound backed by
 * {@link RadioAudioStream} rather than a resource-pack sound file. Uses the Neo-added
 * {@link SoundInstance#getStream} extension point (see its javadoc: "Allow SoundInstances to change
 * source of audio stream if needed") so vanilla's own {@code SoundEngine}/{@code Channel} attach, pump
 * (every client tick, via {@code ChannelAccess.scheduleTick}) and tear down the stream exactly like any
 * other streamed sound -- this never drives LWJGL/OpenAL directly. {@link #getSound} returns a
 * synthetic {@code Sound} with {@code stream=true} purely so the engine picks that streaming code path
 * (a {@code STREAMING} channel, no resource-pack lookup); it names no real sound event or file.
 *
 * <p>Exactly one instance plays exactly one station for exactly one car. {@link CarRadioClient} discards
 * this (via {@code SoundManager.stop}) and starts a fresh instance on any station change, the radio going
 * off, or the car disappearing -- this instance never lingers past the state that created it.
 */
final class RadioSoundInstance implements TickableSoundInstance {
    private final CarEntity car;
    private final String url;
    private final Sound sound;
    private final WeighedSoundEvents weighedSoundEvents;
    private double x;
    private double y;
    private double z;
    private boolean stopped;
    /** Set by {@link #getStream}, read/cancelled by {@link #stop} (MINECRAFT-394 review finding #4):
     * ensures a still-connecting (or already-connected-but-now-unwanted) {@link RadioAudioStream} is
     * always closed if this instance stops before vanilla ever gets to attach it to a channel. */
    private PendingAsyncOpen<AudioStream> pendingOpen;

    RadioSoundInstance(CarEntity car, String url) {
        this.car = car;
        this.url = url;
        this.sound = new Sound(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "car_radio"),
                ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1, Sound.Type.FILE, true, false, 16);
        this.weighedSoundEvents = new WeighedSoundEvents(sound.getLocation(), null);
        this.weighedSoundEvents.addSound(sound);
        this.x = car.getX();
        this.y = car.getY() + 0.5;
        this.z = car.getZ();
    }

    /** The station URL this instance plays, so {@link CarRadioClient} can tell when the car's synced
     * selection has moved on to a different one. */
    String url() {
        return url;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound streamSound, boolean looping) {
        // Deliberately ignores soundBuffers/streamSound: this never goes through the resource-pack
        // SoundBufferLibrary at all, only our own network connection to the station URL. Tracked via
        // pendingOpen (not just returned bare) so stop() can cancel/close it if this instance is torn
        // down before vanilla ever attaches the result to a channel (MINECRAFT-394 review finding #4).
        PendingAsyncOpen<AudioStream> opening = new PendingAsyncOpen<>(() -> {
            try {
                return (AudioStream) RadioAudioStream.open(url);
            } catch (IOException e) {
                DynamicVehiclesMod.LOGGER.info("Radio station unavailable, staying silent ({}): {}", url, e.toString());
                throw new CompletionException(e);
            }
        }, RadioAudioStream.EXECUTOR);
        this.pendingOpen = opening;
        return opening.future();
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Marks this instance stopped and, if {@link #getStream} started connecting but nobody has (yet)
     * claimed the result, cancels that in-flight open and/or closes whatever it already produced
     * (MINECRAFT-394 review finding #4). Idempotent and safe to call either from {@link #tick} noticing
     * it should stop, or directly from {@link CarRadioClient} on dismount/removal/station switch.
     */
    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (pendingOpen != null) {
            pendingOpen.cancel();
        }
    }

    @Override
    public void tick() {
        if (stopped) {
            return;
        }
        // The vehicle was removed/unloaded, the driver left (radioOn() mirrors CarSoundsClient's own
        // engine-on check), or the car cycled to a different station since this instance started --
        // any of those must stop this exact stream rather than let it play on orphaned.
        if (car.isRemoved() || !car.radioOn() || !url.equals(car.radioUrl())) {
            stop();
            return;
        }
        x = car.getX();
        y = car.getY() + 0.5;
        z = car.getZ();
    }

    @Override
    public ResourceLocation getLocation() {
        return sound.getLocation();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        return weighedSoundEvents;
    }

    @Override
    public Sound getSound() {
        return sound;
    }

    @Override
    public SoundSource getSource() {
        return SoundSource.NEUTRAL;
    }

    @Override
    public boolean isLooping() {
        // No functional effect for a streamed sound (SoundEngine forces Channel looping off whenever the
        // underlying Sound streams -- see its play() method), but logically true: this plays continuously
        // for as long as the station and this instance last.
        return true;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public int getDelay() {
        return 0;
    }

    @Override
    public float getVolume() {
        // No radio-specific volume control (explicitly out of scope, MINECRAFT-394) -- this plays at the
        // same unscaled level as any other NEUTRAL-category sound; only vanilla's own sound sliders and
        // this sound's own distance attenuation affect it.
        return 1.0f;
    }

    @Override
    public float getPitch() {
        return 1.0f;
    }

    @Override
    public double getX() {
        return x;
    }

    @Override
    public double getY() {
        return y;
    }

    @Override
    public double getZ() {
        return z;
    }

    @Override
    public SoundInstance.Attenuation getAttenuation() {
        return SoundInstance.Attenuation.LINEAR;
    }
}
