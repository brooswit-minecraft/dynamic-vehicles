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
 * {@link EncounterCleanup}), which is what keeps them unit-testable despite
 * this class not being. Called from {@link ContractTickHandler}'s existing
 * per-player sweep and from the player-logout listener it registers - no
 * second timer, contract store, or player sweep is added.
 */
public final class PillagerAmbushHandler {

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
            Pillager pillager = EntityType.PILLAGER.spawn(level, null, pos, MobSpawnType.EVENT, true, true);
            if (pillager != null) {
                storage.add(new EncounterRecord(pillager.getUUID(), dimension, player.getUUID(), currentTick));
            }
        }
    }

    /**
     * Called once per {@link ContractTickHandler} sweep (not per player):
     * resolves every tracked {@link EncounterRecord} against
     * {@link EncounterCleanup#shouldDespawn}, removing the mob and its
     * record when it fires. A record whose mob can't currently be found
     * (unloaded chunk, or already gone) is left alone until it either
     * reappears or ages past {@code maxEncounterLifetimeTicks} - the AC4
     * backstop against an unreachable record accumulating forever.
     */
    public static void sweep(MinecraftServer server, DeliveryContractStorage contracts, long currentTick) {
        AmbushConfig config = DispatcherOfferConfig.toAmbushConfig();
        PillagerEncounterStorage storage = PillagerEncounterStorage.of(server);
        for (EncounterRecord record : storage.snapshot()) {
            resolve(server, contracts, storage, config, record, currentTick);
        }
    }

    /** Called immediately on player logout (AC4's logout case) rather than waiting up to one sweep for it. */
    public static void cleanupForOwner(MinecraftServer server, DeliveryContractStorage contracts, UUID ownerId, long currentTick) {
        AmbushConfig config = DispatcherOfferConfig.toAmbushConfig();
        PillagerEncounterStorage storage = PillagerEncounterStorage.of(server);
        for (EncounterRecord record : storage.snapshot()) {
            if (record.ownerId().equals(ownerId)) {
                resolve(server, contracts, storage, config, record, currentTick);
            }
        }
    }

    private static void resolve(MinecraftServer server, DeliveryContractStorage contracts, PillagerEncounterStorage storage,
                                 AmbushConfig config, EncounterRecord record, long currentTick) {
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(record.dimension()));
        ServerLevel level = server.getLevel(dimensionKey);
        Entity mob = level == null ? null : level.getEntity(record.mobId());
        if (mob == null) {
            if (currentTick - record.spawnTick() >= config.maxEncounterLifetimeTicks()) {
                storage.remove(record.mobId());
            }
            return;
        }

        boolean ownerOnline = server.getPlayerList().getPlayer(record.ownerId()) != null;
        boolean ownerContractActive = contracts.book().hasActive(record.ownerId());
        boolean otherPlayerNearby = isOtherPlayerNearby(server, mob, record.ownerId(), config.nearbyPlayerRadius());

        if (EncounterCleanup.shouldDespawn(record.spawnTick(), currentTick, config.maxEncounterLifetimeTicks(), ownerContractActive, ownerOnline, otherPlayerNearby)) {
            mob.discard();
            storage.remove(record.mobId());
        }
    }

    private static boolean isOtherPlayerNearby(MinecraftServer server, Entity mob, UUID ownerId, double radius) {
        double radiusSq = radius * radius;
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other.getUUID().equals(ownerId)) {
                continue;
            }
            if (other.level() == mob.level() && other.distanceToSqr(mob) <= radiusSq) {
                return true;
            }
        }
        return false;
    }
}
