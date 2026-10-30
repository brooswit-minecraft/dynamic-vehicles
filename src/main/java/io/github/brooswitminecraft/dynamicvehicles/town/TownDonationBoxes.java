package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TownDonationBoxes {
    private TownDonationBoxes() {}
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DynamicVehiclesMod.MODID);
    public static final DeferredBlock<TownDonationBoxBlock> DONATION_BOX = BLOCKS.registerBlock("town_donation_box", TownDonationBoxBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD).pushReaction(PushReaction.BLOCK));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> DONATION_BOX_ITEM = DynamicVehiclesMod.ITEMS.registerSimpleBlockItem(DONATION_BOX);
}
