package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision logic for client-side skid decals (MINECRAFT-225): when a wheel is sliding hard enough to
 * lay a mark, how far apart marks on the same wheel should be spaced, how a mark fades out, and how many
 * marks are allowed to exist at once. No Minecraft types, so it can be unit tested like
 * {@link CarSoundMath}/{@link CarEffectsMath}.
 */
public final class SkidMarkMath {
    /** {@link CarEntity#clientSlip()} (0..1, the same signal {@link CarSoundMath#hardSkid} reads) must reach
     * this before a wheel starts laying marks; normal rolling must never carpet the ground. Below
     * {@link CarSoundMath#hardSkid}'s 0.5 so marks appear a little before the hard-skid sound kicks in. */
    public static final double SKID_THRESHOLD = 0.35;

    /** Minimum distance (metres) a wheel must travel between marks, so a slow drift lays a readable trail
     * of distinct marks instead of one solid smear. */
    public static final double MIN_SPACING = 0.35;

    /** How long a mark stays visible at all, in milliseconds. */
    public static final long LIFETIME_MS = 8_000L;

    /** Base opacity (0..1) a freshly-laid mark renders at, before the fade curve scales it down. */
    public static final double BASE_ALPHA = 0.55;

    /** How many marks one wheel's own ring buffer holds before it starts overwriting its oldest. */
    public static final int MAX_PER_WHEEL = 24;

    /** How many marks may be visible across every car on the client at once, regardless of wheel count. */
    public static final int MAX_TOTAL = 400;

    /** Metres a mark sits above the ground height it was laid at, to avoid z-fighting with terrain. */
    public static final double SURFACE_OFFSET = 0.015;

    private SkidMarkMath() {}

    /** Whether this slip level is hard enough for the wheel it belongs to to be laying marks right now. */
    public static boolean isSkidding(double slipLevel) {
        return slipLevel >= SKID_THRESHOLD;
    }

    /** Whether a wheel that has travelled this far (metres) since its last mark should lay another. */
    public static boolean shouldSpawn(double distanceSinceLastMark) {
        return distanceSinceLastMark >= MIN_SPACING;
    }

    /**
     * A mark's opacity (0..1) at this age: {@link #BASE_ALPHA} for a brand new mark, ramping linearly down
     * to 0 by {@link #LIFETIME_MS}. Never negative and never above {@code BASE_ALPHA}, however old or
     * "from the future" (negative age) the input is.
     */
    public static double alpha(long ageMs, long lifetimeMs) {
        if (lifetimeMs <= 0) {
            return 0.0;
        }
        double remaining = 1.0 - (double) ageMs / (double) lifetimeMs;
        return BASE_ALPHA * Math.max(0.0, Math.min(1.0, remaining));
    }

    /** Whether a mark this old should have been removed already. */
    public static boolean isExpired(long ageMs, long lifetimeMs) {
        return ageMs >= lifetimeMs;
    }

    /**
     * Enforces the client-wide cap across every wheel of every car: keeps only the {@code max} most
     * recently spawned marks (by {@link SkidMark#spawnTimeMs()}), dropping the rest. A list already within
     * the cap is returned unchanged (by reference), so callers that are already under the limit pay no
     * extra allocation.
     */
    public static List<SkidMark> capToTotal(List<SkidMark> marks, int max) {
        if (marks.size() <= max) {
            return marks;
        }
        List<SkidMark> sorted = new ArrayList<>(marks);
        sorted.sort((a, b) -> Long.compare(b.spawnTimeMs(), a.spawnTimeMs()));
        return new ArrayList<>(sorted.subList(0, max));
    }
}
