package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * The current server game-time tick, injected so {@link ContractBook}'s
 * deadline-expiry transition is a pure function of its input rather than a
 * call to a running server (MINECRAFT-110 AC9). The thin Minecraft layer
 * supplies {@code level::getGameTime}; a unit test supplies a literal.
 */
@FunctionalInterface
public interface TickClock {
    long currentTick();
}
