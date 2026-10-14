package io.github.brooswitminecraft.dynamicvehicles;

/** Pure rules for vehicle health: thresholds, crash damage, burn damage and flipped-body damage. */
public final class CarHealth {
    public static final float MAX = 100.0f;
    /** Below this fraction of max health the car smokes. */
    public static final double SMOKE_FRACTION = 0.50;
    /** Below this fraction the car is on fire and burns itself (and its riders). */
    public static final double FIRE_FRACTION = 0.33;
    /** At or below this fraction the car is wrecked: nobody may enter, and everyone is thrown out. */
    public static final double WRECK_FRACTION = 0.10;
    /** A speed loss (m/s) in one tick below this is just braking, not a crash. */
    static final double CRASH_MIN_SPEED_LOSS = 3.0;
    static final double CRASH_DAMAGE_PER_MS = 3.0;
    /** Health lost per second while burning. */
    static final float BURN_DAMAGE_PER_SECOND = 2.0f;
    /** Health lost per second while resting or sliding on the roof or side. */
    static final float FLIPPED_DAMAGE_PER_SECOND = 4.0f;
    /** Body up-vector y below this means the car is on its side or roof (more than 60 degrees over). */
    static final double FLIPPED_UP_Y = 0.5;

    private CarHealth() {}

    public static boolean smoking(float health) {
        return health < MAX * SMOKE_FRACTION;
    }

    public static boolean burning(float health) {
        return health > 0.0f && health < MAX * FIRE_FRACTION;
    }

    public static boolean wrecked(float health) {
        return health <= MAX * WRECK_FRACTION;
    }

    /** Damage from a crash that shed {@code speedLoss} m/s in a single tick. */
    public static float crashDamage(double speedLoss) {
        if (speedLoss < CRASH_MIN_SPEED_LOSS) {
            return 0.0f;
        }
        return (float) (speedLoss * CRASH_DAMAGE_PER_MS);
    }

    /** Whether a body whose local up axis points {@code upY} in world space is on its side or roof. */
    public static boolean flipped(double upY) {
        return upY < FLIPPED_UP_Y;
    }

    public static float burnDamage(double seconds) {
        return (float) (BURN_DAMAGE_PER_SECOND * seconds);
    }

    public static float flippedDamage(double seconds) {
        return (float) (FLIPPED_DAMAGE_PER_SECOND * seconds);
    }
}
