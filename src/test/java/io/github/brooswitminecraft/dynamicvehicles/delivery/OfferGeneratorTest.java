package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeded-RNG coverage for MINECRAFT-107/120 AC7: slot filling that spills
 * across rings, level gating of eligible rings, danger's independence from
 * distance, and reward monotonicity in both distance and danger. Everything
 * under test here ({@link OfferConfig}, {@link OfferGenerator},
 * {@link RingMath}) is Minecraft-free, so it runs in a plain JUnit JVM —
 * see the class javadocs for why that split exists.
 */
class OfferGeneratorTest {

    private static OfferConfig config() {
        return new OfferConfig(
                new int[] {0, 1, 2, 3, 4, 5},
                new int[] {0, 1, 2, 3, 4, 5},
                0.0, 1.0,
                2.0, 0.01,
                1.0, 2.0,
                6000, 4.0);
    }

    @Test
    void offerConfig_rejectsMismatchedPerLevelArrayLengths() {
        assertThrows(IllegalArgumentException.class, () -> new OfferConfig(
                new int[] {0, 1, 2}, new int[] {0, 1, 2, 3, 4, 5}, 0.0, 1.0, 2.0, 0.01, 1.0, 2.0, 6000, 4.0));
    }

    @Test
    void offerConfig_rejectsDangerMaxNotGreaterThanDangerMin() {
        assertThrows(IllegalArgumentException.class, () -> new OfferConfig(
                new int[] {0, 1, 2, 3, 4, 5}, new int[] {0, 1, 2, 3, 4, 5}, 0.5, 0.5, 2.0, 0.01, 1.0, 2.0, 6000, 4.0));
    }

    @Test
    void offerConfig_rejectsRewardMultiplierDecreasingWithDanger() {
        assertThrows(IllegalArgumentException.class, () -> new OfferConfig(
                new int[] {0, 1, 2, 3, 4, 5}, new int[] {0, 1, 2, 3, 4, 5}, 0.0, 1.0, 2.0, 0.01, 3.0, 1.0, 6000, 4.0));
    }

    @Test
    void offerConfig_rejectsNegativeRewardBase() {
        assertThrows(IllegalArgumentException.class, () -> new OfferConfig(
                new int[] {0, 1, 2, 3, 4, 5}, new int[] {0, 1, 2, 3, 4, 5}, 0.0, 1.0, -0.01, 0.01, 1.0, 2.0, 6000, 4.0));
    }

    // --- AC5: villager level gates the eligible rings ---

    @Test
    void levelGating_maxRingIsNonDecreasingNoviceThroughMaster() {
        OfferConfig config = config();
        int previous = 0;
        for (int level = 1; level <= 5; level++) {
            int maxRing = config.maxRingForLevel(level);
            assertTrue(maxRing >= previous, "level " + level + " maxRing should not shrink from the previous level");
            previous = maxRing;
        }
        assertTrue(config.maxRingForLevel(5) > config.maxRingForLevel(1),
                "Master should reach substantially farther than Novice");
    }

    @Test
    void levelGating_slotsAreNonDecreasingNoviceThroughMaster() {
        OfferConfig config = config();
        int previous = 0;
        for (int level = 1; level <= 5; level++) {
            int slots = config.slotsForLevel(level);
            assertTrue(slots >= previous, "level " + level + " slots should not shrink from the previous level");
            previous = slots;
        }
    }

    @Test
    void levelGating_capsLevelsOutsideTheVanillaOneToFiveRange() {
        OfferConfig config = config();
        assertEquals(config.maxRingForLevel(1), config.maxRingForLevel(0));
        assertEquals(config.maxRingForLevel(5), config.maxRingForLevel(6));
        assertEquals(config.slotsForLevel(1), config.slotsForLevel(0));
        assertEquals(config.slotsForLevel(5), config.slotsForLevel(6));
    }

    // --- AC1: slot filling that spills across rings ---

    @Test
    void slotFilling_spillsIntoFartherRingsWhenTheNearestRingIsSparse() {
        OfferConfig config = config();
        RegionCoord origin = new RegionCoord(0, 0);
        int level = 4; // Expert: maxRing 10, slots 4
        List<RegionCoord> searchOrder = RingMath.destinationRingsUpTo(origin, config.maxRingForLevel(level), 99L);

        // Simulate a world where ring 1 yields no candidate but every farther
        // predicted placement does. The offer list must spill past the eight
        // adjacent regions rather than stop after ring 1 is empty.
        List<RegionCoord> found = RingMath.collectUpToSlots(searchOrder, config.slotsForLevel(level), region -> {
            int ring = RingMath.ringOf(origin, region);
            if (ring == 1) {
                return null;
            }
            return region;
        });

        assertEquals(config.slotsForLevel(level), found.size(), "all slots should still fill despite ring 1 confirming nothing");
        assertTrue(found.stream().noneMatch(r -> RingMath.ringOf(origin, r) == 1),
                "ring 1 contributed nothing, so every filled slot must have spilled past it");
    }

