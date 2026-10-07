package io.github.brooswitminecraft.dynamicvehicles;

/** When the auto headlight mode lights up. Pure, so it is unit-tested. */
public final class HeadlightMath {
    /** Day time (ticks of 24000) when it gets dark and when it is light again. */
    public static final long DUSK = 12600L;
    public static final long DAWN = 23200L;

    private HeadlightMath() {}

    public static boolean isDark(long dayTime, float rainLevel) {
        long t = Math.floorMod(dayTime, 24000L);
        return (t >= DUSK && t <= DAWN) || rainLevel > 0.5f;
    }
}
