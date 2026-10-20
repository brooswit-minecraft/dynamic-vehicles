package io.github.brooswitminecraft.dynamicvehicles.town;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownDonationConversionTest {
    @Test
    void unrecognizedItemsAreNotDonatable() {
        assertNull(TownDonationConversion.creditFor("minecraft:diamond"));
        assertEquals(false, TownDonationConversion.isDonatable("minecraft:diamond"));
    }

    @Test
    void moreProcessedStoneMaterialsCreditMoreThanRawCobblestone() {
        int cobblestone = TownDonationConversion.creditFor("minecraft:cobblestone").amount();
        int stoneBricks = TownDonationConversion.creditFor("minecraft:stone_bricks").amount();
        int smoothStone = TownDonationConversion.creditFor("minecraft:smooth_stone").amount();

        assertTrue(stoneBricks > cobblestone, "stone bricks should credit more than cobblestone");
        assertTrue(smoothStone > cobblestone, "smooth stone should credit more than cobblestone");
    }

    @Test
    void everyDonatableStoneCreditsTheStoneCategory() {
        assertEquals(TownResourceCategory.STONE, TownDonationConversion.creditFor("minecraft:cobblestone").category());
        assertEquals(TownResourceCategory.STONE, TownDonationConversion.creditFor("minecraft:stone_bricks").category());
    }

    @Test
    void woodDonationsCreditTheWoodCategory() {
        assertEquals(TownResourceCategory.WOOD, TownDonationConversion.creditFor("minecraft:oak_log").category());
    }
}
