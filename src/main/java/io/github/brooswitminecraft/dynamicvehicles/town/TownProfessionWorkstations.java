package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

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

    private static final Map<VillagerProfession, Block> BY_PROFESSION = Map.ofEntries(
            Map.entry(VillagerProfession.ARMORER, Blocks.BLAST_FURNACE),
            Map.entry(VillagerProfession.BUTCHER, Blocks.SMOKER),
            Map.entry(VillagerProfession.CARTOGRAPHER, Blocks.CARTOGRAPHY_TABLE),
            Map.entry(VillagerProfession.CLERIC, Blocks.BREWING_STAND),
            Map.entry(VillagerProfession.FARMER, Blocks.COMPOSTER),
            Map.entry(VillagerProfession.FISHERMAN, Blocks.BARREL),
            Map.entry(VillagerProfession.FLETCHER, Blocks.FLETCHING_TABLE),
            Map.entry(VillagerProfession.LEATHERWORKER, Blocks.CAULDRON),
            Map.entry(VillagerProfession.LIBRARIAN, Blocks.LECTERN),
            Map.entry(VillagerProfession.MASON, Blocks.STONECUTTER),
            Map.entry(VillagerProfession.SHEPHERD, Blocks.LOOM),
            Map.entry(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE),
            Map.entry(VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE)
    );

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
}
