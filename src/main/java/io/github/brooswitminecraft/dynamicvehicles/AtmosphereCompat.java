package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * The only door to Dynamic Atmosphere, an optional dependency: nothing else may mention its types. Emission goes
 * through {@code DynamicAtmosphereMod.emitMaterial}, which is loaded-chunk only and never forces chunks to load.
 */
final class AtmosphereCompat {
    private static Boolean present;

    private AtmosphereCompat() {}

    static boolean usable() {
        if (present == null) {
            present = ModList.get().isLoaded("dynamicatmosphere");
        }
        return present;
    }

    static void exhaust(ServerLevel level, BlockPos pos, int amount) {
        var material = exhaustMaterial();
        Emitter.emit(level, material, pos, amount);
        EmissionObserver.record(material, pos, amount);
    }

    static void dust(ServerLevel level, BlockPos pos, int amount) {
        Emitter.emit(level, io.github.brooswitminecraft.dynamicatmosphere.AtmosphereMaterial.DUST, pos, amount);
    }

    /** Holds the Dynamic Atmosphere references so they load only after {@link #usable()}. */
    private static final class Emitter {
        static void emit(ServerLevel level, io.github.brooswitminecraft.dynamicatmosphere.AtmosphereMaterial material, BlockPos pos, int amount) {
            io.github.brooswitminecraft.dynamicatmosphere.DynamicAtmosphereMod.emitMaterial(level, material, pos, amount);
        }
    }

    /** The real Dynamic Atmosphere exhaust singleton, never a re-created look-alike. */
    static io.github.brooswitminecraft.dynamicatmosphere.AtmosphereMaterial exhaustMaterial() {
        return io.github.brooswitminecraft.dynamicatmosphere.AtmosphereMaterial.EXHAUST;
    }

    /** GameTests observe the material passed through the real compatibility emission path. */
    static final class EmissionObserver {
        private static java.util.function.Consumer<Emission> observer;

        static void install(java.util.function.Consumer<Emission> next) {
            observer = next;
        }

        static void clear() {
            observer = null;
        }

        private static void record(Object material, BlockPos pos, int amount) {
            if (observer != null) {
                observer.accept(new Emission(material, pos, amount));
            }
        }
    }

    record Emission(Object material, BlockPos pos, int amount) {}
}
