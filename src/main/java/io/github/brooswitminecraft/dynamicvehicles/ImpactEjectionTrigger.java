package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure trigger decision for impact-triggered mob ejection (MINECRAFT-249 review round 2): a real crash
 * (hitting a wall, dropping off a ledge) shows up in {@code CarEntity.checkImpact} as a sudden speed
 * LOSS, not a speed spike -- {@link CrashEjectionLatch}'s own threshold (1.2 blocks/tick, above the
 * bus's 1.0 top speed) can never fire from a crash alone, since the crash itself can only ever slow the
 * vehicle down. This mirrors {@code checkImpact}'s own existing impact-sound trigger exactly ({@code
 * severity > 0 && impactCooldown == 0}), so a mob is ejected on exactly the tick the player hears/sees the
 * impact, never a different one, and reuses that same cooldown as the re-board latch so an ejected mob
 * cannot immediately re-board while the cooldown is still running.
 */
public final class ImpactEjectionTrigger {

    private ImpactEjectionTrigger() {}

    /**
     * Whether this tick's impact (as {@code CarSoundMath.impactSeverity} already classified it) should
     * eject every mob passenger: a real impact ({@code severity > 0}) not still inside the previous
     * impact's own cooldown.
     */
    public static boolean shouldEject(int severity, int impactCooldownTicksRemaining) {
        return severity > 0 && impactCooldownTicksRemaining == 0;
    }
}
