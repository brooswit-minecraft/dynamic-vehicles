package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TownDonationBoxEntities {
    private TownDonationBoxEntities() {}
    public static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DynamicVehiclesMod.MODID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TownDonationBoxEntity>> DONATION_BOX = TYPES.register("town_donation_box",
            () -> BlockEntityType.Builder.of(TownDonationBoxEntity::new, TownDonationBoxes.DONATION_BOX.get()).build(null));
}
