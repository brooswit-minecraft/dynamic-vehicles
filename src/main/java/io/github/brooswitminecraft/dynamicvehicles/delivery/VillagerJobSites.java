package io.github.brooswitminecraft.dynamicvehicles.delivery;

import com.google.common.collect.ImmutableSet;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Shared construction for "a villager profession that works at exactly one dedicated POI type",
 * the shape {@link DispatcherProfession} and the Pathfinder share. Each profession keeps its own
 * registry entries, block, and POI type (so a villager can only take the job at its own
 * workstation &mdash; the MINECRAFT-196 lesson), and calls these helpers instead of repeating the
 * {@link VillagerProfession} constructor and block-state boilerplate.
 */
public final class VillagerJobSites {
    private VillagerJobSites() {}

    /** Every block state of {@code block}, the set a {@link PoiType} claims as its job site. */
    public static ImmutableSet<BlockState> statesOf(Block block) {
        return ImmutableSet.copyOf(block.getStateDefinition().getPossibleStates());
    }

    /** A single-ticket, single-search-distance POI type claiming all of {@code block}'s states. */
    public static PoiType poiTypeFor(Block block) {
        return new PoiType(statesOf(block), 1, 1);
    }

    /**
     * A profession with no vanilla trades, no gathered items and no secondary job sites, that both
     * acquires and keeps its job only at {@code poi}.
     */
    public static VillagerProfession profession(String name, DeferredHolder<PoiType, PoiType> poi) {
        return new VillagerProfession(name, matching(poi), matching(poi), ImmutableSet.of(), ImmutableSet.of(), null);
    }

    private static java.util.function.Predicate<Holder<PoiType>> matching(DeferredHolder<PoiType, PoiType> poi) {
        return poiType -> poiType.is(poi.getKey());
    }
}
