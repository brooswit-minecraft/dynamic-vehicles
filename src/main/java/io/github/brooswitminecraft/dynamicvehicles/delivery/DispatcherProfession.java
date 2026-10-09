package io.github.brooswitminecraft.dynamicvehicles.delivery;

import com.google.common.collect.ImmutableSet;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;

/**
 * Registers the {@code dispatcher} villager profession (MINECRAFT-105/117).
 *
 * <p>The job-site POI is {@link DispatcherPoiTypes#DISPATCH_BOARD} (MINECRAFT-196), the
 * Dispatcher's own PoiType backed by {@link DispatcherBlocks#DISPATCH_BOARD}. Earlier
 * versions of this profession borrowed the vanilla Bell's {@code PoiTypes#MEETING} type
 * instead; that meant a jobless villager next to a plain Bell silently took the
 * {@code dispatcher} profession too (and vice versa, a Dispatcher-seeking villager could
 * acquire an unrelated Bell), which is the MINECRAFT-196 bug this registration now avoids
 * by recognizing only its own dedicated POI.
 */
public final class DispatcherProfession {
    private DispatcherProfession() {}

    public static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION,
            DynamicVehiclesMod.MODID);

    public static final DeferredHolder<VillagerProfession, VillagerProfession> DISPATCHER = PROFESSIONS.register("dispatcher",
            DispatcherProfession::create);

    /** Package-visible for direct unit testing without a registry/event bus. */
    static VillagerProfession create() {
        return new VillagerProfession("dispatcher", DispatcherProfession::isDispatchBoardPoi, DispatcherProfession::isDispatchBoardPoi,
                ImmutableSet.of(), ImmutableSet.of(), null);
    }

    private static boolean isDispatchBoardPoi(Holder<PoiType> poiType) {
        return poiType.is(DispatcherPoiTypes.DISPATCH_BOARD.getKey());
    }
}
