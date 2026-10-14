package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

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
        // The client receives authoritative vehicle positions via setPos; it does not run the server's
        // collision step, so Entity#onGround is not a reliable client-side ground signal here. Gate each
        // wheel against the block directly beneath its contact point instead. Slip remains the shared
        // synced signal used by the audio path, so ordinary rolling still cannot lay marks.
        if (!SkidMarkMath.isSkidding(car.clientSlip())) {
            return;
        }
        double yaw = Math.toRadians(car.getYRot());
        long now = System.currentTimeMillis();
        double[][] wheels = car.spec().mounts();
        for (int wheel = 0; wheel < wheels.length; wheel++) {
            double[] mount = wheels[wheel];
            BlockHitResult support = wheelSupport(car, mount);
            if (support.getType() == HitResult.Type.MISS || support.getDirection() != Direction.UP) continue;
            double x = support.getLocation().x;
            double y = support.getLocation().y + SkidMarkMath.SURFACE_OFFSET;
            double z = support.getLocation().z;
            SkidMarkRingBuffer buffer = BUFFERS.computeIfAbsent(key(car.getId(), wheel),
                    k -> new SkidMarkRingBuffer(SkidMarkMath.MAX_PER_WHEEL));
            SkidMark last = buffer.latest();
            if (last != null && !SkidMarkMath.shouldSpawn(distance(last, x, y, z))) {
                continue;
            }
            buffer.add(new SkidMark(x, y, z, (float) yaw, now));
        }
    }

    /** Match the physics suspension ray for rigid bodies; the simple model's origin is ground height. */
    static BlockHitResult wheelSupport(CarEntity car, double[] mount) {
        Vec3 from;
        Vec3 to;
        if (car.isRigidBody()) {
            var orientation = car.renderOrientation(1.0F);
            Vector3f offset = orientation.transform(new Vector3f((float) mount[0], (float) mount[1], (float) mount[2]));
            Vector3f down = orientation.transform(new Vector3f(0, -1, 0));
            from = car.position().add(offset.x, car.spec().halfY() + offset.y, offset.z);
            to = from.add(down.x * (car.spec().restLength() + 0.25),
                    down.y * (car.spec().restLength() + 0.25), down.z * (car.spec().restLength() + 0.25));
        } else {
            double yaw = Math.toRadians(car.getYRot());
            from = car.position().add(mount[0] * Math.cos(yaw) - mount[2] * Math.sin(yaw), 0.25,
                    mount[0] * Math.sin(yaw) + mount[2] * Math.cos(yaw));
            to = from.add(0, -0.5, 0);
        }
        return car.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, car));
    }

    /** Forgets every ring buffer for a car that just left the client (from {@code onClientRemoval}):
     * without this a long session watching many cars come and go would grow {@link #BUFFERS} forever. */
    static void forget(CarEntity car) {
        for (int wheel = 0; wheel < car.spec().mounts().length; wheel++) {
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
