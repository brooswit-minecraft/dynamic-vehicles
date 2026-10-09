package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** MINECRAFT-109 AC2/AC8: blocks-to-kilometres formatting, including boundary values. */
class OfferDistanceFormatTest {

    @Test
    void toKilometres_dividesByOneThousand() {
        assertEquals(1.0, OfferDistanceFormat.toKilometres(1000.0), 1e-9);
        assertEquals(0.5, OfferDistanceFormat.toKilometres(500.0), 1e-9);
    }

    @Test
    void toKilometres_zeroBlocksIsZeroKilometres() {
        assertEquals(0.0, OfferDistanceFormat.toKilometres(0.0), 1e-9);
    }

    @Test
    void format_roundsToOneDecimalAndAppendsUnit() {
        assertEquals("1.0 km", OfferDistanceFormat.format(1000.0));
        assertEquals("0.5 km", OfferDistanceFormat.format(500.0));
        assertEquals("0.0 km", OfferDistanceFormat.format(0.0));
    }

    @Test
    void format_roundsHalfUpAtTheDisplayedDigit() {
        // 1234 blocks -> 1.234 km -> rounds to 1.2 km.
        assertEquals("1.2 km", OfferDistanceFormat.format(1234.0));
        // 1260 blocks -> 1.26 km -> rounds to 1.3 km.
        assertEquals("1.3 km", OfferDistanceFormat.format(1260.0));
    }

    @Test
    void format_largeDistance() {
        assertEquals("1000.0 km", OfferDistanceFormat.format(1_000_000.0));
    }
}
