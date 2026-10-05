package io.github.brooswitminecraft.dynamicvehicles;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Scaffold-only entry point. Deliberately empty: the car entity, driving
 * physics and surface handling ship in later MINECRAFT-63 tickets.
 */
@Mod(DynamicVehiclesMod.MODID)
public class DynamicVehiclesMod {
    public static final String MODID = "dynamicvehicles";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicVehiclesMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Dynamic Vehicles scaffold loaded");
    }
}
