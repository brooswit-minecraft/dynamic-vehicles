package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.data.worldgen.BuiltinStructureSets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side destination-discovery service: given a level and an origin
 * position, enumerates village structure placement regions outward in
 * distance rings (via {@link RingMath}, randomized per ring) and returns only
 * the ones where a Village structure is confirmed to have actually
 * generated, each with enough information to recover its real start
 * location.
 *
 * <h2>API choices (see PR description for the full rationale)</h2>
 * <ul>
 *   <li><b>Candidate enumeration</b> uses the real, live {@link StructurePlacement}
 *   NeoForge/vanilla already computed for the {@code minecraft:villages}
 *   {@link StructureSet} ({@link ChunkGeneratorStructureState#getPlacementsForStructureSet})
 *   and that placement's own {@link RandomSpreadStructurePlacement#getPotentialStructureChunk}
 *   — i.e. the same seed+spacing+separation+salt math the world generator
 *   itself uses to decide where a village CAN go. This is driven purely by
 *   the world seed and the structure-set settings; nothing here scans
 *   blocks or chunks to find candidates.</li>
 *   <li><b>Existence confirmation</b> reads the candidate chunk only up to
 *   {@link ChunkStatus#STRUCTURE_STARTS} with {@code requireChunk = false}
 *   ({@link ServerLevel#getChunk(int, int, ChunkStatus, boolean)}), which
 *   returns {@code null} instead of generating anything when the chunk
 *   hasn't reached that status yet. A village is "confirmed" only when that
 *   lookup already has a valid {@link StructureStart} recorded for one of
 *   the village structures — i.e. only once the world has actually produced
 *   it, never forced into existence by this service.</li>
 * </ul>
 */
public final class VillagePlacementService {

    private VillagePlacementService() {
    }

    /**
     * @param level   the dimension to search in
     * @param origin  the search's origin position (typically the dispatcher's
     *                source village)
     * @param maxRing the furthest ring (inclusive) to search before giving up
     * @param slots   how many confirmed villages to return at most
     * @return confirmed generated villages, nearest ring first; never
     *         includes the origin's own region; empty if none were found
     *         within {@code maxRing}
     */
    public static List<ConfirmedVillage> findVillages(ServerLevel level, BlockPos origin, int maxRing, int slots) {
        if (slots <= 0) {
            return List.of();
        }

        RandomSpreadStructurePlacement placement = villagePlacement(level);
        if (placement == null) {
            return List.of();
        }

        long seed = level.getSeed();
        int spacingInBlocks = placement.spacing() * 16;
        RegionCoord originRegion = toRegion(origin, placement.spacing());

        List<ConfirmedVillage> found = new ArrayList<>();
        for (RegionCoord region : RingMath.destinationRingsUpTo(originRegion, maxRing, seed)) {
            if (found.size() >= slots) {
                break;
            }
            ChunkPos candidateChunk = placement.getPotentialStructureChunk(seed, region.x(), region.z());
            StructureStart start = confirmedVillageStart(level, candidateChunk);
            if (start == null) {
                continue;
            }
            BlockPos startPos = start.getChunkPos().getMiddleBlockPosition(0).atY(0);
            double approxDistance = Math.sqrt(origin.distSqr(startPos));
            int ring = RingMath.ringOf(originRegion, region);
            found.add(new ConfirmedVillage(
                    new VillageIdentity(region, level.dimension()),
                    ring,
                    approxDistance,
                    startPos
            ));
        }
        // destinationRingsUpTo already visits rings nearest-first and regions
        // within a ring in shuffled (not distance-sorted) order, so the
        // result is already "ordered by ring, nearest first" as required.
        return found;
    }

    /**
     * Resolves the live placement NeoForge/vanilla computed for the
     * {@code minecraft:villages} structure set in this level, or {@code null}
     * if that structure set has no (or a non-random-spread) placement — which
     * would mean a datapack replaced village placement with something this
     * service does not know how to enumerate.
     */
    private static RandomSpreadStructurePlacement villagePlacement(ServerLevel level) {
        ChunkGeneratorStructureState structureState = level.getChunkSource().getGeneratorState();
        Holder<StructureSet> villages = level.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.VILLAGES);
        List<StructurePlacement> placements = structureState.getPlacementsForStructureSet(villages);
        for (StructurePlacement candidate : placements) {
            if (candidate instanceof RandomSpreadStructurePlacement spread) {
                return spread;
            }
        }
        return null;
    }

    private static RegionCoord toRegion(BlockPos pos, int spacingInChunks) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        return new RegionCoord(Math.floorDiv(chunkX, spacingInChunks), Math.floorDiv(chunkZ, spacingInChunks));
    }

    /**
     * Looks up the candidate chunk only up to {@code STRUCTURE_STARTS}
     * without forcing it to be generated, and returns a valid village
     * {@link StructureStart} if one is already recorded there.
     */
    private static StructureStart confirmedVillageStart(ServerLevel level, ChunkPos candidateChunk) {
        ChunkAccess chunk = level.getChunk(candidateChunk.x, candidateChunk.z, ChunkStatus.STRUCTURE_STARTS, false);
        if (chunk == null) {
            return null;
        }
        for (StructureStart start : chunk.getAllStarts().values()) {
            if (start == null || !start.isValid()) {
                continue;
            }
            Structure structure = start.getStructure();
            if (isVillage(level, structure)) {
                return start;
            }
        }
        return null;
    }

    private static boolean isVillage(ServerLevel level, Structure structure) {
        ResourceKey<Structure> key = level.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                .getResourceKey(structure)
                .orElse(null);
        return key != null && key.location().getPath().startsWith("village_");
    }
}
