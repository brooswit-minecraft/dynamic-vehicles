package io.github.brooswitminecraft.dynamicvehicles;

/** Pure rules for a car breaking the block it hits. */
public final class CollisionMath {
    private CollisionMath() {}

    /**
     * Chance that a hit at {@code speed} m/s breaks a block of the given hardness (vanilla destroy speed).
     * Zero below {@code minSpeed}, for unbreakable blocks (negative hardness) and above {@code maxHardness}.
     */
    public static double breakChance(double speed, double hardness, double minSpeed, double maxHardness) {
        if (hardness < 0 || hardness > maxHardness || speed < minSpeed) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, 0.15 * (speed - minSpeed + 1.0) / (hardness + 0.5)));
    }
}