    @Test
    void slotFilling_stopsAsSoonAsSlotsAreFilledEvenIfMoreRingsRemain() {
        OfferConfig config = config();
        RegionCoord origin = new RegionCoord(0, 0);
        int level = 1; // Novice: maxRing 2, slots 1
        List<RegionCoord> searchOrder = RingMath.destinationRingsUpTo(origin, config.maxRingForLevel(level), 5L);

        int[] confirmCalls = {0};
        List<RegionCoord> found = RingMath.collectUpToSlots(searchOrder, config.slotsForLevel(level), region -> {
            confirmCalls[0]++;
            return region; // every candidate confirms
        });

        assertEquals(config.slotsForLevel(level), found.size());
        assertEquals(config.slotsForLevel(level), confirmCalls[0], "should stop confirming once the slot count is met");
    }

    // --- AC3: danger is independent of distance ---

    @Test
    void danger_sameDistanceCanProduceMateriallyDifferentDanger() {
        OfferConfig config = config();
        long seed = 777L;

        // rollDanger's signature has no distance parameter at all - it is
        // structurally impossible for it to depend on distance. These two
        // candidates are deliberately treated as having the "same" approximate
        // distance by the caller; rollDanger never sees that value.
        double dangerA = OfferGenerator.rollDanger(seed, new RegionCoord(1, 0), config);
        double dangerB = OfferGenerator.rollDanger(seed, new RegionCoord(0, 1), config);

        assertTrue(Math.abs(dangerA - dangerB) > 0.05,
                "two candidates at materially different regions should be able to roll materially different danger: " + dangerA + " vs " + dangerB);
    }

    @Test
    void danger_isDeterministicForTheSameSeedAndRegion() {
        OfferConfig config = config();
        RegionCoord region = new RegionCoord(5, -2);
        assertEquals(OfferGenerator.rollDanger(42L, region, config), OfferGenerator.rollDanger(42L, region, config));
    }

    @Test
    void danger_staysWithinTheConfiguredRange() {
        OfferConfig config = new OfferConfig(
                new int[] {0, 2, 4, 6, 10, 16}, new int[] {0, 1, 2, 3, 4, 5},
                0.25, 0.75, 2.0, 0.01, 1.0, 2.0, 6000, 4.0);
        for (int x = 0; x < 50; x++) {
            double danger = OfferGenerator.rollDanger(1234L, new RegionCoord(x, -x), config);
            assertTrue(danger >= 0.25 && danger < 0.75, "danger " + danger + " is outside the configured [0.25, 0.75) range");
        }
    }

    // --- AC4: reward scales from both distance and danger, monotonically ---

    @Test
    void reward_usesTheConfiguredBaseDistanceAndDangerFormula() {
        OfferConfig config = config();
        double[][] cases = {
                {500.0, 7.0, 10.5, 14.0},
                {1500.0, 17.0, 25.5, 34.0},
                {5000.0, 52.0, 78.0, 104.0},
                {8000.0, 82.0, 123.0, 164.0}
        };

        double previousLow = Double.NEGATIVE_INFINITY;
        double previousMid = Double.NEGATIVE_INFINITY;
        double previousHigh = Double.NEGATIVE_INFINITY;
        for (double[] rewardCase : cases) {
            double distance = rewardCase[0];
            double low = OfferGenerator.reward(distance, 0.0, config);
            double mid = OfferGenerator.reward(distance, 0.5, config);
            double high = OfferGenerator.reward(distance, 1.0, config);

            assertEquals(rewardCase[1], low, 1e-9, "low-danger reward at " + distance + " blocks");
            assertEquals(rewardCase[2], mid, 1e-9, "mid-danger reward at " + distance + " blocks");
            assertEquals(rewardCase[3], high, 1e-9, "high-danger reward at " + distance + " blocks");
            assertTrue(low > previousLow && mid > previousMid && high > previousHigh,
                    "reward must increase with distance at every danger level");
            assertTrue(low < mid && mid < high,
                    "reward must increase with danger at " + distance + " blocks");
            previousLow = low;
            previousMid = mid;
            previousHigh = high;
        }
    }

    @Test
    void reward_isMonotonicIncreasingInDistanceAtFixedDanger() {
        OfferConfig config = config();
        double danger = 0.5;
        double rewardNear = OfferGenerator.reward(100.0, danger, config);
        double rewardFar = OfferGenerator.reward(1000.0, danger, config);
        assertTrue(rewardFar > rewardNear);
    }

