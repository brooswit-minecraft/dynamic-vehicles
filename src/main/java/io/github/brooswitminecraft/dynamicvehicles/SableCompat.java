package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * The only door to Sable. Sable is an optional dependency, so nothing outside
 * this class may mention a Sable type; the body is passed around as Object and
 * the real work lives in {@link SableCarBody}, which is only loaded after
 * {@link #usable()} says Sable is present and enabled.
 */
final class SableCompat {
    private SableCompat() {}

    static boolean usable() {
        return CarConfig.USE_SABLE_PHYSICS.get() && ModList.get().isLoaded("sable");
    }

    static Object create(ServerLevel level, CarEntity car) {
        return SableCarBody.create(level, car);
    }

    static void tick(Object body, CarEntity car, double throttle, double steer, boolean handbrake, double dt) {
        ((SableCarBody) body).tick(car, throttle, steer, handbrake, dt);
    }

    static void syncEntity(Object body, CarEntity car) {
        ((SableCarBody) body).syncEntity(car);
    }

    static org.joml.Quaternionf orientation(Object body) {
        return ((SableCarBody) body).orientationF();
    }

    static String describe(Object body) {
        return ((SableCarBody) body).describe();
    }

    static void remove(Object body) {
        ((SableCarBody) body).remove();
    }
}
