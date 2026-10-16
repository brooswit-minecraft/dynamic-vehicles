package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicterrain.TexelField;

class TextureGripMathTest {
    private static TexelField.TexelSample evidence(float chroma, float contrast, float xVar, float yVar) {
        return new TexelField.TexelSample(chroma, contrast, xVar, yVar);
    }

    @Test
    void flatEvidenceHasNoEffect() {
        TexelField.TexelSample flat = evidence(0.5f, 0, 0, 0);
        assertEquals(1.0, TextureGripMath.gripMultiplier(flat), 1e-9);
        assertEquals(1.0, TextureGripMath.rollingResistanceMultiplier(flat), 1e-9);
    }

    @Test
    void busiestEvidenceGivesTheBoundedExtremes() {
        TexelField.TexelSample busiest = evidence(0, 1, 1, 1);
        assertEquals(0.85, TextureGripMath.gripMultiplier(busiest), 1e-9);
        assertEquals(1.3, TextureGripMath.rollingResistanceMultiplier(busiest), 1e-9);
    }

    @Test
    void chromaAloneHasNoEffect() {
        TexelField.TexelSample vividButSmooth = evidence(1, 0, 0, 0);
        assertEquals(1.0, TextureGripMath.gripMultiplier(vividButSmooth), 1e-9);
        assertEquals(1.0, TextureGripMath.rollingResistanceMultiplier(vividButSmooth), 1e-9);
    }

    @Test
    void busierEvidenceLowersGripAndRaisesRollingResistance() {
        TexelField.TexelSample calm = evidence(0, 0.1f, 0.1f, 0.1f);
        TexelField.TexelSample busy = evidence(0, 0.8f, 0.8f, 0.8f);
        assertTrue(TextureGripMath.gripMultiplier(busy) < TextureGripMath.gripMultiplier(calm));
        assertTrue(TextureGripMath.rollingResistanceMultiplier(busy) > TextureGripMath.rollingResistanceMultiplier(calm));
    }

    @Test
    void staysWithinItsDeclaredBounds() {
        for (float v : new float[] {0f, 0.25f, 0.5f, 0.75f, 1f}) {
            TexelField.TexelSample sample = evidence(v, v, v, v);
            double grip = TextureGripMath.gripMultiplier(sample);
            double rolling = TextureGripMath.rollingResistanceMultiplier(sample);
            assertTrue(grip >= 0.85 && grip <= 1.0, "grip out of bounds: " + grip);
            assertTrue(rolling >= 1.0 && rolling <= 1.3, "rolling out of bounds: " + rolling);
        }
    }
}
