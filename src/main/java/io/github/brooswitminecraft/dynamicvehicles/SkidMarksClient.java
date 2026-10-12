package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side tracking of per-wheel skid decals (MINECRAFT-225): every client tick of a car that is on
 * the ground and skidding hard enough ({@link SkidMarkMath#isSkidding}), checks whether each wheel has
 * travelled far enough since its last mark ({@link SkidMarkMath#shouldSpawn}) to lay another one. Each
 * wheel gets its own {@link SkidMarkRingBuffer}, keyed by car entity id and wheel index, so one wheel's
 * marks never crowd out another's and a single car's marks are capped regardless of how long it slides.
 * {@link SkidMarkRenderer} reads back the alive marks every frame, capped across the whole client by
 * {@link SkidMarkMath#capToTotal}.
 *
 * <p>No Minecraft client-only imports, so (like {@link CarEntity}'s own call site) this stays safe to
 * reference from code that only ever runs on the client branch of {@link CarEntity#tick()}.
 */
final class SkidMarksClient {
    private static final Map<Long, SkidMarkRingBuffer> BUFFERS = new HashMap<>();

    private SkidMarksClient() {}

    /** Called once per client tick of {@code car}, from {@link CarEntity#tick()}'s client branch. */
    static void tick(CarEntity car) {
        if (!car.onGround() || !SkidMarkMath.isSkidding(car.clientSlip())) {
            return;
        }
        double yaw = Math.toRadians(car.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double y = car.getY() + SkidMarkMath.SURFACE_OFFSET;
        long now = System.currentTimeMillis();
        for (int wheel = 0; wheel < CarEntity.WHEELS.length; wheel++) {
            double[] local = CarEntity.WHEELS[wheel];
            // Same rotation convention as CarEntity#reportSlip: local[0] = right offset, local[1] = forward offset.
            double x = car.getX() + local[0] * cos - local[1] * sin;
            double z = car.getZ() + local[0] * sin + local[1] * cos;
            SkidMarkRingBuffer buffer = BUFFERS.computeIfAbsent(key(car.getId(), wheel),
                    k -> new SkidMarkRingBuffer(SkidMarkMath.MAX_PER_WHEEL));
            SkidMark last = buffer.latest();
            if (last != null && !SkidMarkMath.shouldSpawn(distance(last, x, y, z))) {
                continue;
            }
            buffer.add(new SkidMark(x, y, z, (float) yaw, now));
        }
    }

    /** Forgets every ring buffer for a car that just left the client (from {@code onClientRemoval}):
     * without this a long session watching many cars come and go would grow {@link #BUFFERS} forever. */
    static void forget(CarEntity car) {
        for (int wheel = 0; wheel < CarEntity.WHEELS.length; wheel++) {
            BUFFERS.remove(key(car.getId(), wheel));
        }
    }

    /** Every mark alive right now across every car and wheel this client is tracking, already capped to
     * {@link SkidMarkMath#MAX_TOTAL}. */
    static List<SkidMark> aliveMarks(long nowMs) {
        List<SkidMark> all = new ArrayList<>();
        for (SkidMarkRingBuffer buffer : BUFFERS.values()) {
            all.addAll(buffer.snapshotAlive(nowMs, SkidMarkMath.LIFETIME_MS));
        }
        return SkidMarkMath.capToTotal(all, SkidMarkMath.MAX_TOTAL);
    }

    private static long key(int carId, int wheel) {
        return ((long) carId << 8) | wheel;
    }

    private static double distance(SkidMark mark, double x, double y, double z) {
        double dx = mark.x() - x;
        double dy = mark.y() - y;
        double dz = mark.z() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
