package io.github.brooswitminecraft.dynamicvehicles.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;

/**
 * Covers MINECRAFT-105/117 AC7: whatever can be unit-tested without a client. This
 * relies on ModDevGradle's {@code neoForge.unitTest} mode (see build.gradle) to give
 * the {@code test} task a bootstrapped NeoForge/Minecraft game environment — plain
 * JUnit on a bare JVM cannot do this: merely constructing a {@code VillagerProfession}
 * loads that class, which eagerly registers ARMORER/BUTCHER/etc. into
 * {@code BuiltInRegistries}, cascading into {@code PoiTypes.bootstrap} and NeoForge's
 * {@code GameData} registry hooks, none of which initialize outside FML's own
 * mod-loading lifecycle (confirmed by a real CI failure before unitTest mode was
 * wired in).
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

    private static Holder<PoiType> holderFor(ResourceKey<PoiType> key) {
        return BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(key);
    }

    @Test
    void nameMatchesTheRegisteredPath() {
        VillagerProfession dispatcher = DispatcherProfession.create();
        assertEquals("dispatcher", dispatcher.name());
    }

    @Test
    void jobSitePredicatesAcceptTheVanillaMeetingPoiType() {
        VillagerProfession dispatcher = DispatcherProfession.create();
        Holder<PoiType> meeting = holderFor(PoiTypes.MEETING);

        assertTrue(dispatcher.heldJobSite().test(meeting), "a Dispatcher must recognize the Bell/meeting POI as its held job site");
        assertTrue(dispatcher.acquirableJobSite().test(meeting), "an unemployed villager must be able to acquire the Bell/meeting POI as Dispatcher");
    }

    @Test
    void jobSitePredicatesRejectOtherVanillaPoiTypes() {
        VillagerProfession dispatcher = DispatcherProfession.create();

        for (ResourceKey<PoiType> key : List.of(PoiTypes.ARMORER, PoiTypes.FARMER, PoiTypes.LIBRARIAN, PoiTypes.HOME)) {
            Holder<PoiType> other = holderFor(key);
            assertFalse(dispatcher.heldJobSite().test(other), () -> key + " must not satisfy the Dispatcher's held job site");
            assertFalse(dispatcher.acquirableJobSite().test(other), () -> key + " must not satisfy the Dispatcher's acquirable job site");
        }
    }

    @Test
    void dispatcherOffersNothingInThisSlice() {
        VillagerProfession dispatcher = DispatcherProfession.create();
        assertTrue(dispatcher.requestedItems().isEmpty(), "no trades/offers belong to this slice");
        assertTrue(dispatcher.secondaryPoi().isEmpty(), "the Bell is the only POI the Dispatcher uses");
    }

    @Test
    void bellBlockStatesAreStillExclusivelyOwnedByTheVanillaMeetingPoiType() {
        // Guards the central risk this profession is reviewed hardest on: nothing in
        // this mod may claim the Bell's BlockStates for a type other than vanilla's
        // own MEETING PoiType (PoiTypes#registerBlockStates throws if a BlockState is
        // ever mapped to more than one PoiType, so this would fail loudly if it broke).
        for (var state : Blocks.BELL.getStateDefinition().getPossibleStates()) {
            assertTrue(PoiTypes.forState(state).map(h -> h.is(PoiTypes.MEETING)).orElse(false), () -> state + " must map to the vanilla MEETING PoiType");
        }
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
