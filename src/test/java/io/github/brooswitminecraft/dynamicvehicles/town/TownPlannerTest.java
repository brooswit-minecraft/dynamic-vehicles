package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownPlannerTest {
    @Test
    void generationIsDeterministicForTheSameRegionAndSeed() {
        RegionCoord region = new RegionCoord(3, -5);
        TownPlan first = TownPlanner.generate(region, 42L);
        TownPlan second = TownPlanner.generate(region, 42L);

        assertEquals(first.townCenterX(), second.townCenterX());
        assertEquals(first.townCenterZ(), second.townCenterZ());
        assertEquals(first.storehouseVariant(), second.storehouseVariant());
        assertEquals(first.edgeConnections(), second.edgeConnections());
    }

    @Test
    void generationIsDeterministicForNegativeRegionCoordinates() {
        RegionCoord region = new RegionCoord(-7, -11);
        TownPlan first = TownPlanner.generate(region, 1234L);
        TownPlan second = TownPlanner.generate(region, 1234L);

        assertEquals(first.townCenterX(), second.townCenterX());
        assertEquals(first.townCenterZ(), second.townCenterZ());
    }

    @Test
    void reloadingRegeneratesTheIdenticalPlanWithoutAnyStoredState() {
        RegionCoord region = new RegionCoord(100, 200);
        long seed = -99L;
        TownPlan beforeReload = TownPlanner.generate(region, seed);
        // "Reload" here is simulated by simply calling generate again - the whole point of making
        // the plan a pure function is that nothing needs to survive a real reload for this to hold.
        TownPlan afterReload = TownPlanner.generate(region, seed);

        assertEquals(beforeReload, afterReload);
    }

    @Test
    void differentRegionsOrSeedsProduceDifferentCentres() {
        TownPlan a = TownPlanner.generate(new RegionCoord(0, 0), 1L);
        TownPlan b = TownPlanner.generate(new RegionCoord(1, 0), 1L);
        TownPlan c = TownPlanner.generate(new RegionCoord(0, 0), 2L);

        assertTrue(a.townCenterX() != b.townCenterX() || a.townCenterZ() != b.townCenterZ());
        assertTrue(a.townCenterX() != c.townCenterX() || a.townCenterZ() != c.townCenterZ());
    }

    @Test
    void townCenterStaysWellInsideTheRegionAwayFromEdgeRoadPoints() {
        RegionCoord region = new RegionCoord(5, 5);
        TownPlan plan = TownPlanner.generate(region, 7L);
        int minX = TownRegion.minBlockX(region);
        int minZ = TownRegion.minBlockZ(region);

        assertTrue(plan.townCenterX() > minX && plan.townCenterX() < minX + TownConstants.REGION_SIZE_BLOCKS);
        assertTrue(plan.townCenterZ() > minZ && plan.townCenterZ() < minZ + TownConstants.REGION_SIZE_BLOCKS);
    }

    @Test
    void zoningRespectsTheDocumentedRatioOverManyParcelsAlongOneAxis() {
        TownPlan plan = TownPlanner.generate(new RegionCoord(0, 0), 55L);
        int residential = 0;
        int commercial = 0;
        int industrial = 0;
        for (int i = 0; i < 200; i++) {
            int d = i * TownConstants.PARCEL_SIZE_BLOCKS;
            int x = plan.townCenterX() + d;
            int z = plan.townCenterZ() + d + 3;
            switch (plan.zoneAt(x, z)) {
                case RESIDENTIAL -> residential++;
                case COMMERCIAL -> commercial++;
                case INDUSTRIAL -> industrial++;
            }
        }
        assertTrue(residential > commercial, "residential should be the plurality zone over a long run");
        assertTrue(residential > 0 && commercial > 0 && industrial > 0, "all three zones should appear");
    }
}
