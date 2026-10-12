package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-capacity pool of {@link SkidMark}s for one wheel: adding past capacity silently overwrites the
 * oldest mark rather than growing, which is what bounds one wheel's own render cost (MINECRAFT-225
 * requirement 3) independently of how long a car keeps sliding. Plain Java, no Minecraft types, so it is
 * unit tested directly.
 */
public final class SkidMarkRingBuffer {
    private final SkidMark[] ring;
    private int writeIndex;
    private int size;

    public SkidMarkRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.ring = new SkidMark[capacity];
    }

    public int capacity() {
        return ring.length;
    }

    public int size() {
        return size;
    }

    /** Adds a mark, overwriting the oldest slot once the buffer is at capacity. */
    public void add(SkidMark mark) {
        ring[writeIndex] = mark;
        writeIndex = (writeIndex + 1) % ring.length;
        size = Math.min(size + 1, ring.length);
    }

    /** The most recently added mark, or null if nothing has been added yet. */
    public SkidMark latest() {
        if (size == 0) {
            return null;
        }
        int lastIndex = (writeIndex - 1 + ring.length) % ring.length;
        return ring[lastIndex];
    }

    /** Every mark currently held, oldest first, excluding any that have expired. */
    public List<SkidMark> snapshotAlive(long nowMs, long lifetimeMs) {
        List<SkidMark> out = new ArrayList<>(size);
        for (SkidMark mark : ring) {
            if (mark != null && !SkidMarkMath.isExpired(nowMs - mark.spawnTimeMs(), lifetimeMs)) {
                out.add(mark);
            }
        }
        return out;
    }
}
