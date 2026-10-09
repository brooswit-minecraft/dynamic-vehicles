package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicvehicles.DispatcherOfferConfig;

/**
 * The thin Minecraft-facing layer for MINECRAFT-130: the only class that
 * actually spawns, tags (via {@link PillagerEncounterStorage}), or removes
 * an ambush pillager. All of the decisions - whether this is a roll tick,
 * whether the roll succeeds, how many pillagers and where, whether a
 * tracked mob should despawn - live in the plain classes
 * ({@link AmbushSchedule}, {@link AmbushRoll}, {@link SpawnOffsetPicker},
 * {@link EncounterCleanup}, {@link OrphanAmbushCleanup}), which is what
 * keeps them unit-testable despite this class not being. Called from
 * {@link ContractTickHandler}'s existing per-player sweep, from the
 * player-logout listener it registers, and from the
 * {@link EntityJoinLevelEvent} listener this class registers itself - no
 * second timer, contract store, or player sweep is added.
 */
public final class PillagerAmbushHandler {

    /**
     * Entity tag (persists via {@code Entity}'s own NBT "Tags" list, independent of
     * {@link PillagerEncounterStorage}) marking a mob as "one of ours" for the
     * {@link #onEntityJoinLevel} backstop - review finding: a tagged mob can outlive its
     * {@link EncounterRecord} (e.g. it should never happen, but is cheap to guard against)
     * and would otherwise sit forever as an untracked pillager once its chunk reloads.
     */
    private static final String AMBUSH_TAG = "dynamicvehicles_ambush";

    private static final Random RANDOM = new Random();

    private PillagerAmbushHandler() {
    }

    /**
     * Called once per {@link ContractTickHandler} sweep tick for a player
     * whose contract is {@code STILL_ACTIVE}. A no-op on every tick that
     * isn't this contract's roll window (AC1), and on every roll window the
     * roll itself didn't succeed (AC3).
     */
    public static void attemptRoll(ServerPlayer player, DeliveryContract contract, long currentTick) {
        AmbushConfig config = DispatcherOfferConfig.toAmbushConfig();
        if (!AmbushSchedule.isRollTick(contract.acceptedAtTick(), currentTick, ContractTickHandler.EVALUATE_INTERVAL_TICKS, config.rollIntervalTicks())) {
            return;
        }
        if (!AmbushRoll.attempt(contract.danger(), config, RANDOM)) {
            return;
        }
        int size = AmbushRoll.sizeFor(contract.danger(), config);
        spawnEncounter(player, size, config, currentTick);
    }

    /**
     * AC2: spawns near or ahead of the player using heading/velocity - the
     * forward vector of whatever the player is actually moving as (its
     * vehicle, when driving one; the player itself otherwise), never car
     * code's own internals. AC6: spawning never touches the vehicle or the
     * player's control state, so driving is untouched.
     *
     * <p>Uses {@code EntityType.create} + the entity's own tag (via the
     * spawn-time consumer) + an explicit {@code addFreshEntity}, rather than
     * the one-call {@code EntityType.spawn}, so both the tag AND the
     * {@link EncounterRecord} exist in that order BEFORE the mob joins the
     * level - the {@link EntityJoinLevelEvent} this same spawn triggers must
     * never see a tagged-but-unrecorded mob and mistake it for an orphan.
     */
    private static void spawnEncounter(ServerPlayer player, int size, AmbushConfig config, long currentTick) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Entity mover = player.getVehicle() != null ? player.getVehicle() : player;
        Vec3 forward = mover.getLookAngle();
        PillagerEncounterStorage storage = PillagerEncounterStorage.of(player.server);
        String dimension = level.dimension().location().toString();

