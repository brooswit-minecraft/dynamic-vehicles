package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import io.github.brooswitminecraft.dynamicvehicles.delivery.VillagerJobSites;

/**
 * Registers the {@code pathfinder} villager profession (MINECRAFT-379).
 * Its job-site POI is {@link PathfinderPoiTypes#ROUTE_BOARD}, its own
 * dedicated block — never a vanilla or Dispatcher/Builder POI, for the
 * same reason {@code delivery.DispatcherProfession} gives (MINECRAFT-196):
 * a shared POI would let an unrelated villager pick up this profession,
 * and vice versa.
 *
 * <p>Route selection/start, offers, HUD and persistence are separate
 * follow-up work (MINECRAFT-379 acceptance criteria #4); this class only
 * lets a villager become a Pathfinder at a route board.
 */
public final class PathfinderProfession {
    private PathfinderProfession() {
    }

    public static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION,
            DynamicVehiclesMod.MODID);

    public static final DeferredHolder<VillagerProfession, VillagerProfession> PATHFINDER = PROFESSIONS.register("pathfinder",
            PathfinderProfession::create);

    /** Package-visible for direct unit testing without a registry/event bus. */
    static VillagerProfession create() {
        return VillagerJobSites.profession("pathfinder", PathfinderPoiTypes.ROUTE_BOARD);
    }
}
