package io.github.brooswitminecraft.dynamicvehicles.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-105/117 AC7 asks for whatever can be unit-tested without a client. This
 * repo's test sourceSet has no path to vanilla/NeoForge classes (confirmed by CI: even
 * {@code DispatcherProfession.create()} fails outside a running NeoForge mod loader,
 * because constructing a {@code VillagerProfession} loads that class, which eagerly
 * registers ARMORER/BUTCHER/etc. into {@code BuiltInRegistries}, which in turn runs
 * {@code PoiTypes.bootstrap} and NeoForge's {@code GameData} registry hooks — none of
 * which are initialized outside FML's own lifecycle). So the profession-registration
 * code itself (job-site predicates, POI association, actual registry entry) genuinely
 * needs a running client/dev environment to exercise and is NOT covered here; see the
 * ticket comment for exactly what a human must do to confirm it.
 *
 * <p>What IS checked here, without any Minecraft/NeoForge dependency: the two static
 * resources this profession needs to render instead of showing a missing-texture
 * villager (AC2) are present and shaped the way
 * {@code VillagerProfessionLayer#getResourceLocation} and vanilla's own profession
 * overlays require.
 */
class DispatcherProfessionTest {
    private static final String RESOURCE_ROOT = "/assets/dynamicvehicles";

    private static InputStream resource(String path) {
        InputStream in = DispatcherProfessionTest.class.getResourceAsStream(RESOURCE_ROOT + path);
        if (in == null) {
            fail("missing resource " + RESOURCE_ROOT + path);
        }
        return in;
    }

    @Test
    void langFileDeclaresTheDispatcherProfessionName() throws IOException {
        // Vanilla's own convention, confirmed against this build's shipped
        // en_us.json: "entity.<namespace>.villager.<profession path>".
        try (InputStream in = resource("/lang/en_us.json")) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("\"entity.dynamicvehicles.villager.dispatcher\""),
                    "en_us.json must declare entity.dynamicvehicles.villager.dispatcher");
        }
    }

    @Test
    void professionTextureIsPresentAndShapedLikeAVillagerOverlay() throws IOException {
        // VillagerProfessionLayer renders this exact path
        // (textures/entity/villager/profession/<profession path>.png) as a 64x64
        // RGBA cutout overlay on top of the villager type texture; vanilla's own
        // profession overlays (e.g. armorer.png) are 64x64 RGBA.
        BufferedImage image;
        try (InputStream in = resource("/textures/entity/villager/profession/dispatcher.png")) {
            image = ImageIO.read(in);
        }
        assertEquals(64, image.getWidth(), "profession overlay must be 64px wide like vanilla's villager overlays");
        assertEquals(64, image.getHeight(), "profession overlay must be 64px tall like vanilla's villager overlays");
        assertTrue(image.getColorModel().hasAlpha(), "profession overlay must have an alpha channel (it's a cutout layer)");

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
        assertTrue(sawTransparent, "a cutout overlay covering the whole 64x64 villager skin would not render correctly");
        assertTrue(sawOpaque, "an entirely transparent overlay would render as no clothing at all");
    }
}
