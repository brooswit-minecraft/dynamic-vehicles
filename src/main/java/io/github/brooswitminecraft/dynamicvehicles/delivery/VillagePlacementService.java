package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

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
 *   <li><b>Existence confirmation</b> is two steps, both non-generating:
 *   first {@link StructureManager#checkStructurePresence} (backed by
 *   vanilla's own {@code StructureCheck} — the same machinery the
 *   {@code /locate} command uses) answers from the in-memory loaded-chunk
 *   cache if available, otherwise scans only the structure-starts NBT field
 *   straight off the saved chunk file, without loading or generating the
 *   chunk itself. A result other than {@code START_PRESENT} (including
 *   {@code CHUNK_LOAD_NEEDED}, meaning the chunk was never saved and
 *   answering for real would require generating it) is treated as
 *   "not confirmed" — this service will never pay that generation cost to
 *   resolve it. Only once step one reports {@code START_PRESENT} — i.e. the
 *   save already has this structure's start recorded, so the chunk was
 *   already generated past {@link ChunkStatus#STRUCTURE_STARTS} at some
 *   point, loaded or not right now — does step two call
 *   {@link ServerLevel#getChunk(int, int, ChunkStatus, boolean)} with
 *   {@code requireChunk = true} to obtain the real {@link StructureStart}
 *   object (for its actual bounding box). That call cannot trigger new
 *   world generation here: step one already confirmed the chunk's saved
 *   data is at least at this status, so it only deserializes data that
 *   already exists on disk (or is already loaded).</li>
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
        List<Structure> villageStructures = villageStructureHolders.stream().map(Holder::value).toList();

        long seed = level.getSeed();
        RegionCoord originRegion = toRegion(origin, placement.spacing());

        // destinationRingsUpTo already visits rings nearest-first and regions
        // within a ring in shuffled (not distance-sorted) order, so the
        // slot-limited result below is already "ordered by ring, nearest
        // first" as required, with no extra sort needed.
        return RingMath.collectUpToSlots(RingMath.destinationRingsUpTo(originRegion, maxRing, seed), slots, region -> {
            ChunkPos candidateChunk = placement.getPotentialStructureChunk(
                    seed, region.x() * placement.spacing(), region.z() * placement.spacing());
            StructureStart start = confirmedVillageStart(level, placement, candidateChunk, villageStructures);
            if (start == null) {
                return null;
            }
            // The structure's real bounding-box centre, not merely the
            // candidate chunk's middle at y=0 — a tighter, more useful
            // "actual start location" for AC1's recoverability requirement.
            BlockPos startPos = start.getBoundingBox().getCenter();
            double approxDistance = horizontalDistance(origin, startPos);
            int ring = RingMath.ringOf(originRegion, region);
            return new ConfirmedVillage(new VillageIdentity(region, level.dimension()), ring, approxDistance, startPos);
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

    /**
     * Confirms whether a village structure has actually generated at
     * {@code candidateChunk} and, if so, returns its real
     * {@link StructureStart} — without ever forcing that chunk to generate.
     * See the class-level javadoc for why each of these two steps is safe.
     */
    private static StructureStart confirmedVillageStart(
            ServerLevel level, StructurePlacement placement, ChunkPos candidateChunk, List<Structure> villageStructures) {
        StructureManager structureManager = level.structureManager();
        for (Structure structure : villageStructures) {
            StructureCheckResult presence = structureManager.checkStructurePresence(candidateChunk, structure, placement, false);
            if (presence != StructureCheckResult.START_PRESENT) {
                continue;
            }
            ChunkAccess chunk = level.getChunk(candidateChunk.x, candidateChunk.z, ChunkStatus.STRUCTURE_STARTS, true);
            StructureStart start = chunk.getStartForStructure(structure);
            if (start != null && start.isValid()) {
                return start;
            }
        }
        return null;
    }
}
