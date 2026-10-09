package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-130 AC3/AC7a-b: roll probability and encounter size both derive
 * from the contract's danger value alone, under a seeded RNG.
 */
class AmbushRollTest {

    private static AmbushConfig config() {
        return new AmbushConfig(600, 0.05, 0.6, 1, 4, 10.0, 20.0, Math.PI / 2, 6000, 24.0, 0.0, 1.0);
    }

    @Test
    void chanceFor_atDangerMin_equalsMinRollChance() {
        assertEquals(0.05, AmbushRoll.chanceFor(0.0, config()), 1e-9);
    }

    @Test
    void chanceFor_atDangerMax_equalsMaxRollChance() {
        assertEquals(0.6, AmbushRoll.chanceFor(1.0, config()), 1e-9);
    }

    @Test
    void chanceFor_increasesMonotonicallyWithDanger() {
        AmbushConfig config = config();
        double low = AmbushRoll.chanceFor(0.2, config);
        double mid = AmbushRoll.chanceFor(0.5, config);
        double high = AmbushRoll.chanceFor(0.8, config);
        assertTrue(low < mid, "chance must increase from low to mid danger");
        assertTrue(mid < high, "chance must increase from mid to high danger");
    }

    @Test
    void sizeFor_atDangerMin_equalsMinEncounterSize() {
        assertEquals(1, AmbushRoll.sizeFor(0.0, config()));
    }

    @Test
    void sizeFor_atDangerMax_equalsMaxEncounterSize() {
        assertEquals(4, AmbushRoll.sizeFor(1.0, config()));
    }

    @Test
    void sizeFor_scalesUpWithDanger() {
        AmbushConfig config = config();
        assertTrue(AmbushRoll.sizeFor(0.1, config) <= AmbushRoll.sizeFor(0.9, config));
    }

    @Test
    void attempt_lowDangerRollsRarely_highDangerRollsOften_overManySeededDraws() {
        AmbushConfig config = config();
        int trials = 20_000;

        int lowSuccesses = 0;
        Random lowRandom = new Random(42);
        for (int i = 0; i < trials; i++) {
            if (AmbushRoll.attempt(0.0, config, lowRandom)) {
                lowSuccesses++;
            }
        }

        int highSuccesses = 0;
        Random highRandom = new Random(42);
        for (int i = 0; i < trials; i++) {
            if (AmbushRoll.attempt(1.0, config, highRandom)) {
                highSuccesses++;
            }
        }

        double lowRate = (double) lowSuccesses / trials;
        double highRate = (double) highSuccesses / trials;

        assertEquals(0.05, lowRate, 0.02, "low danger (dangerMin) must roll close to minRollChance over many draws");
        assertEquals(0.6, highRate, 0.02, "high danger (dangerMax) must roll close to maxRollChance over many draws");
        assertTrue(lowRate < highRate, "high danger must roll materially more often than low danger");
    }
}
