package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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
        return isSiteSafe(level, campfire, radius, baseY, baseY, pos -> false);
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
     * volume, never just one or the other.
     */
    public static boolean isSiteSafe(ServerLevel level, BlockPos campfire, int radius, int baseY, int plannedTopY,
            java.util.function.Predicate<BlockPos> matchesPlannedBlock) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int columnX = campfire.getX() + x;
                int columnZ = campfire.getZ() + z;
                if (!level.hasChunkAt(new BlockPos(columnX, baseY, columnZ))) {
                    return false;
                }
                int columnTop = Math.max(plannedTopY, TownTerrain.rawCanopyTop(level, columnX, columnZ));
                for (int y = baseY; y <= columnTop; y++) {
                    BlockPos pos = new BlockPos(columnX, y, columnZ);
                    if (!level.getWorldBorder().isWithinBounds(pos)) {
                        return false;
                    }
                    if (!isSafeToOverwrite(level, pos, campfire) && !matchesPlannedBlock.test(pos)) {
                        return false;
                    }
                    // [Review round 4, finding #6] The whole-site preflight must reject an occupied
                    // footprint, not merely scan blocks: a player, mob, or other villager anywhere
                    // in the planned volume means this tick is not safe to start mutating, the same
                    // way protected block content already aborts the whole site.
                    if (isEntityBlocking(level, pos, campfire)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Per-cell entity-occupancy check, meant to be re-run immediately before each individual
     * mutation (construction places incrementally over many ticks, unlike the legacy mechanic's
     * single instantaneous placement, so a single whole-footprint occupancy snapshot taken once
     * would either go stale over the build's lifetime or make any bystander permanently stall an
     * entire site). {@code true} when something occupies {@code pos} right now that this site is
     * not allowed to place over.
     *
     * <p>[Review round 4, finding #6] The claiming villager's own exemption applies only while it is
     * actually parked in the campfire's own COLUMN ({@code campfire}'s x/z, any y) - never the whole
     * footprint, so a claimant standing in some other planned wall/floor cell still blocks exactly
     * like any other occupant. This is deliberately a column match, not {@code pos.equals(campfire)}:
     * a villager's hitbox is taller than one block (~1.95), so a claimant parked at the campfire's
     * own cell also physically overlaps the cell directly above it (same x/z, y+1) - e.g. the roof
     * cell straight above a single-cell-footprint site, or simply the empty air above the campfire
     * at a wider one. Exempting only the single literal {@code campfire} BlockPos left that cell
     * reading as "occupied by an entity" on every tick, failing the WHOLE site's own preflight at a
     * cell the claimant's own reserved parking spot legitimately overlaps - a site's own claimant
     * must never be the reason its own site can never build.
     */
    public static boolean isEntityBlocking(ServerLevel level, BlockPos pos, BlockPos campfire) {
        AABB cell = new AABB(pos);
        boolean inCampfireColumn = pos.getX() == campfire.getX() && pos.getZ() == campfire.getZ();
        for (Entity entity : level.getEntities((Entity) null, cell)) {
            if (inCampfireColumn
                    && entity instanceof net.minecraft.world.entity.npc.Villager villager
                    && villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                            .map(gp -> gp.pos().equals(campfire)).orElse(false)
                    && villager.blockPosition().getX() == campfire.getX()
                    && villager.blockPosition().getZ() == campfire.getZ()) {
                continue; // the claiming villager parking at its own reserved campfire column
            }
            return true;
        }
        return false;
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
