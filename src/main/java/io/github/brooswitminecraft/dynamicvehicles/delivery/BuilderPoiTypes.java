package io.github.brooswitminecraft.dynamicvehicles.delivery;

import com.google.common.collect.ImmutableSet;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Dedicated POI identity for campfires used as Builder workstations. */
public final class BuilderPoiTypes {
    private BuilderPoiTypes() {}

    public static final DeferredRegister<PoiType> POI_TYPES =
            DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, DynamicVehiclesMod.MODID);
    public static final DeferredHolder<PoiType, PoiType> CAMPFIRE = POI_TYPES.register("builder_campfire",
            () -> new PoiType(ImmutableSet.copyOf(Blocks.CAMPFIRE.getStateDefinition().getPossibleStates()), 1, 1));
}
