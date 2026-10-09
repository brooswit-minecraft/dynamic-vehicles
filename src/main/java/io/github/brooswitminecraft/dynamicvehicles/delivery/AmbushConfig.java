package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * Plain, Minecraft-free tuning values for the periodic pillager ambush roll
 * (MINECRAFT-130, epic MINECRAFT-76 AC1-5). Mirrors {@link OfferConfig}'s
 * pattern: every tunable lives here as a primitive so {@link AmbushRoll},
 * {@link AmbushSchedule} and {@link SpawnOffsetPicker} stay directly
 * unit-testable with a seeded RNG. The Minecraft-facing config that owns the
 * actual tunable defaults is {@code DispatcherOfferConfig} in the mod's root
 * package (matching its existing {@code ModConfigSpec} convention); it
 * builds one of these at roll time. {@code dangerMin}/{@code dangerMax} are
 * NOT redefined here - they are the same bounds {@code DispatcherOfferConfig}
 * already declares for danger generally (AC3's note on danger's shape).
 *
 * @param rollIntervalTicks     how often (in game ticks) a contract gets one roll attempt; AC1
 * @param minRollChance         roll success probability at danger == dangerMin
 * @param maxRollChance         roll success probability at danger == dangerMax; must be &gt;= minRollChance
 * @param minEncounterSize      pillager count on a successful roll at danger == dangerMin
 * @param maxEncounterSize      pillager count on a successful roll at danger == dangerMax; must be &gt;= minEncounterSize
 * @param spawnMinDistance      nearest a spawn offset may land from the traveling entity, in blocks
 * @param spawnMaxDistance      farthest a spawn offset may land; must be &gt; spawnMinDistance
 * @param spawnArcRadians       total angular spread (radians) centered on heading that a spawn offset may land within
 * @param maxEncounterLifetimeTicks hard backstop: an encounter mob despawns after this many ticks regardless of other state (AC4)
 * @param nearbyPlayerRadius    a non-owner player within this many blocks of an encounter mob blocks its despawn (AC5)
 * @param dangerMin             minimum rolled danger value (inclusive); same bound {@code DispatcherOfferConfig} uses elsewhere
 * @param dangerMax             maximum rolled danger value (exclusive); must be &gt; dangerMin
 */
public record AmbushConfig(
        long rollIntervalTicks,
        double minRollChance,
        double maxRollChance,
        int minEncounterSize,
        int maxEncounterSize,
        double spawnMinDistance,
        double spawnMaxDistance,
        double spawnArcRadians,
        long maxEncounterLifetimeTicks,
        double nearbyPlayerRadius,
        double dangerMin,
        double dangerMax
) {
    public AmbushConfig {
        if (rollIntervalTicks <= 0) {
            throw new IllegalArgumentException("rollIntervalTicks must be > 0: " + rollIntervalTicks);
        }
        if (minRollChance < 0.0 || maxRollChance > 1.0 || maxRollChance < minRollChance) {
            throw new IllegalArgumentException(
                    "roll chance must satisfy 0 <= minRollChance <= maxRollChance <= 1: " + minRollChance + ".." + maxRollChance);
        }
        if (minEncounterSize < 1 || maxEncounterSize < minEncounterSize) {
            throw new IllegalArgumentException(
                    "encounter size must satisfy 1 <= minEncounterSize <= maxEncounterSize: " + minEncounterSize + ".." + maxEncounterSize);
        }
        if (spawnMinDistance < 0.0 || spawnMaxDistance <= spawnMinDistance) {
            throw new IllegalArgumentException(
                    "spawnMaxDistance must be > spawnMinDistance >= 0: " + spawnMinDistance + ".." + spawnMaxDistance);
        }
        if (spawnArcRadians <= 0.0 || spawnArcRadians > 2 * Math.PI) {
            throw new IllegalArgumentException("spawnArcRadians must be in (0, 2*PI]: " + spawnArcRadians);
        }
        if (maxEncounterLifetimeTicks <= 0) {
            throw new IllegalArgumentException("maxEncounterLifetimeTicks must be > 0: " + maxEncounterLifetimeTicks);
        }
        if (nearbyPlayerRadius < 0.0) {
            throw new IllegalArgumentException("nearbyPlayerRadius must be >= 0: " + nearbyPlayerRadius);
        }
        if (dangerMax <= dangerMin) {
            throw new IllegalArgumentException("dangerMax must be > dangerMin: " + dangerMax + " <= " + dangerMin);
        }
    }
}