        for (int i = 0; i < size; i++) {
            SpawnOffset offset = SpawnOffsetPicker.pick(forward.x, forward.z, config.spawnMinDistance(), config.spawnMaxDistance(), config.spawnArcRadians(), RANDOM);
            BlockPos pos = BlockPos.containing(player.getX() + offset.dx(), player.getY(), player.getZ() + offset.dz());
            Pillager pillager = EntityType.PILLAGER.create(level, p -> p.addTag(AMBUSH_TAG), pos, MobSpawnType.EVENT, true, true);
            if (pillager != null) {
                storage.add(new EncounterRecord(pillager.getUUID(), dimension, player.getUUID(), currentTick));
                level.addFreshEntity(pillager);
            }
        }
    }

    /**
     * Called once per {@link ContractTickHandler} sweep (not per player):
     * resolves every tracked {@link EncounterRecord} against
     * {@link EncounterCleanup#shouldDespawn}, removing the mob and its
     * record when it fires. A record whose mob can't currently be found
     * (its chunk is unloaded, most commonly - the player is driving away)
     * is left exactly as is: it is NOT dropped just because it has aged
     * past {@code maxEncounterLifetimeTicks} while unreachable, since doing
     * so would silently orphan a mob that still exists on disk and will
     * resurface, untracked, the moment its chunk reloads (the real leak a
     * review caught). The entity's own {@link #AMBUSH_TAG} plus
     * {@link #onEntityJoinLevel} is the backstop for a record lost any
     * other way.
     */
    public static void sweep(MinecraftServer server, DeliveryContractStorage contracts, long currentTick) {
        AmbushConfig config = DispatcherOfferConfig.toAmbushConfig();
        PillagerEncounterStorage storage = PillagerEncounterStorage.of(server);
        for (EncounterRecord record : storage.snapshot()) {
            findMob(server, record).ifPresent(mob -> resolveAgainstMob(server, contracts, storage, config, record, mob, currentTick, false));
        }
    }

    /**
     * Called on player logout (AC4's logout case). Passes {@code ownerLeaving = true} rather
     * than asking {@code PlayerList} whether the owner is still online: NeoForge fires
     * {@code PlayerLoggedOutEvent} before removing the player from the list, so that query
     * would still say "online" here and this would be a no-op, silently deferring to the next
     * sweep instead of actually being immediate (review finding).
     */
    public static void cleanupForOwner(MinecraftServer server, DeliveryContractStorage contracts, UUID ownerId, long currentTick) {
        AmbushConfig config = DispatcherOfferConfig.toAmbushConfig();
        PillagerEncounterStorage storage = PillagerEncounterStorage.of(server);
        for (EncounterRecord record : storage.snapshot()) {
            if (record.ownerId().equals(ownerId)) {
                findMob(server, record).ifPresent(mob -> resolveAgainstMob(server, contracts, storage, config, record, mob, currentTick, true));
            }
        }
    }

    /**
     * MINECRAFT-130 review backstop: whenever ANY entity (re)joins a level -
     * including a chunk loading a previously-spawned mob back in - a tagged
     * pillager with no matching {@link EncounterRecord} is resolved via
     * {@link OrphanAmbushCleanup} instead of being left untracked forever. A
     * tagged pillager that DOES still have a live record is resolved right
     * away via the normal predicate too, rather than waiting for the next
     * sweep.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof Pillager pillager) || !pillager.getTags().contains(AMBUSH_TAG)) {
            return;
        }
        if (!(pillager.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        AmbushConfig config = DispatcherOfferConfig.toAmbushConfig();
        PillagerEncounterStorage storage = PillagerEncounterStorage.of(server);
        DeliveryContractStorage contracts = DeliveryContractStorage.of(server);
        long currentTick = server.overworld().getGameTime();

        Optional<EncounterRecord> record = storage.find(pillager.getUUID());
        if (record.isPresent()) {
            resolveAgainstMob(server, contracts, storage, config, record.get(), pillager, currentTick, false);
            return;
        }
        boolean anyoneNearby = isOtherPlayerNearby(server, pillager, null, config.nearbyPlayerRadius());
        if (OrphanAmbushCleanup.shouldDiscardOrphan(false, anyoneNearby)) {
            pillager.discard();
        }
    }

    private static Optional<Entity> findMob(MinecraftServer server, EncounterRecord record) {
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(record.dimension()));
        ServerLevel level = server.getLevel(dimensionKey);
        return level == null ? Optional.empty() : Optional.ofNullable(level.getEntity(record.mobId()));
    }

    private static void resolveAgainstMob(MinecraftServer server, DeliveryContractStorage contracts, PillagerEncounterStorage storage,
                                           AmbushConfig config, EncounterRecord record, Entity mob, long currentTick, boolean ownerLeaving) {
        boolean ownerOnline = !ownerLeaving && server.getPlayerList().getPlayer(record.ownerId()) != null;
        boolean ownerContractActive = contracts.book().hasActive(record.ownerId());
        boolean otherPlayerNearby = isOtherPlayerNearby(server, mob, record.ownerId(), config.nearbyPlayerRadius());

        if (EncounterCleanup.shouldDespawn(record.spawnTick(), currentTick, config.maxEncounterLifetimeTicks(), ownerContractActive, ownerOnline, otherPlayerNearby)) {
            mob.discard();
            storage.remove(record.mobId());
        }
    }

    /** @param excludeId a player id to exclude from the check (the owner), or {@code null} to exclude no one */
    private static boolean isOtherPlayerNearby(MinecraftServer server, Entity mob, UUID excludeId, double radius) {
        double radiusSq = radius * radius;
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (excludeId != null && other.getUUID().equals(excludeId)) {
                continue;
            }
            if (other.level() == mob.level() && other.distanceToSqr(mob) <= radiusSq) {
                return true;
            }
        }
        return false;
    }
}
