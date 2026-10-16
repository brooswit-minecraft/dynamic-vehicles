package io.github.brooswitminecraft.dynamicvehicles;

import io.github.brooswitminecraft.dynamicterrain.TexelField;

/**
 * Pure refinement of the block-level {@code SurfaceProperties} grip and
 * rolling resistance from per-texel contact-patch evidence (Dynamic Terrain's
 * {@code Surfaces.contactPatch}): a small, bounded correction on top of the
 * block-level values, not a replacement for them. Free of Minecraft types so
 * it can be unit tested.
 */
public final class TextureGripMath {
    /** Grip multiplier at the roughest evidence this scale recognises (1.0 = no effect). */
    private static final double MAX_GRIP_REDUCTION = 0.15;
    /** Rolling-resistance multiplier at the roughest evidence this scale recognises (1.0 = no effect). */
    private static final double MAX_ROLLING_BONUS = 0.3;

    private TextureGripMath() {}

    /** Grip multiplier in [1 - MAX_GRIP_REDUCTION, 1.0]: busier texture detail costs a little peak grip. */
    public static double gripMultiplier(TexelField.TexelSample evidence) {
        return 1.0 - MAX_GRIP_REDUCTION * roughnessSignal(evidence);
    }

    /** Rolling-resistance multiplier in [1.0, 1 + MAX_ROLLING_BONUS]: busier texture detail rolls harder. */
    public static double rollingResistanceMultiplier(TexelField.TexelSample evidence) {
        return 1.0 + MAX_ROLLING_BONUS * roughnessSignal(evidence);
    }

    /**
     * 0..1: how busy the evidence is. Luminance contrast and X/Y local variation all speak to surface
     * irregularity; chroma (plain material colour, not texture detail) deliberately does not contribute.
     */
    private static double roughnessSignal(TexelField.TexelSample evidence) {
        double signal = (evidence.luminanceContrast() + evidence.xVariation() + evidence.yVariation()) / 3.0;
        return Math.max(0.0, Math.min(1.0, signal));
    }
}
