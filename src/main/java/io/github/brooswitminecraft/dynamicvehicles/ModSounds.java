package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The car's sound events; the files they play are listed in assets/dynamicvehicles/sounds.json. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, DynamicVehiclesMod.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_IDLE = register("car.engine.idle");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_MID = register("car.engine.mid");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_HIGH = register("car.engine.high");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_START = register("car.engine.start");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_STOP = register("car.engine.stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> IMPACT_LIGHT = register("car.impact.light");
    public static final DeferredHolder<SoundEvent, SoundEvent> IMPACT_MEDIUM = register("car.impact.medium");
    public static final DeferredHolder<SoundEvent, SoundEvent> IMPACT_HARD = register("car.impact.hard");
    public static final DeferredHolder<SoundEvent, SoundEvent> IMPACT_SEVERE = register("car.impact.severe");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIRE_ROUGH = register("car.tire.rough");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIRE_SMOOTH = register("car.tire.smooth");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIRE_SNOW = register("car.tire.snow");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIRE_SKID_LIGHT = register("car.tire.skid_light");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIRE_SKID_HARD = register("car.tire.skid_hard");

    public static final DeferredHolder<SoundEvent, SoundEvent> HORN = register("car.horn");

    private ModSounds() {}

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, name)));
    }

    /** The impact sound for a severity from {@link CarSoundMath#impactSeverity}, or null for none. */
    public static SoundEvent impact(int severity) {
        return switch (severity) {
            case 1 -> IMPACT_LIGHT.get();
            case 2 -> IMPACT_MEDIUM.get();
            case 3 -> IMPACT_HARD.get();
            case 4 -> IMPACT_SEVERE.get();
            default -> null;
        };
    }
}
