package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * Maps an {@link OfferTerms#danger()} value to a readable gauge for
 * MINECRAFT-109 AC3: a category label and a green-to-red "temperature"
 * colour. Danger is NOT assumed to be on a fixed 0..1 span — the caller
 * always supplies the config's own {@code dangerMin}/{@code dangerMax}
 * bounds (AC3's note on danger's shape). Plain and Minecraft-free so it is
 * directly unit-testable, including the boundary values AC8 calls for.
 */
public final class DangerGauge {

    public enum Category {
        SAFE, LOW, MODERATE, HIGH, EXTREME
    }

    /**
     * The {@code en_us.json} translation key for a danger category (MINECRAFT-110:
     * {@link Category} previously rendered as its raw enum name, untranslated).
     * Returns a plain String, not a {@code Component}, so this stays Minecraft-free;
     * callers (the offer screen, the contract HUD) wrap it in {@code Component.translatable}.
     */
    public static String translationKey(Category category) {
        return "gauge.dynamicvehicles.danger." + category.name().toLowerCase(java.util.Locale.ROOT);
    }

    private DangerGauge() {
    }

    /** @return danger normalized to 0.0 (dangerMin) .. 1.0 (dangerMax), clamped */
    public static double normalize(double danger, double dangerMin, double dangerMax) {
        if (dangerMax <= dangerMin) {
            throw new IllegalArgumentException("dangerMax must be > dangerMin: " + dangerMax + " <= " + dangerMin);
        }
        double t = (danger - dangerMin) / (dangerMax - dangerMin);
        return Math.max(0.0, Math.min(1.0, t));
    }

    public static Category categoryFor(double danger, double dangerMin, double dangerMax) {
        double t = normalize(danger, dangerMin, dangerMax);
        if (t >= 0.8) {
            return Category.EXTREME;
        } else if (t >= 0.6) {
            return Category.HIGH;
        } else if (t >= 0.4) {
            return Category.MODERATE;
        } else if (t >= 0.2) {
            return Category.LOW;
        } else {
            return Category.SAFE;
        }
    }

    /** @return an opaque ARGB colour, green (safe) through yellow to red (extreme) */
    public static int colorArgb(double danger, double dangerMin, double dangerMax) {
        double t = normalize(danger, dangerMin, dangerMax);
        int r;
        int g;
        if (t < 0.5) {
            double u = t / 0.5;
            r = (int) Math.round(u * 220);
            g = 200;
        } else {
            double u = (t - 0.5) / 0.5;
            r = 220;
            g = (int) Math.round(200 * (1 - u));
        }
        return (0xFF << 24) | (r << 16) | (g << 8);
    }
}
