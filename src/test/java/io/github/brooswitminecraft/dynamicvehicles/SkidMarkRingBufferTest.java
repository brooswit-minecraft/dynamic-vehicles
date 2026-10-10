package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SkidMarkRingBufferTest {
    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new SkidMarkRingBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new SkidMarkRingBuffer(-1));
    }

    @Test
    void emptyBufferHasNoLatestAndNoAliveMarks() {
        SkidMarkRingBuffer buffer = new SkidMarkRingBuffer(3);
        assertEquals(0, buffer.size());
        assertNull(buffer.latest());
        assertTrue(buffer.snapshotAlive(0L, 1000L).isEmpty());
    }

    @Test
    void addingPastCapacityOverwritesTheOldestInsteadOfGrowing() {
        SkidMarkRingBuffer buffer = new SkidMarkRingBuffer(2);
        buffer.add(mark(1));
        buffer.add(mark(2));
        buffer.add(mark(3)); // overwrites mark(1)
        assertEquals(2, buffer.capacity());
        assertEquals(2, buffer.size(), "size never exceeds capacity");
        assertEquals(3L, buffer.latest().spawnTimeMs());
        List<SkidMark> alive = buffer.snapshotAlive(3L, 1000L);
        assertEquals(2, alive.size());
        assertTrue(alive.stream().noneMatch(m -> m.spawnTimeMs() == 1L), "the overwritten mark is gone");
    }

    @Test
    void snapshotAliveExcludesExpiredMarks() {
        SkidMarkRingBuffer buffer = new SkidMarkRingBuffer(4);
        buffer.add(mark(0));
        buffer.add(mark(100));
        List<SkidMark> alive = buffer.snapshotAlive(250L, 200L);
        assertEquals(1, alive.size(), "only the mark younger than the lifetime survives");
        assertEquals(100L, alive.get(0).spawnTimeMs());
    }

    private static SkidMark mark(long spawnTimeMs) {
        return new SkidMark(0.0, 0.0, 0.0, 0.0f, spawnTimeMs);
    }
}
