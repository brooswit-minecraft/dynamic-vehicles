package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathfinderConfigTest {

    @Test
    void rewardScalesByLevelAsPostedOnTheTicket() {
        assertEquals(4, PathfinderConfig.rewardEmeralds(PathfinderLevel.LEVEL_1));
        assertEquals(20, PathfinderConfig.rewardEmeralds(PathfinderLevel.LEVEL_2));
        assertEquals(36, PathfinderConfig.rewardEmeralds(PathfinderLevel.LEVEL_3));
        assertEquals(52, PathfinderConfig.rewardEmeralds(PathfinderLevel.LEVEL_4));
        assertEquals(68, PathfinderConfig.rewardEmeralds(PathfinderLevel.LEVEL_5));
    }

    @Test
    void timeLimitIsNinetySecondsPerLegIncludingTheClosingLeg() {
        PathfinderRoute level1 = PathfinderRouteGenerator.generate(new PathfinderRegion(0, 0), PathfinderLevel.LEVEL_1);
        assertEquals(20L * 90L * 9L, PathfinderConfig.timeLimitTicks(level1)); // 8 checkpoints + 1 closing leg
    }
}
