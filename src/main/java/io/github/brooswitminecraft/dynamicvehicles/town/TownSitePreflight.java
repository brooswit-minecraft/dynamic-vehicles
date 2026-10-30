package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MINECRAFT-388 review finding #1: before {@link TownConstructionService} mutates any block at a
 * claimed-campfire site, the WHOLE site must be preflighted and rejected (no mutation, no resource
 * charge) when it overlaps occupied, protected, player-built, or otherwise-claimed content (clear-
 * or-known-natural-terrain + no foreign entities).
 *
 * <p>[Review round 3, finding #3] The scan must cover the WHOLE planned building volume - floor
 * through the tallest planned cell - not merely today's terrain/canopy top; see
 * {@link #isSiteSafe(ServerLevel, BlockPos, int, int, int, java.util.function.Predicate)}'s
 * {@code plannedTopY} parameter.
 */
public final class TownSitePreflight {
    private TownSitePreflight() {}

    /**
     * Checks every column in the (2*radius+1) square footprint centred on {@code campfire}, from
     * {@code baseY} up to that column's own real terrain top (excluding trees - a tall tree well
     * above the building's planned roof must still block/salvage correctly), for the duration of
     * {@link #isSafeToOverwrite}. Returns {@code false} the moment any cell fails, without mutating
     * anything - the caller must then abort the entire site for this tick.
     */
    public static boolean isSiteSafe(ServerLevel level, BlockPos campfire, int radius, int baseY) {
        return isSiteSafe(level, campfire, radius, baseY, baseY, pos -> false, null, pos -> false);
    }

    /**
     * As above, but a cell that does not read as safe-to-overwrite can still pass when
     * {@code matchesPlannedBlock} says it already holds exactly the block THIS site's own template
     * planned there - i.e. a previous tick's own (correct) progress, never any other foreign
     * content. Without this, a site that already has walls up would fail its own preflight on
     * every later tick purely because a placed plank/brick/cobblestone block is not "natural
     * terrain" - the whole-site check would never let construction continue past its first tick.
     *
     * <p>[Review round 3, finding #3] Scanning only up to today's terrain/canopy top is not
     * enough: a chest sitting above the current terrain but still within the PLANNED building's
     * walls/roof (e.g. inside a tree canopy above the roofline, or a player build overhead) would
     * otherwise pass preflight and only be discovered mid-build, after earlier floor/wall cells
     * are already placed and charged. Callers pass {@code plannedTopY} (the site's own planned
     * roof Y) so this scans the UNION of the real terrain top and the whole planned footprint
     * volume, never just one or the other. {@code claimantId} (nullable) is the UUID of the SPECIFIC
     * villager {@link TownConstructionService} selected as this site's own claimant - see
     * {@link #isEntityBlocking(ServerLevel, BlockPos, BlockPos, java.util.UUID)}.
     *
     * <p>[Review round 11, finding #2 supplement] {@code isBlocked} (see
     * {@link TownStorage#isCellBlocked}) rejects the whole site the moment ANY planned cell was
     * durably excluded after a DIFFERENT plan's own forward-write-plus-rollback failure (a road's,
     * most commonly) - a claimed building must never adopt that unpaid, unverified partial state as
     * ordinary clearable terrain just because its own preflight never otherwise objects to it. This
     * check is deliberately unconditional (checked even where {@code pos.equals(campfire)} would
     * otherwise always pass): a blocked cell is never safe, regardless of what else is planned there.
     */
    public static boolean isSiteSafe(ServerLevel level, BlockPos campfire, int radius, int baseY, int plannedTopY,
            java.util.function.Predicate<BlockPos> matchesPlannedBlock, java.util.UUID claimantId,
            java.util.function.Predicate<BlockPos> isBlocked) {
        return firstRejection(level, campfire, radius, baseY, plannedTopY, matchesPlannedBlock, claimantId, isBlocked, false).isEmpty();
    }

    /**
     * Returns the first production preflight rejection using the same traversal and predicates as
     * {@link #isSiteSafe}.  Package-visible so the town GameTest can report a failed fixture's
     * exact cell and live state without recreating a second, potentially divergent preflight.
     */
    static Optional<Rejection> firstRejection(ServerLevel level, BlockPos campfire, int radius, int baseY, int plannedTopY,
            java.util.function.Predicate<BlockPos> matchesPlannedBlock, java.util.UUID claimantId,
            java.util.function.Predicate<BlockPos> isBlocked) {
        return firstRejection(level, campfire, radius, baseY, plannedTopY, matchesPlannedBlock, claimantId, isBlocked, true);
    }

    private static Optional<Rejection> firstRejection(ServerLevel level, BlockPos campfire, int radius, int baseY, int plannedTopY,
            java.util.function.Predicate<BlockPos> matchesPlannedBlock, java.util.UUID claimantId,
            java.util.function.Predicate<BlockPos> isBlocked, boolean detailedOccupants) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int columnX = campfire.getX() + x;
                int columnZ = campfire.getZ() + z;
                if (!level.hasChunkAt(new BlockPos(columnX, baseY, columnZ))) {
                    // Do not turn a failed availability guard into a chunk acquisition merely to
                    // describe it.  The production path must remain no-load at this early exit.
                    return Optional.of(Rejection.unavailable("unloaded-chunk", new BlockPos(columnX, baseY, columnZ)));
                }
                int columnTop = Math.max(plannedTopY, TownTerrain.rawCanopyTop(level, columnX, columnZ));
                for (int y = baseY; y <= columnTop; y++) {
                    BlockPos pos = new BlockPos(columnX, y, columnZ);
                    if (!level.getWorldBorder().isWithinBounds(pos)) {
                        // A border rejection is likewise intentionally reported without reading
                        // the cell: it may be outside the server's available world data.
                        return Optional.of(Rejection.unavailable("world-border", pos));
                    }
                    if (isBlocked.test(pos)) {
                        return Optional.of(Rejection.at("blocked-cell", pos, level));
                    }
                    if (!isSafeToOverwrite(level, pos, campfire) && !matchesPlannedBlock.test(pos)) {
                        return Optional.of(Rejection.at("unsafe-block", pos, level));
                    }
                    // [Review round 4, finding #6] The whole-site preflight must reject an occupied
                    // footprint, not merely scan blocks: a player, mob, or other villager anywhere
                    // in the planned volume means this tick is not safe to start mutating, the same
                    // way protected block content already aborts the whole site.
                    if (detailedOccupants) {
                        List<String> occupants = blockingOccupants(level, pos, campfire, claimantId);
                        if (!occupants.isEmpty()) {
                            return Optional.of(Rejection.at("foreign-occupant", pos, level, occupants));
                        }
                    } else if (isEntityBlocking(level, pos, campfire, claimantId)) {
                        return Optional.of(Rejection.at("foreign-occupant", pos, level));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** A lossless description of the cell that made production preflight refuse the site. */
    static record Rejection(String reason, BlockPos position, String state, String blockEntity, List<String> occupants) {
        private static Rejection unavailable(String reason, BlockPos position) {
            return new Rejection(reason, position.immutable(), "unavailable", "unavailable", List.of());
        }

        private static Rejection at(String reason, BlockPos position, ServerLevel level) {
            return at(reason, position, level, List.of());
        }

        private static Rejection at(String reason, BlockPos position, ServerLevel level, List<String> occupants) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            return new Rejection(reason, position.immutable(), String.valueOf(level.getBlockState(position)),
                    blockEntity == null ? "none" : String.valueOf(blockEntity.getType()), List.copyOf(occupants));
        }
    }

    /**
     * Per-cell entity-occupancy check, meant to be re-run immediately before each individual
     * mutation (construction places incrementally over many ticks, unlike the legacy mechanic's
     * single instantaneous placement, so a single whole-footprint occupancy snapshot taken once
     * would either go stale over the build's lifetime or make any bystander permanently stall an
     * entire site). {@code true} when something occupies {@code pos} right now that this site is
     * not allowed to place over.
     *
     * <p>[Review round 4, finding #6 / round 6, finding #1] The claiming villager's own exemption
     * applies only to the SPECIFIC villager {@code claimantId} identifies (never {@code null}, which
     * exempts nothing) - matched by UUID identity, never merely "any villager whose JOB_SITE memory
     * happens to match". Two villagers can end up with the same (erroneous/duplicate/stale) JOB_SITE
     * memory; {@link TownConstructionService} picks exactly ONE of them as this site's real claimant
     * and relocates only that one before a solid closing block lands, so only that exact entity may
     * ever be exempt here - any OTHER villager standing in the column (even one whose memory also
     * points here) still blocks exactly like any other occupant. The exemption is further narrowed
     * to the campfire's own COLUMN ({@code campfire}'s x/z, any y) - never the whole footprint - a
     * villager's hitbox is taller than one block (~1.95), so the claimant parked at the campfire's
     * own cell also physically overlaps the cell directly above it (same x/z, y+1); exempting only
     * the single literal {@code campfire} BlockPos would leave that cell reading as "occupied" on
     * every tick, failing the WHOLE site's own preflight at a cell its own claimant legitimately
     * overlaps.
     */
    public static boolean isEntityBlocking(ServerLevel level, BlockPos pos, BlockPos campfire, java.util.UUID claimantId) {
        return !blockingOccupants(level, pos, campfire, claimantId).isEmpty();
    }

    private static List<String> blockingOccupants(ServerLevel level, BlockPos pos, BlockPos campfire, java.util.UUID claimantId) {
        AABB cell = new AABB(pos);
        boolean inCampfireColumn = pos.getX() == campfire.getX() && pos.getZ() == campfire.getZ();
        List<String> occupants = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, cell)) {
            if (inCampfireColumn && claimantId != null && entity.getUUID().equals(claimantId)) {
                continue; // the EXACT claiming villager parking at its own reserved campfire column
            }
            occupants.add(entity.getType() + " uuid=" + entity.getUUID() + " pos=" + entity.position());
        }
        return occupants;
    }

    /**
     * {@code true} when {@code pos} is either already the site's own claimed campfire cell, empty
     * air/fluid, or known-natural terrain ({@link TownTerrain#isNaturalTerrain}) this mod is
     * allowed to clear. {@code false} for ANY other block - explicitly including another live
     * campfire (a second claimed construction anchor, which must never be overwritten by this
     * site's own build), a chest, or any other player-built/protected/foreign content.
     */
    public static boolean isSafeToOverwrite(ServerLevel level, BlockPos pos, BlockPos campfire) {
        if (pos.equals(campfire)) {
            return true;
        }
        // A fluid state alone is not evidence that a cell is disposable: modded or future
        // waterlogged blocks can carry a block entity (and therefore inventory/NBT) while still
        // satisfying TownTerrain#isNaturalTerrain's fluid allowance.  Terrain preparation never
        // snapshots arbitrary block-entity data, so reject it before any site mutation instead of
        // risking a state-only rollback that silently destroys that data.
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.CAMPFIRE)) {
            // Never overwrite another live campfire - including one this exact site's own claim
            // happens to have grown past (its template must not have planned a cell there, but
            // this is the hard backstop regardless).
            return false;
        }
        return TownTerrain.isNaturalTerrain(state);
    }
}
