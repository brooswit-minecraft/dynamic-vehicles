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
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> TRUCK = ENTITIES.register("truck",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.TRUCK.width(), VehicleSpec.TRUCK.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "truck").toString()));
    public static final DeferredItem<CarItem> TRUCK_ITEM = ITEMS.register("truck", () -> new CarItem(new Item.Properties().stacksTo(1), TRUCK, 0.5));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> TROPHY = ENTITIES.register("trophy_truck",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.TROPHY.width(), VehicleSpec.TROPHY.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "trophy_truck").toString()));
    public static final DeferredItem<CarItem> TROPHY_ITEM = ITEMS.register("trophy_truck", () -> new CarItem(new Item.Properties().stacksTo(1), TROPHY, 0.9));
    public static final DeferredItem<CarItem> CAR_ITEM = ITEMS.register("car", () -> new CarItem(new Item.Properties().stacksTo(1), CAR, 0.0));

    public DynamicVehiclesMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, CarConfig.SPEC);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        ModSounds.SOUNDS.register(modEventBus);
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
        // /dtcar debug on|off: draws each car's physics box (red) and where its suspension rays hit the ground (green).
        event.getDispatcher().register(Commands.literal("dtcar").requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                        .then(Commands.literal("on").executes(context -> {
                            CarDebug.enabled = true;
                            context.getSource().sendSuccess(() -> Component.literal("car debug overlay on"), true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            CarDebug.enabled = false;
                            context.getSource().sendSuccess(() -> Component.literal("car debug overlay off"), true);
                            return 1;
                        }))));
        // Debug: /dvdrive <throttle> <steer> <ticks> drives every loaded car as if a rider held those inputs.
        event.getDispatcher().register(Commands.literal("dvdrive").requires(source -> source.hasPermission(2))
                .then(Commands.argument("throttle", DoubleArgumentType.doubleArg(-1, 1))
                        .then(Commands.argument("steer", DoubleArgumentType.doubleArg(-1, 1))
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 24000)).executes(context -> {
                                    int cars = 0;
                                    for (net.minecraft.world.entity.Entity entity : context.getSource().getLevel().getAllEntities()) {
                                        if (entity instanceof CarEntity car) {
                                            car.forceDrive(DoubleArgumentType.getDouble(context, "throttle"),
                                                    DoubleArgumentType.getDouble(context, "steer"), IntegerArgumentType.getInteger(context, "ticks"));
                                            cars++;
                                        }
                                    }
                                    int count = cars;
                                    context.getSource().sendSuccess(() -> Component.literal("driving " + count + " car(s)"), true);
                                    return count;
                                })))));
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
            event.accept(TRUCK_ITEM);
            event.accept(TROPHY_ITEM);
        }
    }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {}

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(CAR.get(), CarRenderer::new);
            event.registerEntityRenderer(TRUCK.get(), CarRenderer::new);
            event.registerEntityRenderer(TROPHY.get(), CarRenderer::new);
        }
    }
}
