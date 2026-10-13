package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minecraft-free coverage for MINECRAFT-324's UUID/day board scheduling. */
class DispatcherOfferScheduleTest {

    private static final UUID DISPATCHER = UUID.fromString("d34db33f-2aa7-4d81-9a46-73ee2059f012");

    @Test
    void sameDispatcherAndDayProduceTheSameOrderedAdjacentRegions() {
        RegionCoord origin = new RegionCoord(17, -4);
        long day = DispatcherOfferSchedule.day(72_123L);
        List<RegionCoord> first = RingMath.destinationRingsUpTo(
                origin, 1, DispatcherOfferSchedule.orderSeed(DISPATCHER, day));
        List<RegionCoord> second = RingMath.destinationRingsUpTo(
                origin, 1, DispatcherOfferSchedule.orderSeed(DISPATCHER, day));

        assertEquals(first, second);
        assertEquals(8, first.size(), "level 1 considers exactly the eight adjacent placement regions");
        assertTrue(first.stream().noneMatch(origin::equals), "the Dispatcher region is never a destination");
    }

    @Test
    void nextMinecraftDayRotatesTheDailyOrder() {
        RegionCoord origin = new RegionCoord(0, 0);
        long today = DispatcherOfferSchedule.day(47_999L);
        List<RegionCoord> todayOrder = RingMath.destinationRingsUpTo(
                origin, 1, DispatcherOfferSchedule.orderSeed(DISPATCHER, today));
        List<RegionCoord> tomorrowOrder = RingMath.destinationRingsUpTo(
                origin, 1, DispatcherOfferSchedule.orderSeed(DISPATCHER, today + 1));

        assertNotEquals(todayOrder, tomorrowOrder, "a day change rotates this Dispatcher's offers");
        assertEquals(today, DispatcherOfferSchedule.day(47_999L));
        assertEquals(today + 1, DispatcherOfferSchedule.day(48_000L));
    }

    @Test
    void differentDispatchersHaveIndependentDailyOrders() {
        long day = 3L;
        assertNotEquals(DispatcherOfferSchedule.orderSeed(DISPATCHER, day),
                DispatcherOfferSchedule.orderSeed(UUID.fromString("00000000-0000-0000-0000-000000000001"), day));
    }
}
