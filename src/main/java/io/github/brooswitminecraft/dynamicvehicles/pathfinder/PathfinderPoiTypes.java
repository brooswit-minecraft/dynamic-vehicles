package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import io.github.brooswitminecraft.dynamicvehicles.delivery.VillagerJobSites;

/**
 * The Pathfinder profession's own PoiType, backed by {@link
 * PathfinderBlocks#ROUTE_BOARD}. Reuses {@code delivery.VillagerJobSites},
 * which was extracted from the Dispatcher specifically so this profession
 * could share it (see its own javadoc).
 */
public final class PathfinderPoiTypes {
    private PathfinderPoiTypes() {
    }

    public static final DeferredRegister<PoiType> POI_TYPES = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, DynamicVehiclesMod.MODID);

    public static final DeferredHolder<PoiType, PoiType> ROUTE_BOARD = POI_TYPES.register("route_board",
            () -> VillagerJobSites.poiTypeFor(PathfinderBlocks.ROUTE_BOARD.get()));
}
