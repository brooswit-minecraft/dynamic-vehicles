package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;

/** Registers {@link DispatchBoardBlockEntity}, the per-placed-board persisted-UUID storage for MINECRAFT-323. */
public final class DispatchBoardBlockEntities {
    private DispatchBoardBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DynamicVehiclesMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DispatchBoardBlockEntity>> DISPATCH_BOARD =
            BLOCK_ENTITY_TYPES.register("dispatch_board",
                    () -> BlockEntityType.Builder.of(DispatchBoardBlockEntity::new, DispatcherBlocks.DISPATCH_BOARD.get()).build(null));
}
