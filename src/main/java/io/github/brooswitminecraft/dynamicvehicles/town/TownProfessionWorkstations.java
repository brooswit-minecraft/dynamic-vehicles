package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MINECRAFT-388 review round 2, finding #7: commercial/industrial construction must include the
 * profession-appropriate work-site block for whichever profession the claiming villager actually
 * holds, reusing vanilla's own existing profession-to-workstation mapping
 * ({@code net.minecraft.world.entity.ai.village.poi.PoiTypes#bootstrap}) rather than inventing a
 * new one. Every block below is already the sole member of its profession's registered POI
 * matching-state set in vanilla, so placing it with its default state is already enough for a
 * villager of that profession to recognize and acquire it as a job site - no custom POI type, no
 * new profession mechanic.
 */
public final class TownProfessionWorkstations {
    private TownProfessionWorkstations() {}

    /**
     * [Review round 4, finding #8] A FIXED, explicit ordering - deliberately never
     * {@code BY_PROFESSION.keySet()}'s own iteration order, which the JDK randomizes per JVM launch
     * for exactly this size of immutable map - so {@link #finalProfessionFor} can pick a stable
     * index into it and get the SAME profession for the SAME site across every tick, reload, and
     * server restart.
     */
    private static final List<VillagerProfession> ASSIGNABLE_PROFESSIONS = List.of(
            VillagerProfession.ARMORER, VillagerProfession.BUTCHER, VillagerProfession.CARTOGRAPHER,
            VillagerProfession.CLERIC, VillagerProfession.FARMER, VillagerProfession.FISHERMAN,
            VillagerProfession.FLETCHER, VillagerProfession.LEATHERWORKER, VillagerProfession.LIBRARIAN,
            VillagerProfession.MASON, VillagerProfession.SHEPHERD, VillagerProfession.TOOLSMITH,
            VillagerProfession.WEAPONSMITH);
    private static final List<Block> ASSIGNABLE_WORKSTATIONS = List.of(
            Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CARTOGRAPHY_TABLE,
            Blocks.BREWING_STAND, Blocks.COMPOSTER, Blocks.BARREL,
            Blocks.FLETCHING_TABLE, Blocks.CAULDRON, Blocks.LECTERN,
            Blocks.STONECUTTER, Blocks.LOOM, Blocks.SMITHING_TABLE,
            Blocks.GRINDSTONE);

    private static final Map<VillagerProfession, Block> BY_PROFESSION;
    static {
        Map<VillagerProfession, Block> map = new LinkedHashMap<>();
        for (int i = 0; i < ASSIGNABLE_PROFESSIONS.size(); i++) {
            map.put(ASSIGNABLE_PROFESSIONS.get(i), ASSIGNABLE_WORKSTATIONS.get(i));
        }
        BY_PROFESSION = Collections.unmodifiableMap(map);
    }

    /**
     * [Review round 4, finding #8] The zone's own deterministically-assigned FINAL profession for a
     * commercial/industrial site's workstation and eventual claimant handoff - a pure function of
     * the world seed and this site's own fixed coordinates, so it is stable across ticks and
     * reloads, and deliberately independent of whichever profession the claiming villager currently
     * holds (in production, always the temporary {@code BuilderProfession.BUILDER}). "Another
     * campfire" was never a real workstation for a commercial/industrial building; this picks a
     * real vanilla profession (and so a real vanilla POI block) instead. Empty for any kind with no
     * workplace of its own ({@link TownBuildingTemplates.Kind#RESIDENTIAL}/{@code STOREHOUSE}).
     */
    public static Optional<VillagerProfession> finalProfessionFor(TownBuildingTemplates.Kind kind, long worldSeed,
            int siteX, int siteZ) {
        if (kind != TownBuildingTemplates.Kind.COMMERCIAL && kind != TownBuildingTemplates.Kind.INDUSTRIAL) {
            return Optional.empty();
        }
        long hash = TownSeedMath.mix(worldSeed, siteX, siteZ, 0x5E0F1E0F1E0FL);
        int index = TownSeedMath.bounded(hash, ASSIGNABLE_PROFESSIONS.size());
        return Optional.of(ASSIGNABLE_PROFESSIONS.get(index));
    }

    /** Empty for {@code NONE}/{@code NITWIT} (no vanilla job site at all) or any profession this
     *  mapping does not recognise - callers must simply omit a workstation in that case, never
     *  invent a substitute block.
     *
     * <p>[Review round 3, finding #5] Real Dynamic Towns campfire claimants hold this mod's own
     * custom Builder profession ({@code delivery.BuilderProfession}), never a vanilla one - the
     * {@code BY_PROFESSION} map above only ever covered vanilla professions, so a real claimant's
     * workstation was never found. Builder's own workstation IS the campfire
     * ({@code delivery.BuilderPoiTypes#CAMPFIRE}), so that is what gets reused here, checked
     * lazily (never baked into the static map above) so this class never forces
     * {@code BuilderProfession.BUILDER}'s DeferredHolder to resolve before mod registries are
     * actually populated. */
    public static Optional<Block> blockFor(VillagerProfession profession) {
        if (profession == BuilderProfession.BUILDER.get()) {
            return Optional.of(Blocks.CAMPFIRE);
        }
        return Optional.ofNullable(BY_PROFESSION.get(profession));
    }

    /**
     * MINECRAFT-388/455: {@code true} for a POI type matching any of this zone's own
     * {@link #ASSIGNABLE_PROFESSIONS} workstations - used by {@link TownResidentAllocationHandler}
     * to find a vacant, already-built workplace ANY unemployed town resident could pick up, never
     * Builder's own campfire or any other mod's/vanilla's unrelated job-site type.
     */
    public static boolean isAssignableWorkstationType(net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType> type) {
        return ASSIGNABLE_PROFESSIONS.stream().anyMatch(profession -> profession.heldJobSite().test(type));
    }

    /** The one {@link #ASSIGNABLE_PROFESSIONS} profession whose own job-site type {@code type} is -
     *  the exact inverse of {@link #blockFor}, driven by vanilla's own per-profession
     *  {@code heldJobSite} predicate rather than a second, separately-maintained block map that
     *  could drift out of sync with it. Empty for any type {@link #isAssignableWorkstationType}
     *  would also reject. */
    public static Optional<VillagerProfession> professionForType(net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType> type) {
        return ASSIGNABLE_PROFESSIONS.stream().filter(profession -> profession.heldJobSite().test(type)).findFirst();
    }
}
