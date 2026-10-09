package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.random.RandomGenerator;

/**
 * Pure, Minecraft-free roll-probability and encounter-size math for the
 * periodic pillager ambush (MINECRAFT-130 AC3, epic AC3/AC7a-b). Both
 * quantities derive from the contract's own danger value, linearly
 * interpolated between the config's min/max via {@link DangerGauge#normalize}
 * so danger is read off the contract rather than re-derived, matching
 * {@link OfferGenerator}'s pattern of a seeded-RNG-testable plain class.
 */
public final class AmbushRoll {

    private AmbushRoll() {
    }

    /** @return this danger's roll success probability, linearly interpolated between config's min/max roll chance */
    public static double chanceFor(double danger, AmbushConfig config) {
        double t = DangerGauge.normalize(danger, config.dangerMin(), config.dangerMax());
        return config.minRollChance() + t * (config.maxRollChance() - config.minRollChance());
    }

    /** @return this danger's encounter size on a successful roll, linearly interpolated and rounded between config's min/max size */
    public static int sizeFor(double danger, AmbushConfig config) {
        double t = DangerGauge.normalize(danger, config.dangerMin(), config.dangerMax());
        double size = config.minEncounterSize() + t * (config.maxEncounterSize() - config.minEncounterSize());
        return (int) Math.round(size);
    }

    /** @return whether this roll attempt succeeds, drawing exactly one double from {@code random} */
    public static boolean attempt(double danger, AmbushConfig config, RandomGenerator random) {
        return random.nextDouble() < chanceFor(danger, config);
    }
}
