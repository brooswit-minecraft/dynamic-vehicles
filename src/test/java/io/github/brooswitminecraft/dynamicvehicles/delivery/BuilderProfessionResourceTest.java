package io.github.brooswitminecraft.dynamicvehicles.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class BuilderProfessionResourceTest {
    @Test
    void builderProfessionHasALocalizedName() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/assets/dynamicvehicles/lang/en_us.json")) {
            assertTrue(in != null);
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("\"entity.dynamicvehicles.villager.builder\""));
        }
    }

    @Test
    void builderProfessionHasARenderableVillagerOverlay() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/assets/dynamicvehicles/textures/entity/villager/profession/builder.png")) {
            assertTrue(in != null, "Builder profession needs its overlay texture");
            BufferedImage image = ImageIO.read(in);
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
            assertTrue(image.getColorModel().hasAlpha());
        }
    }
}
