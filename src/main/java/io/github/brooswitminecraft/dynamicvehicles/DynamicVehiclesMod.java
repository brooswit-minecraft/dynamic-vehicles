package io.github.brooswitminecraft.dynamicvehicles;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entry point and registry for the first vehicle, a 4-wheel car (MINECRAFT-63). */
@Mod(DynamicVehiclesMod.MODID)
public class DynamicVehiclesMod {
    public static final String MODID = "dynamicvehicles";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> CAR = ENTITIES.register("car",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(1.9f, 1.0f).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "car").toString()));
    public static final DeferredItem<CarItem> CAR_ITEM = ITEMS.register("car", () -> new CarItem(new Item.Properties().stacksTo(1)));

    public DynamicVehiclesMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, CarConfig.SPEC);
        ENTITIES.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Dynamic Vehicles loaded");
    }

    /** Debug entry point: /dvspin <slipSpeed> <ticks> makes every loaded car's wheels report that slip, to exercise terrain wear. */
    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dvspin").requires(source -> source.hasPermission(2))
                .then(Commands.argument("slip", DoubleArgumentType.doubleArg(0))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 24000)).executes(context -> {
                            int cars = 0;
                            for (net.minecraft.world.entity.Entity entity : context.getSource().getLevel().getAllEntities()) {
                                if (entity instanceof CarEntity car) {
                                    car.forceSlip(DoubleArgumentType.getDouble(context, "slip"), IntegerArgumentType.getInteger(context, "ticks"));
                                    cars++;
                                }
                            }
                            int count = cars;
                            context.getSource().sendSuccess(() -> Component.literal("forced slip on " + count + " car(s)"), true);
                            return count;
                        }))));
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CAR_ITEM);
        }
    }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {}

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(CAR.get(), CarRenderer::new);
        }
    }
}