    @Test
    void reward_isMonotonicIncreasingInDangerAtFixedDistance() {
        OfferConfig config = config();
        double distance = 400.0;
        double rewardSafe = OfferGenerator.reward(distance, config.dangerMin(), config);
        double rewardRisky = OfferGenerator.reward(distance, config.dangerMax(), config);
        assertTrue(rewardRisky > rewardSafe);
    }

    @Test
    void reward_allowsAShortHighRiskOfferToOutpayALongLowRiskOne() {
        OfferConfig config = config();
        double shortHighRisk = OfferGenerator.reward(100.0, config.dangerMax(), config);
        double longLowRisk = OfferGenerator.reward(120.0, config.dangerMin(), config);
        assertTrue(shortHighRisk > longLowRisk,
                "a short high-risk run should be able to out-pay a slightly longer low-risk one");
    }

    @Test
    void reward_shortLowRiskStaysBelowLongHighRisk_bothCombinationsArePossible() {
        OfferConfig config = config();
        double shortLowRisk = OfferGenerator.reward(100.0, config.dangerMin(), config);
        double longHighRisk = OfferGenerator.reward(1000.0, config.dangerMax(), config);
        assertTrue(longHighRisk > shortLowRisk);
        assertNotEquals(shortLowRisk, OfferGenerator.reward(100.0, config.dangerMax(), config));
    }

    // --- AC2: every offer carries a time allowance and a reward ---

    @Test
    void timeAllowance_growsWithDistance() {
        OfferConfig config = config();
        assertTrue(OfferGenerator.timeAllowanceTicks(1000, config) > OfferGenerator.timeAllowanceTicks(10, config));
    }

    @Test
    void generateTerms_producesDangerWithinRangeAndPositiveTimeAllowanceAndReward() {
        OfferConfig config = config();
        OfferTerms terms = OfferGenerator.generateTerms(1L, new RegionCoord(3, 3), 250.0, config);
        assertTrue(terms.danger() >= config.dangerMin() && terms.danger() < config.dangerMax());
        assertTrue(terms.timeAllowanceTicks() > 0);
        assertTrue(terms.reward() > 0);
    }

    @Test
    void generateTerms_isDeterministicForTheSameInputs() {
        OfferConfig config = config();
        OfferTerms first = OfferGenerator.generateTerms(1L, new RegionCoord(3, 3), 250.0, config);
        OfferTerms second = OfferGenerator.generateTerms(1L, new RegionCoord(3, 3), 250.0, config);
        assertEquals(first, second);
    }

    @Test
    void generateTerms_differentRegionsAtTheSameDistanceCanDifferOnlyInDanger() {
        OfferConfig config = config();
        double sharedDistance = 300.0;
        OfferTerms a = OfferGenerator.generateTerms(9L, new RegionCoord(1, 0), sharedDistance, config);
        OfferTerms b = OfferGenerator.generateTerms(9L, new RegionCoord(0, -1), sharedDistance, config);

        assertEquals(a.timeAllowanceTicks(), b.timeAllowanceTicks(), "same distance must give the same time allowance");
        assertFalse(a.danger() == b.danger() && a.reward() == b.reward(),
                "different candidates at the same distance should not be forced into identical terms");
    }

    // --- MINECRAFT-131: reward payout floor (inherited item 4) ---

    @Test
    void payoutAmount_defaultFloorMatchesTheOldHardCodedLiteral() {
        // DispatcherOfferConfig.REWARD_PAYOUT_FLOOR's default is 1, matching the literal
        // ContractTickHandler used before this ticket moved it into config.
        assertEquals(1, OfferGenerator.payoutAmount(0.2, 1));
        assertEquals(1, OfferGenerator.payoutAmount(0.9, 1));
        assertEquals(5, OfferGenerator.payoutAmount(4.6, 1), "above the floor, payout still rounds the computed reward");
    }

    @Test
    void payoutAmount_flattensEveryRewardBelowTheFloorToTheSameValue() {
        assertEquals(3, OfferGenerator.payoutAmount(0.1, 3));
        assertEquals(3, OfferGenerator.payoutAmount(2.9, 3));
        assertEquals(3, OfferGenerator.payoutAmount(3.0, 3));
    }

    @Test
    void payoutAmount_neverPaysLessThanTheFloorEvenForZeroOrNegativeReward() {
        assertEquals(1, OfferGenerator.payoutAmount(0.0, 1));
        assertEquals(1, OfferGenerator.payoutAmount(-5.0, 1));
    }
}
