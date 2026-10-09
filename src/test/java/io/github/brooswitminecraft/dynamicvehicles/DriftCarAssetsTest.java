package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-188: this test sourceSet has no path to Minecraft/NeoForge classes (see
 * {@code DispatcherProfessionTest}'s own javadoc for why), so {@code CarRenderer} itself can't be
 * exercised here -- a human must confirm the in-game look (see the PR description for exactly what
 * was and wasn't checked). What IS checked without any Minecraft dependency: the asset files the
 * renderer and item model now point at actually exist, are shaped sanely, and that the item model
 * really was repointed at the new custom icon rather than the borrowed {@code furnace_minecart} one.
 */
class DriftCarAssetsTest {
    private static final String RESOURCE_ROOT = "/assets/dynamicvehicles";

    private static InputStream resource(String path) {
        InputStream in = DriftCarAssetsTest.class.getResourceAsStream(RESOURCE_ROOT + path);
        if (in == null) {
            fail("missing resource " + RESOURCE_ROOT + path);
        }
        return in;
    }

    @Test
    void itemModelPointsAtTheCustomIconUnderTheModNamespace() throws IOException {
        try (InputStream in = resource("/models/item/drift_car.json")) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("dynamicvehicles:item/drift_car"),
                    "drift_car.json must reference its own namespace's custom icon");
            assertFalse(content.contains("furnace_minecart"),
                    "drift_car.json must no longer borrow furnace_minecart's icon");
        }
    }

    @Test
    void itemIconExistsAndIsShapedLikeAVanillaItemTexture() throws IOException {
        BufferedImage image;
        try (InputStream in = resource("/textures/item/drift_car.png")) {
            image = ImageIO.read(in);
        }
        assertEquals(16, image.getWidth(), "item icons are 16x16 by vanilla convention");
        assertEquals(16, image.getHeight(), "item icons are 16x16 by vanilla convention");
        assertTrue(image.getColorModel().hasAlpha(), "the icon needs transparency outside its silhouette");

        boolean sawTransparent = false;
        boolean sawOpaque = false;
        for (int y = 0; y < image.getHeight() && !(sawTransparent && sawOpaque); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha == 0) {
                    sawTransparent = true;
                } else if (alpha == 255) {
                    sawOpaque = true;
                }
            }
        }
        assertTrue(sawTransparent, "an icon with no transparent background would render as a solid square");
        assertTrue(sawOpaque, "an entirely transparent icon would render as invisible");
    }

    @Test
    void bodyLiveryTextureExistsAndIsSane() throws IOException {
        BufferedImage image;
        try (InputStream in = resource("/textures/entity/drift_car_body.png")) {
            image = ImageIO.read(in);
        }
        assertEquals(32, image.getWidth(), "CarRenderer.texturedBox maps this image onto every face as a whole, square UV");
        assertEquals(32, image.getHeight());
    }

    @Test
    void trimTextureExistsAndIsSane() throws IOException {
        BufferedImage image;
        try (InputStream in = resource("/textures/entity/drift_car_trim.png")) {
            image = ImageIO.read(in);
        }
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
    }
}
