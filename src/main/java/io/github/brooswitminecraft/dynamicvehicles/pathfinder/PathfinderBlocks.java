package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;

/**
 * The Pathfinder profession's own job-site block: a dedicated "route board",
 * approved by John on 2026-10-18 (see ticket MINECRAFT-379). A new,
 * survival-craftable block whose states exist nowhere else, so {@link
 * PathfinderPoiTypes#ROUTE_BOARD} can claim them without colliding with any
 * other PoiType — the same reasoning {@code delivery.DispatcherBlocks}
 * documents for its own dedicated block (MINECRAFT-196): sharing a vanilla
 * block (e.g. the Bell) as a POI would let an unrelated villager take this
 * profession, and vice versa.
 *
 * <p>Plain {@link Block} for now (no block entity, no right-click
 * interaction yet) — route selection/start UI is separate follow-up work,
 * not required just to let a villager acquire the job site.
 */
public final class PathfinderBlocks {
    private PathfinderBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DynamicVehiclesMod.MODID);

    public static final DeferredBlock<Block> ROUTE_BOARD = BLOCKS.registerBlock("route_board", Block::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD));

    public static final DeferredItem<net.minecraft.world.item.BlockItem> ROUTE_BOARD_ITEM =
            DynamicVehiclesMod.ITEMS.registerSimpleBlockItem(ROUTE_BOARD);
}
