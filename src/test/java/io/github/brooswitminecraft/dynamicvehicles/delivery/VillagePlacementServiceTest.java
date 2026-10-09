package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link VillagePlacementService#collectUpToSlots}, the
 * slot-count-stop / exhaustion seam that {@code findVillages} delegates to
 * (acceptance criterion #6) — without a Minecraft server, by faking which
 * regions "confirm".
 */
class VillagePlacementServiceTest {

    private static final RegionCoord ORIGIN = new RegionCoord(0, 0);

    @Test
    void stopsAsSoonAsSlotsAreFilled() {
        List<RegionCoord> regions = RingMath.destinationRingsUpTo(ORIGIN, 3, 1L);
        int[] confirmCalls = {0};

        List<RegionCoord> found = VillagePlacementService.collectUpToSlots(regions, 2, region -> {
            confirmCalls[0]++;
            return region; // every candidate "confirms"
        });

        assertEquals(2, found.size());
        assertEquals(2, confirmCalls[0], "should stop checking further regions once slots are filled");
    }

    @Test
    void exhaustsGracefullyWhenFewerThanSlotsConfirm() {
        List<RegionCoord> regions = RingMath.destinationRingsUpTo(ORIGIN, 2, 1L);

        List<RegionCoord> found = VillagePlacementService.collectUpToSlots(regions, 100, region -> null);

        assertTrue(found.isEmpty());
    }

    @Test
    void exhaustsGracefullyWhenNoRegionsAtAll() {
        List<RegionCoord> found = VillagePlacementService.collectUpToSlots(List.of(), 5, region -> region);

        assertTrue(found.isEmpty());
    }

    @Test
    void onlyNonNullConfirmationsAreCollectedInOrder() {
        List<RegionCoord> regions = RingMath.destinationRingsUpTo(ORIGIN, 2, 1L);

        List<RegionCoord> confirmedOnly = regions.stream().limit(5).toList();
        Set<RegionCoord> confirmedSet = Set.copyOf(confirmedOnly);

        List<RegionCoord> found = VillagePlacementService.collectUpToSlots(
                regions, regions.size(), region -> confirmedSet.contains(region) ? region : null);

        assertEquals(confirmedOnly, found);
    }
}
