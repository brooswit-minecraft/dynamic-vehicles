package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathfinderVillagerProgressTest {

    @Test
    void advancesAfterTheConfiguredCompletionCount() {
        PathfinderVillagerProgress.State state = new PathfinderVillagerProgress.State(1, 0);
        state = PathfinderVillagerProgress.onCompletion(state);
        assertEquals(new PathfinderVillagerProgress.State(1, 1), state);
        state = PathfinderVillagerProgress.onCompletion(state);
        assertEquals(new PathfinderVillagerProgress.State(1, 2), state);
        state = PathfinderVillagerProgress.onCompletion(state);
        assertEquals(new PathfinderVillagerProgress.State(2, 0), state, "the third completion at level 1 should advance to level 2");
    }

    @Test
    void neverAdvancesPastLevel5() {
        PathfinderVillagerProgress.State state = new PathfinderVillagerProgress.State(5, 0);
        for (int i = 0; i < 10; i++) {
            state = PathfinderVillagerProgress.onCompletion(state);
        }
        assertEquals(5, state.level());
    }

    @Test
    void rejectsOutOfRangeLevel() {
        assertThrows(IllegalArgumentException.class, () -> new PathfinderVillagerProgress.State(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PathfinderVillagerProgress.State(6, 0));
    }

    @Test
    void rejectsNegativeCompletions() {
        assertThrows(IllegalArgumentException.class, () -> new PathfinderVillagerProgress.State(1, -1));
    }
}
