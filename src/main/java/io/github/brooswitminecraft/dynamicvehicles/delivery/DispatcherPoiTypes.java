package io.github.brooswitminecraft.dynamicvehicles.delivery;

import com.google.common.collect.ImmutableSet;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;

/**
 * The Dispatcher's own PoiType (MINECRAFT-196), backed by {@link DispatcherBlocks#DISPATCH_BOARD}
 * instead of the vanilla Bell/{@code PoiTypes#MEETING}. {@code DISPATCH_BOARD}'s block states are
 * brand new (no other PoiType or {@code ExtendPoiTypesEvent} call claims them), so registering a
 * new entry here &mdash; the same {@code DeferredRegister} pattern {@link DispatcherProfession}
 * already uses for {@code Registries.VILLAGER_PROFESSION} &mdash; cannot collide the way a
 * Bell-backed PoiType would have (see the history in {@code DispatcherProfession}'s git log).
 */
public final class DispatcherPoiTypes {
    private DispatcherPoiTypes() {}

    public static final DeferredRegister<PoiType> POI_TYPES = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, DynamicVehiclesMod.MODID);

    public static final DeferredHolder<PoiType, PoiType> DISPATCH_BOARD = POI_TYPES.register("dispatch_board",
            () -> new PoiType(blockStates(DispatcherBlocks.DISPATCH_BOARD.get()), 1, 1));

    private static ImmutableSet<BlockState> blockStates(Block block) {
        return ImmutableSet.copyOf(block.getStateDefinition().getPossibleStates());
    }
}
