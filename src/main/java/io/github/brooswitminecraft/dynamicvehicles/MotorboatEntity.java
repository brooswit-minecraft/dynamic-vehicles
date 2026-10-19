package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * MINECRAFT-410: a watercraft, now rebuilt on the same Sable rigid-body engine {@link CarEntity} uses
 * (via {@link SableMotorboatBody}/{@link SableCompat}) instead of the old scalar single-water-column,
 * direct-yaw-assignment model ({@code MotorboatPhysics} before this ticket). Server-authoritative, same as
 * the car: the rigid body is the single source of truth for position and orientation, and this class's
 * own {@code setYRot}/{@code setPos} calls only ever read it back (see {@link SableMotorboatBody#syncEntity}),
 * never compute it from input directly.
 *
 * <p>Extends {@link SimpleVehicleEntity} (shared one-seat-vehicle plumbing with {@link ChopperEntity}/
 * {@link BiplaneEntity}, left untouched by this ticket) but, unlike those two, carries its own
 * orientation-sync machinery copied from {@link CarEntity}'s pattern (DATA_ORIENTATION/DATA_RIGID_BODY,
 * movement/orientation queues, {@code lerpTo} override, {@code renderOrientation}, NBT persistence) rather
 * than sharing or refactoring {@code CarEntity} itself -- deliberate, to keep zero risk of touching
 * anything the in-flight car-drivetrain work (MINECRAFT-401/PR #172) depends on.
 */
public class MotorboatEntity extends SimpleVehicleEntity {
    private static final double HALF_WIDTH = VehicleGeometry.MOTORBOAT_HALF_WIDTH;
    private static final double HALF_HEIGHT = VehicleGeometry.MOTORBOAT_HALF_HEIGHT;
    private static final double HALF_LENGTH = VehicleGeometry.MOTORBOAT_HALF_LENGTH;
    private static final double DT = 1.0 / 20.0;

    /** The body's orientation, synced every tick so clients can draw pitch and roll -- same pattern as
     * {@code CarEntity.DATA_ORIENTATION}/{@code DATA_RIGID_BODY}. */
    private static final EntityDataAccessor<org.joml.Quaternionf> DATA_ORIENTATION =
            SynchedEntityData.defineId(MotorboatEntity.class, EntityDataSerializers.QUATERNION);
    private static final EntityDataAccessor<Boolean> DATA_RIGID_BODY =
            SynchedEntityData.defineId(MotorboatEntity.class, EntityDataSerializers.BOOLEAN);

    // Client side: received movement/orientation states wait here and are consumed one per tick -- same
    // "keep about one in hand" queue CarEntity uses, copied verbatim (see nextState's own javadoc there).
    private final java.util.ArrayDeque<double[]> movementQueue = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<org.joml.Quaternionf> orientationQueue = new java.util.ArrayDeque<>();
    private int movementStarved;
    private int orientationStarved;
    private final org.joml.Quaternionf previousOrientation = new org.joml.Quaternionf();
    private final org.joml.Quaternionf currentOrientation = new org.joml.Quaternionf();

    private org.joml.Quaternionf savedOrientation;
    /** The Sable rigid body, as Object so this class never loads Sable types (see {@link SableCompat}). */
    private Object sableBody;

    public MotorboatEntity(EntityType<? extends MotorboatEntity> type, Level level) {
        super(type, level);
    }

    public static double halfWidth() {
        return HALF_WIDTH;
    }

    public static double halfHeight() {
        return HALF_HEIGHT;
    }

    public static double halfLength() {
        return HALF_LENGTH;
    }

    @Override
    protected Vec3 seatOffset() {
        return new Vec3(0.0, HALF_HEIGHT + 0.1, 0.2);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ORIENTATION, new org.joml.Quaternionf());
        builder.define(DATA_RIGID_BODY, false);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_ORIENTATION.equals(key) && level().isClientSide()) {
            orientationQueue.add(new org.joml.Quaternionf(entityData.get(DATA_ORIENTATION)));
            while (orientationQueue.size() > 6) {
                orientationQueue.poll();
            }
        }
    }

    /** Server: publish the body's orientation to clients. */
    void publishOrientation(org.joml.Quaternionf orientation) {
        entityData.set(DATA_RIGID_BODY, true);
        entityData.set(DATA_ORIENTATION, orientation);
    }

    /** Whether this boat is driven by a rigid body, so the renderer should draw its full orientation. */
    public boolean isRigidBody() {
        return entityData.get(DATA_RIGID_BODY);
    }

    /** Client: the orientation to draw, interpolated between the last two updates. */
    public org.joml.Quaternionf renderOrientation(float partialTick) {
        return previousOrientation.nlerp(currentOrientation, partialTick, new org.joml.Quaternionf());
    }

    /** Client: take the next buffered movement and orientation, one per tick (two when the buffer runs
     * long) -- same {@code nextState} logic {@code CarEntity} uses, copied verbatim. */
    private void consumeClientStates() {
        if (!isRigidBody()) {
            return;
        }
        double[] move = nextState(movementQueue, movementStarved);
        movementStarved = move == null && !movementQueue.isEmpty() ? movementStarved + 1 : 0;
        if (move != null) {
            setPos(move[0], move[1], move[2]);
        }
        org.joml.Quaternionf q = nextState(orientationQueue, orientationStarved);
        orientationStarved = q == null && !orientationQueue.isEmpty() ? orientationStarved + 1 : 0;
        previousOrientation.set(currentOrientation);
        if (q != null) {
            currentOrientation.set(q);
        }
    }

    private static <T> T nextState(java.util.ArrayDeque<T> queue, int starved) {
        if (queue.size() >= 4) {
            queue.poll();
            return queue.poll();
        }
        if (queue.size() >= 2 || (queue.size() == 1 && starved >= 1)) {
            return queue.poll();
        }
        return null;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (level().isClientSide() && isRigidBody()) {
            movementQueue.add(new double[] {x, y, z});
            while (movementQueue.size() > 6) {
                movementQueue.poll();
            }
            return;
        }
        super.lerpTo(x, y, z, yRot, xRot, steps);
    }

    /**
     * The boat is server-authoritative, same reasoning as {@code CarEntity.isControlledByLocalInstance}:
     * by default the rider's own client would otherwise count as the local controller and fight the
     * server's own rigid-body position updates.
     */
    @Override
    public boolean isControlledByLocalInstance() {
        return false;
    }

    @Override
    public void tick() {
        super.tick(); // Entity.tick() + driveTick(rider), via SimpleVehicleEntity.
        if (level().isClientSide()) {
            consumeClientStates();
        }
    }

    @Override
    protected void driveTick(Player rider) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            // Client side: position/orientation come from consumeClientStates() in tick() above, not from
            // running the physics model locally -- see isControlledByLocalInstance().
            return;
        }
        tickSable(serverLevel, rider);
    }

    private void tickSable(ServerLevel serverLevel, Player rider) {
        if (sableBody == null) {
            sableBody = SableCompat.createBoat(serverLevel, this);
            if (sableBody == null) {
                return;
            }
        }
        double throttle = rider == null ? 0.0 : Mth.clamp(rider.zza, -1.0, 1.0);
        double steer = rider == null ? 0.0 : Mth.clamp(rider.xxa, -1.0, 1.0);
        try {
            SableCompat.tickBoat(sableBody, this, throttle, steer, DT);
            SableCompat.syncEntityBoat(sableBody, this);
            if (tickCount % 20 == 0) {
                DynamicVehiclesMod.LOGGER.debug("motorboat {} {}", getUUID(), SableCompat.describeBoat(sableBody));
            }
        } catch (RuntimeException dead) {
            // Sable released the physics body under us (its handle is null, e.g. a chunk unload race).
            // Drop it so the next tick rebuilds a fresh one from the entity's position, instead of
            // crashing the server -- same recovery CarEntity.tickSable uses.
            DynamicVehiclesMod.LOGGER.warn("motorboat {} lost its Sable body ({}); rebuilding", getUUID(), dead.toString());
            try {
                SableCompat.removeBoat(sableBody);
            } catch (RuntimeException ignored) {
                // already gone
            }
            sableBody = null;
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        OrientationNbt.Orientation orientation = OrientationNbt.read(tag.contains("OrientationW"),
                tag.getFloat("OrientationX"), tag.getFloat("OrientationY"), tag.getFloat("OrientationZ"), tag.getFloat("OrientationW"));
        if (orientation != null) {
            savedOrientation = new org.joml.Quaternionf(orientation.x(), orientation.y(), orientation.z(), orientation.w());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        org.joml.Quaternionf q = savedOrientation;
        if (sableBody != null) {
            try {
                q = SableCompat.orientationBoat(sableBody);
            } catch (RuntimeException gone) {
                // Sable has already released the physics body (chunk unload); keep the last known orientation.
            }
        }
        if (q != null) {
            tag.putFloat("OrientationX", q.x);
            tag.putFloat("OrientationY", q.y);
            tag.putFloat("OrientationZ", q.z);
            tag.putFloat("OrientationW", q.w);
        }
    }

    /** The orientation to restore when the body is recreated after a reload, or null to derive it from the yaw. */
    org.joml.Quaternionf savedOrientation() {
        return savedOrientation;
    }

    /** MINECRAFT-410: lets a test start the next-created Sable body from a deliberately tilted pose (e.g.
     * to exercise the real per-point buoyancy restoring torque starting from a non-level orientation),
     * rather than always the identity/yaw-only pose. Package-private, test-only -- no production code path
     * calls this; must be called before the first {@link #driveTick} creates the Sable body. */
    void setSavedOrientationForTest(org.joml.Quaternionf orientation) {
        this.savedOrientation = orientation;
    }

    /** MINECRAFT-410: the rigid body's current live orientation, for tests exercising the real production
     * per-point sampler/point-force path (same package only -- not a public API; the only other read path
     * for this is the full NBT-save round trip {@code motorboatOrientationNbtRoundTripsThroughARealTilted
     * Pose} already exercises). Returns the last saved/known orientation if the Sable body isn't currently
     * alive. */
    org.joml.Quaternionf currentOrientationForTest() {
        if (sableBody != null) {
            try {
                return SableCompat.orientationBoat(sableBody);
            } catch (RuntimeException gone) {
                // Sable released the body (chunk unload race); fall through to the last known orientation.
            }
        }
        return savedOrientation;
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (sableBody != null) {
            SableCompat.removeBoat(sableBody);
            sableBody = null;
        }
        super.remove(reason);
    }

    /** Current server-authoritative speed, m/s (0 before the rigid body exists). */
    double sableSpeed() {
        return sableBody == null ? 0.0 : SableCompat.speedBoat(sableBody);
    }
}
