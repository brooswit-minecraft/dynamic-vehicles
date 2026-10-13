package io.github.brooswitminecraft.dynamicvehicles.delivery;

import com.google.common.collect.ImmutableSet;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The single Builder profession, whose workstation is a vanilla campfire. */
public final class BuilderProfession {
    private BuilderProfession() {}

    public static final DeferredRegister<VillagerProfession> PROFESSIONS =
            DeferredRegister.create(Registries.VILLAGER_PROFESSION, DynamicVehiclesMod.MODID);
    public static final DeferredHolder<VillagerProfession, VillagerProfession> BUILDER =
            PROFESSIONS.register("builder", BuilderProfession::create);

    static VillagerProfession create() {
        return new VillagerProfession("builder", BuilderProfession::isBuilderCampfire,
                BuilderProfession::isBuilderCampfire, ImmutableSet.of(), ImmutableSet.of(), null);
    }

    private static boolean isBuilderCampfire(Holder<PoiType> poiType) {
        return poiType.is(BuilderPoiTypes.CAMPFIRE.getKey());
    }
}
