package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

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
 * Same scope as {@code delivery.DispatcherProfessionTest}: constructing a
 * {@code VillagerProfession} requires a running NeoForge mod loader (see
 * that test's javadoc for why), so the actual profession/POI/block
 * registration cannot be exercised in a plain unit-test JVM. What IS
 * checked here, Minecraft-free: the static resources this profession needs
 * to render instead of a missing-texture villager.
 */
class PathfinderProfessionResourceTest {
    private static final String RESOURCE_ROOT = "/assets/dynamicvehicles";

    private static InputStream resource(String path) {
        InputStream in = PathfinderProfessionResourceTest.class.getResourceAsStream(RESOURCE_ROOT + path);
        if (in == null) {
            fail("missing resource " + RESOURCE_ROOT + path);
        }
        return in;
    }

    @Test
    void langFileDeclaresThePathfinderProfessionAndRouteBoardNames() throws IOException {
        try (InputStream in = resource("/lang/en_us.json")) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("\"entity.minecraft.villager.dynamicvehicles.pathfinder\""),
                    "en_us.json must declare the pathfinder profession name");
            assertTrue(content.contains("\"block.dynamicvehicles.route_board\""),
                    "en_us.json must declare the route_board block name");
        }
    }

    @Test
    void professionTextureIsPresentAndShapedLikeAVillagerOverlay() throws IOException {
        BufferedImage image;
        try (InputStream in = resource("/textures/entity/villager/profession/pathfinder.png")) {
            image = ImageIO.read(in);
        }
        assertEquals(64, image.getWidth(), "profession overlay must be 64px wide like vanilla's villager overlays");
        assertEquals(64, image.getHeight(), "profession overlay must be 64px tall like vanilla's villager overlays");
        assertTrue(image.getColorModel().hasAlpha(), "profession overlay must have an alpha channel (it's a cutout layer)");
    }

    @Test
    void routeBoardBlockAndItemModelsExistAndAgreeOnTexture() throws IOException {
        try (InputStream block = resource("/models/block/route_board.json");
             InputStream item = resource("/models/item/route_board.json")) {
            String blockModel = new String(block.readAllBytes(), StandardCharsets.UTF_8);
            String itemModel = new String(item.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(blockModel.contains("dynamicvehicles:block/route_board"), "block model must reference its own texture");
            assertTrue(itemModel.contains("dynamicvehicles:block/route_board"), "item model must parent the block model");
        }
        try (InputStream texture = resource("/textures/block/route_board.png")) {
            assertTrue(ImageIO.read(texture).getWidth() > 0, "route_board.png must be a valid image");
        }
    }
}
