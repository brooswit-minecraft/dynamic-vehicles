package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.List;

/**
 * Server-side destination-discovery service: given a level and an origin
 * position, enumerates vanilla Village structure-placement regions outward
 * in distance rings (via {@link RingMath}, randomized per ring) and returns
 * their predicted placement chunks. It never asks whether those chunks have
 * generated, loaded, or saved a structure start.
 *
 * <h2>API choices (see PR description for the full rationale)</h2>
 * <ul>
 *   <li><b>Candidate enumeration</b> uses the placement the LIVE chunk
 *   generator itself actually consults for the {@code minecraft:villages}
 *   structures: {@link ServerLevel#getChunkSource()}'s
 *   {@link ChunkGeneratorStructureState#getPlacementsForStructure}, not the
 *   raw {@link StructureSet#placement()} off the registry. The generator's
 *   structure-state is authoritative because it is already filtered to this
 *   dimension's actual biome source (a placement for a structure whose
 *   biomes don't exist here is simply absent from it), so it can never hand
 *   back a placement that the real generator would never use to place a
 *   village in this level. In vanilla, with no datapack override, this
 *   resolves to the exact same {@link RandomSpreadStructurePlacement}
 *   object the registry's {@code StructureSet#placement()} holds — but
 *   going through the generator state is what makes that guaranteed rather
 *   than coincidental. Once that placement is in hand, candidate chunks come
 *   from its own {@link RandomSpreadStructurePlacement#getPotentialStructureChunk}
 *   — the same seed+spacing+separation+salt math the world generator itself
 *   uses to decide where a village CAN go. This is driven purely by the
 *   world seed and the structure-set settings; nothing here scans blocks or
 *   chunks to find candidates.</li>
 *   <li><b>No generation or confirmation</b>: after deriving the candidate
 *   chunk from the placement, this service deliberately stops. In
 *   particular, it does not call {@code checkStructurePresence}, retrieve a
 *   chunk, inspect a saved structure start, or scan structures. That makes
 *   offers available for destination regions that do not yet exist on disk.</li>
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
     * @param orderSeed deterministic Dispatcher UUID + Minecraft-day seed
     *                  controlling the order within each ring
     * @return predicted vanilla Village placement chunks, nearest ring first;
     *         never includes the origin's own region
     */
    public static List<PredictedVillage> findPredictedVillages(
            ServerLevel level, BlockPos origin, int maxRing, int slots, long orderSeed) {
        if (slots <= 0) {
            return List.of();
        }

        Holder<StructureSet> villages = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE_SET)
                .getHolderOrThrow(BuiltinStructureSets.VILLAGES);
        List<Holder<Structure>> villageStructureHolders = villages.value().structures().stream()
                .map(StructureSet.StructureSelectionEntry::structure)
                .toList();
        if (villageStructureHolders.isEmpty()) {
            return List.of();
        }

        ChunkGeneratorStructureState generatorState = level.getChunkSource().getGeneratorState();
        RandomSpreadStructurePlacement placement = villageStructureHolders.stream()
                .flatMap(structure -> generatorState.getPlacementsForStructure(structure).stream())
                .filter(RandomSpreadStructurePlacement.class::isInstance)
                .map(RandomSpreadStructurePlacement.class::cast)
                .findFirst()
                .orElse(null);
        if (placement == null) {
            // Either a datapack replaced village placement with something
            // other than the vanilla random-spread scheme (this service
            // doesn't know how to enumerate it), or — per the live generator
            // state, which is already filtered to this dimension's actual
            // biome source — villages simply cannot generate here at all.
            return List.of();
        }
        long seed = level.getSeed();
        RegionCoord originRegion = toRegion(origin, placement.spacing());

        // destinationRingsUpTo visits rings nearest-first and shuffles only
        // within a ring. Thus level 1 examines exactly its eight adjacent
        // placement regions, including diagonals and excluding ring 0.
        return RingMath.collectUpToSlots(RingMath.destinationRingsUpTo(originRegion, maxRing, orderSeed), slots, region -> {
            ChunkPos candidateChunk = placement.getPotentialStructureChunk(
                    seed, region.x() * placement.spacing(), region.z() * placement.spacing());
            BlockPos targetPos = new BlockPos(candidateChunk.getMiddleBlockX(), origin.getY(), candidateChunk.getMiddleBlockZ());
            double approxDistance = horizontalDistance(origin, targetPos);
            int ring = RingMath.ringOf(originRegion, region);
            return new PredictedVillage(new VillageIdentity(region, level.dimension()), ring, approxDistance, targetPos);
        });
    }

    private static RegionCoord toRegion(BlockPos pos, int spacingInChunks) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        return new RegionCoord(Math.floorDiv(chunkX, spacingInChunks), Math.floorDiv(chunkZ, spacingInChunks));
    }

    /** Horizontal (X/Z-only) block distance — deliberately ignores Y. */
    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

}
