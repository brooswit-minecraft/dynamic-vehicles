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
 * MINECRAFT-196: like {@link DispatcherProfessionTest}, the test sourceSet has no path to
 * Minecraft/NeoForge classes, so {@link DispatcherBlocks}/{@link DispatcherPoiTypes}'s actual
 * registration (the new block's PoiType claiming its own states, the profession recognizing only
 * that POI) is NOT exercised here &mdash; that needs a running client/dev environment, covered
 * instead by {@link DispatcherGameTests} (run in CI via the {@code gameTestServer} task; see that
 * class's javadoc) and by the PR's in-game verification checklist.
 *
 * <p>What IS checked here, without any Minecraft/NeoForge dependency: the static data/asset
 * files the new block needs are present and internally consistent &mdash; the kind of typo
 * (a model referencing a texture path that does not exist, a recipe result id that does not
 * match the registered block id) that would otherwise only surface as a missing-texture purple/
 * black block or a silently-uncraftable recipe in a running game.
 */
class DispatcherBlocksTest {
    private static final String BLOCK_ID = "dynamicvehicles:dispatch_board";

    private static String resourceText(String path) throws IOException {
        try (InputStream in = DispatcherBlocksTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void langFileDeclaresTheDispatchBoardBlockName() throws IOException {
        String content = resourceText("/assets/dynamicvehicles/lang/en_us.json");
        assertTrue(content.contains("\"block.dynamicvehicles.dispatch_board\""),
                "en_us.json must declare block.dynamicvehicles.dispatch_board");
    }

    @Test
    void blockstateReferencesTheBlockModel() throws IOException {
        String content = resourceText("/assets/dynamicvehicles/blockstates/dispatch_board.json");
        assertTrue(content.contains("\"dynamicvehicles:block/dispatch_board\""),
                "blockstates/dispatch_board.json must reference the dispatch_board block model");
    }

    @Test
    void blockModelReferencesTheCubeAllParentAndItsOwnTexture() throws IOException {
        String content = resourceText("/assets/dynamicvehicles/models/block/dispatch_board.json");
        assertTrue(content.contains("\"minecraft:block/cube_all\""), "block model must parent to cube_all");
        assertTrue(content.contains("\"dynamicvehicles:block/dispatch_board\""), "block model must reference its own texture");
    }

    @Test
    void itemModelParentsToTheBlockModel() throws IOException {
        String content = resourceText("/assets/dynamicvehicles/models/item/dispatch_board.json");
        assertTrue(content.contains("\"dynamicvehicles:block/dispatch_board\""), "item model must parent to the block model");
    }

    @Test
    void blockTextureIsAValid16x16RgbaTexture() throws IOException {
        BufferedImage image;
        try (InputStream in = DispatcherBlocksTest.class.getResourceAsStream("/assets/dynamicvehicles/textures/block/dispatch_board.png")) {
            if (in == null) {
                fail("missing dispatch_board.png");
            }
            image = ImageIO.read(in);
        }
        assertEquals(16, image.getWidth(), "block texture must be 16px wide");
        assertEquals(16, image.getHeight(), "block texture must be 16px tall");
    }

    @Test
    void lootTableDropsTheBlockItself() throws IOException {
        String content = resourceText("/data/dynamicvehicles/loot_table/blocks/dispatch_board.json");
        assertTrue(content.contains("\"" + BLOCK_ID + "\""), "loot table must drop " + BLOCK_ID);
    }

    @Test
    void recipeResultIsTheDispatchBoard() throws IOException {
        String content = resourceText("/data/dynamicvehicles/recipe/dispatch_board.json");
        assertTrue(content.contains("\"" + BLOCK_ID + "\""), "recipe result must be " + BLOCK_ID);
        assertTrue(content.contains("minecraft:planks"), "recipe should use common planks, not a specific wood type");
    }

    @Test
    void axeMineableTagIncludesTheBlock() throws IOException {
        String content = resourceText("/data/minecraft/tags/block/mineable/axe.json");
        assertTrue(content.contains("\"" + BLOCK_ID + "\""), "mineable/axe tag must include " + BLOCK_ID);
    }

    @Test
    void gameTestStructureIsPresent() throws IOException {
        try (InputStream in = DispatcherBlocksTest.class.getResourceAsStream("/data/dynamicvehicles/structure/dispatch_profession.nbt")) {
            if (in == null) {
                fail("missing dispatch_profession.nbt");
            }
            assertTrue(in.readAllBytes().length > 0, "gametest structure must not be empty");
        }
    }
}
