package io.github.brooswitminecraft.dynamicvehicles.delivery;

import com.google.common.collect.ImmutableSet;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;

/**
 * Registers the {@code dispatcher} villager profession (MINECRAFT-105/117).
 *
 * <p>The job-site POI is the vanilla {@link PoiTypes#MEETING} type (the Bell), not a
 * new PoiType. A BlockState can only ever be mapped to one PoiType (see
 * {@code net.minecraft.world.entity.ai.village.poi.PoiTypes#registerBlockStates} and
 * NeoForge's {@code PoiTypeExtender#register}, both of which throw
 * {@code IllegalStateException} on a second mapping of the same state) and the Bell's
 * states are already mapped to {@code MEETING} at vanilla bootstrap. A new
 * "dispatcher" PoiType built from the Bell's block states would collide with that
 * existing mapping and crash at registration time, and NeoForge's
 * {@code ExtendPoiTypesEvent} only ADDS new states to a foreign type, it cannot move
 * states already claimed by another type. So the only way to give Dispatcher a POI at
 * the Bell without touching the Bell's own registration is for the profession's
 * {@code heldJobSite}/{@code acquirableJobSite} predicates to recognize the existing
 * {@code MEETING} PoiType directly, exactly like {@code VillagerProfession.NONE} and
 * {@code NITWIT} recognize {@code PoiType.NONE} without owning it.
 */
public final class DispatcherProfession {
    private DispatcherProfession() {}

    public static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION,
            DynamicVehiclesMod.MODID);

    public static final DeferredHolder<VillagerProfession, VillagerProfession> DISPATCHER = PROFESSIONS.register("dispatcher",
            DispatcherProfession::create);

    /** Package-visible for direct unit testing without a registry/event bus. */
    static VillagerProfession create() {
        return new VillagerProfession("dispatcher", DispatcherProfession::isMeetingPoi, DispatcherProfession::isMeetingPoi, ImmutableSet.of(),
                ImmutableSet.of(), null);
    }

    private static boolean isMeetingPoi(Holder<PoiType> poiType) {
        return poiType.is(PoiTypes.MEETING);
    }
}
