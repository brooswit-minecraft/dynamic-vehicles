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

import io.github.brooswitminecraft.dynamicvehicles.delivery.ContractTickHandler;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DeliveryHudPayload;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherBlocks;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherInteractionHandler;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherOfferAcceptPayload;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherOfferMenus;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherOfferScreen;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherPoiTypes;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherProfession;

/** Entry point and registry for the first vehicle, a 4-wheel car (MINECRAFT-63). */
@Mod(DynamicVehiclesMod.MODID)
public class DynamicVehiclesMod {
    public static final String MODID = "dynamicvehicles";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> CAR = ENTITIES.register(VehicleIds.CAR,
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(1.9f, 1.0f).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, VehicleIds.CAR).toString()));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> TRUCK = ENTITIES.register(VehicleIds.TRUCK,
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.TRUCK.width(), VehicleSpec.TRUCK.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, VehicleIds.TRUCK).toString()));
    public static final DeferredItem<CarItem> TRUCK_ITEM = ITEMS.register(VehicleIds.TRUCK, () -> new CarItem(new Item.Properties().stacksTo(1), TRUCK, 0.5));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> TROPHY = ENTITIES.register(VehicleIds.TROPHY_TRUCK,
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.TROPHY.width(), VehicleSpec.TROPHY.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, VehicleIds.TROPHY_TRUCK).toString()));
    public static final DeferredItem<CarItem> TROPHY_ITEM = ITEMS.register(VehicleIds.TROPHY_TRUCK, () -> new CarItem(new Item.Properties().stacksTo(1), TROPHY, 0.9));
    public static final DeferredItem<CarItem> CAR_ITEM = ITEMS.register(VehicleIds.CAR, () -> new CarItem(new Item.Properties().stacksTo(1), CAR, 0.0));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> DRIFT = ENTITIES.register(VehicleIds.DRIFT_CAR,
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.DRIFT.width(), VehicleSpec.DRIFT.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, VehicleIds.DRIFT_CAR).toString()));
    public static final DeferredItem<CarItem> DRIFT_ITEM = ITEMS.register(VehicleIds.DRIFT_CAR, () -> new CarItem(new Item.Properties().stacksTo(1), DRIFT, 0.0));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> MUSCLE = ENTITIES.register("muscle_car",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.MUSCLE.width(), VehicleSpec.MUSCLE.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "muscle_car").toString()));
    public static final DeferredItem<CarItem> MUSCLE_ITEM = ITEMS.register("muscle_car", () -> new CarItem(new Item.Properties().stacksTo(1), MUSCLE, 0.0));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> ROCK_CRAWLER = ENTITIES.register("rock_crawler",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.ROCK_CRAWLER.width(), VehicleSpec.ROCK_CRAWLER.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "rock_crawler").toString()));
    public static final DeferredItem<CarItem> ROCK_CRAWLER_ITEM = ITEMS.register("rock_crawler", () -> new CarItem(new Item.Properties().stacksTo(1), ROCK_CRAWLER, 1.0));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> MONSTER_TRUCK = ENTITIES.register("monster_truck",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.MONSTER_TRUCK.width(), VehicleSpec.MONSTER_TRUCK.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "monster_truck").toString()));
    public static final DeferredItem<CarItem> MONSTER_TRUCK_ITEM = ITEMS.register("monster_truck", () -> new CarItem(new Item.Properties().stacksTo(1), MONSTER_TRUCK, 1.2));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> INDY = ENTITIES.register("indy_car",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.INDY.width(), VehicleSpec.INDY.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "indy_car").toString()));
    public static final DeferredItem<CarItem> INDY_ITEM = ITEMS.register("indy_car", () -> new CarItem(new Item.Properties().stacksTo(1), INDY, 0.0));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> BUS = ENTITIES.register("bus",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.BUS.width(), VehicleSpec.BUS.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "bus").toString()));
    public static final DeferredItem<CarItem> BUS_ITEM = ITEMS.register("bus", () -> new CarItem(new Item.Properties().stacksTo(1), BUS, 0.5));
    public static final DeferredHolder<EntityType<?>, EntityType<CarEntity>> CARGO_TRUCK = ENTITIES.register("cargo_truck",
            () -> EntityType.Builder.<CarEntity>of(CarEntity::new, MobCategory.MISC)
                    .sized(VehicleSpec.CARGO_TRUCK.width(), VehicleSpec.CARGO_TRUCK.height()).clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "cargo_truck").toString()));
    public static final DeferredItem<CarItem> CARGO_TRUCK_ITEM = ITEMS.register("cargo_truck", () -> new CarItem(new Item.Properties().stacksTo(1), CARGO_TRUCK, 0.6));

    public DynamicVehiclesMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, CarConfig.SPEC);
        // Explicit file name: the default ("dynamicvehicles-server.toml") is already claimed by
        // CarConfig.SPEC above, and ConfigTracker throws on a second SERVER config reusing it.
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, DispatcherOfferConfig.SPEC, "dynamicvehicles-dispatcher-server.toml");
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        ModSounds.SOUNDS.register(modEventBus);
        ENTITIES.register(modEventBus);
        ITEMS.register(modEventBus);
        DispatcherBlocks.BLOCKS.register(modEventBus);
        DispatcherPoiTypes.POI_TYPES.register(modEventBus);
        DispatcherProfession.PROFESSIONS.register(modEventBus);
        DispatcherOfferMenus.MENUS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) -> LightLedger.sweep(e.getServer()));
        NeoForge.EVENT_BUS.addListener(DispatcherInteractionHandler::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onServerTick);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(io.github.brooswitminecraft.dynamicvehicles.delivery.PillagerAmbushHandler::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(io.github.brooswitminecraft.dynamicvehicles.delivery.PillagerAmbushHandler::onEntityLeaveLevel);
    }

    private void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(LightsTogglePayload.TYPE, LightsTogglePayload.STREAM_CODEC, LightsTogglePayload::handle);
        registrar.playToServer(WheelPaddlesPayload.TYPE, WheelPaddlesPayload.STREAM_CODEC, WheelPaddlesPayload::handle);
        registrar.playToServer(GearTogglePayload.TYPE, GearTogglePayload.STREAM_CODEC, GearTogglePayload::handle);
        registrar.playToServer(DispatcherOfferAcceptPayload.TYPE, DispatcherOfferAcceptPayload.STREAM_CODEC, DispatcherOfferAcceptPayload::handle);
        registrar.playToClient(DeliveryHudPayload.TYPE, DeliveryHudPayload.STREAM_CODEC, DeliveryHudPayload::handle);
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
            event.accept(DRIFT_ITEM);
            event.accept(MUSCLE_ITEM);
            event.accept(ROCK_CRAWLER_ITEM);
            event.accept(MONSTER_TRUCK_ITEM);
            event.accept(INDY_ITEM);
            event.accept(BUS_ITEM);
            event.accept(CARGO_TRUCK_ITEM);
            event.accept(DispatcherBlocks.DISPATCH_BOARD_ITEM);
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
            event.registerEntityRenderer(DRIFT.get(), CarRenderer::new);
            event.registerEntityRenderer(MUSCLE.get(), CarRenderer::new);
            event.registerEntityRenderer(ROCK_CRAWLER.get(), CarRenderer::new);
            event.registerEntityRenderer(MONSTER_TRUCK.get(), CarRenderer::new);
            event.registerEntityRenderer(INDY.get(), CarRenderer::new);
            event.registerEntityRenderer(BUS.get(), CarRenderer::new);
            event.registerEntityRenderer(CARGO_TRUCK.get(), CarRenderer::new);
        }

        @SubscribeEvent
        public static void registerScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
            event.register(DispatcherOfferMenus.DISPATCHER_OFFER.get(), DispatcherOfferScreen::new);
        }

        @SubscribeEvent
        public static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "delivery_hud"),
                    new io.github.brooswitminecraft.dynamicvehicles.delivery.ContractHudOverlay());
        }
    }
}
