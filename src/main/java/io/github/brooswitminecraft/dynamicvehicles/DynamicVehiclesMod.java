package io.github.brooswitminecraft.dynamicvehicles;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

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
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatchBoardBlockEntities;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherBlocks;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherInteractionHandler;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherOfferAcceptPayload;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherOfferMenus;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherOfferScreen;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherPoiTypes;
import io.github.brooswitminecraft.dynamicvehicles.delivery.DispatcherProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderPoiTypes;
import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderTradeHandler;
import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderCampfireHandler;
import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderPassiveSettlementHandler;
import io.github.brooswitminecraft.dynamicvehicles.delivery.VillagerNoonProgressionHandler;
import io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderBlocks;
import io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderHudPayload;
import io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderInteractionHandler;
import io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderPoiTypes;
import io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderProfession;
import io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderTickHandler;

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

    /** MINECRAFT-380: a watercraft, a rotor-powered aircraft and a fixed-wing aircraft. None of the three
     * share CarEntity's Sable rigid body or wheel physics -- see SimpleVehicleEntity. */
    public static final DeferredHolder<EntityType<?>, EntityType<MotorboatEntity>> MOTORBOAT = ENTITIES.register(VehicleIds.MOTORBOAT,
            () -> EntityType.Builder.<MotorboatEntity>of(MotorboatEntity::new, MobCategory.MISC)
                    .sized((float) (2 * MotorboatEntity.halfWidth()), (float) (2 * MotorboatEntity.halfHeight()))
                    .clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, VehicleIds.MOTORBOAT).toString()));
    public static final DeferredItem<VehicleSpawnItem<MotorboatEntity>> MOTORBOAT_ITEM = ITEMS.register(VehicleIds.MOTORBOAT,
            () -> new VehicleSpawnItem<>(new Item.Properties().stacksTo(1), MOTORBOAT, 0.5));
    public static final DeferredHolder<EntityType<?>, EntityType<ChopperEntity>> CHOPPER = ENTITIES.register(VehicleIds.CHOPPER,
            () -> EntityType.Builder.<ChopperEntity>of(ChopperEntity::new, MobCategory.MISC)
                    .sized((float) (2 * ChopperEntity.halfWidth()), (float) (2 * ChopperEntity.halfHeight()))
                    .clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, VehicleIds.CHOPPER).toString()));
    public static final DeferredItem<VehicleSpawnItem<ChopperEntity>> CHOPPER_ITEM = ITEMS.register(VehicleIds.CHOPPER,
            () -> new VehicleSpawnItem<>(new Item.Properties().stacksTo(1), CHOPPER, 0.5));
    public static final DeferredHolder<EntityType<?>, EntityType<BiplaneEntity>> BIPLANE = ENTITIES.register(VehicleIds.BIPLANE,
            () -> EntityType.Builder.<BiplaneEntity>of(BiplaneEntity::new, MobCategory.MISC)
                    .sized((float) (2 * BiplaneEntity.halfWidth()), (float) (2 * BiplaneEntity.halfHeight()))
                    .clientTrackingRange(10).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, VehicleIds.BIPLANE).toString()));
    public static final DeferredItem<VehicleSpawnItem<BiplaneEntity>> BIPLANE_ITEM = ITEMS.register(VehicleIds.BIPLANE,
            () -> new VehicleSpawnItem<>(new Item.Properties().stacksTo(1), BIPLANE, 0.5));

    public DynamicVehiclesMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, CarConfig.SPEC);
        // Explicit file name: the default ("dynamicvehicles-server.toml") is already claimed by
        // CarConfig.SPEC above, and ConfigTracker throws on a second SERVER config reusing it.
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, DispatcherOfferConfig.SPEC, "dynamicvehicles-dispatcher-server.toml");
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, PathfinderServerConfig.SPEC, "dynamicvehicles-pathfinder-server.toml");
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        ModSounds.SOUNDS.register(modEventBus);
        ENTITIES.register(modEventBus);
        ITEMS.register(modEventBus);
        DispatcherBlocks.BLOCKS.register(modEventBus);
        DispatchBoardBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        DispatcherPoiTypes.POI_TYPES.register(modEventBus);
        DispatcherProfession.PROFESSIONS.register(modEventBus);
        BuilderPoiTypes.POI_TYPES.register(modEventBus);
        BuilderProfession.PROFESSIONS.register(modEventBus);
        PathfinderBlocks.BLOCKS.register(modEventBus);
        PathfinderPoiTypes.POI_TYPES.register(modEventBus);
        PathfinderProfession.PROFESSIONS.register(modEventBus);
        DispatcherOfferMenus.MENUS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) -> LightLedger.sweep(e.getServer()));
        // MINECRAFT-443: per-car config files are read once, here, at server start -- not re-read while
        // the server runs (see VehicleSpecOverrides' own javadoc for why that is a deliberate scope
        // boundary rather than an oversight). ServerAboutToStartEvent specifically, not the later
        // ServerStartingEvent: it fires before any level is loaded, so the override table is populated
        // before a single saved CarEntity can be deserialized from world data and read it.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerAboutToStartEvent e) ->
                VehicleSpecOverrides.load(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()));
        NeoForge.EVENT_BUS.addListener(DispatcherInteractionHandler::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(BuilderTradeHandler::registerTrades);
        NeoForge.EVENT_BUS.addListener(BuilderTradeHandler::onTrade);
        NeoForge.EVENT_BUS.addListener(BuilderCampfireHandler::onLevelTick);
        NeoForge.EVENT_BUS.addListener(BuilderPassiveSettlementHandler::onLevelTick);
        NeoForge.EVENT_BUS.addListener(VillagerNoonProgressionHandler::onLevelTick);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onServerTick);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(io.github.brooswitminecraft.dynamicvehicles.delivery.PillagerAmbushHandler::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(io.github.brooswitminecraft.dynamicvehicles.delivery.PillagerAmbushHandler::onEntityLeaveLevel);
        NeoForge.EVENT_BUS.addListener(PathfinderInteractionHandler::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(PathfinderTickHandler::onServerTick);
        NeoForge.EVENT_BUS.addListener(PathfinderTickHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PathfinderTickHandler::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(PathfinderTickHandler::onLivingDeath);
    }

    private void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(LightsTogglePayload.TYPE, LightsTogglePayload.STREAM_CODEC, LightsTogglePayload::handle);
        registrar.playToServer(WheelPaddlesPayload.TYPE, WheelPaddlesPayload.STREAM_CODEC, WheelPaddlesPayload::handle);
        registrar.playToServer(GearTogglePayload.TYPE, GearTogglePayload.STREAM_CODEC, GearTogglePayload::handle);
        registrar.playToServer(GearSelectPayload.TYPE, GearSelectPayload.STREAM_CODEC, GearSelectPayload::handle);
        registrar.playToServer(RadioTogglePayload.TYPE, RadioTogglePayload.STREAM_CODEC, RadioTogglePayload::handle);
        registrar.playToServer(DispatcherOfferAcceptPayload.TYPE, DispatcherOfferAcceptPayload.STREAM_CODEC, DispatcherOfferAcceptPayload::handle);
        registrar.playToClient(DeliveryHudPayload.TYPE, DeliveryHudPayload.STREAM_CODEC, DeliveryHudPayload::handle);
        registrar.playToClient(PathfinderHudPayload.TYPE, PathfinderHudPayload.STREAM_CODEC, PathfinderHudPayload::handle);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Dynamic Vehicles loaded");
    }

    private void registerCommands(RegisterCommandsEvent event) {
        // /dtcar debug on|off: draws each car's physics box (red) and where its suspension rays hit the ground (green).
        var dtcar = Commands.literal("dtcar").requires(source -> source.hasPermission(2));
        dtcar.then(Commands.literal("debug")
                .then(Commands.literal("on").executes(context -> {
                    CarDebug.enabled = true;
                    context.getSource().sendSuccess(() -> Component.literal("car debug overlay on"), true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(context -> {
                    CarDebug.enabled = false;
                    context.getSource().sendSuccess(() -> Component.literal("car debug overlay off"), true);
                    return 1;
                })));
                // Per-instance rather than type-wide tuning: the command only changes the car the player is
                // currently driving, and CarEntity persists that state in the entity's own NBT.
        dtcar.then(Commands.literal("tune")
                .then(Commands.argument("setting", StringArgumentType.word())
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                .executes(context -> tuneDrivenVehicle(context,
                                        StringArgumentType.getString(context, "setting"),
                                        DoubleArgumentType.getDouble(context, "value"))))));
        event.getDispatcher().register(dtcar);
        // MINECRAFT-397: /dvflat - there-and-back to the separate superflat dimension, see FlatGarageCommand.
        event.getDispatcher().register(Commands.literal("dvflat").executes(FlatGarageCommand::execute));
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

        // Review follow-up: route selection was missing entirely - right-click always
        // auto-started the villager's current maximum unlocked level, with no way to run an
        // earlier, already-unlocked circuit again. No elaborate UI is required for this: a plain
        // player command (no permission requirement, unlike the debug commands above),
        // server-validated against the nearest Pathfinder villager's actual unlocked level.
        event.getDispatcher().register(Commands.literal("pathfinder")
                .then(Commands.literal("start")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 5))
                                .executes(context -> startPathfinderAtLevel(context, IntegerArgumentType.getInteger(context, "level"))))));
    }

    private static int startPathfinderAtLevel(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
            int level) {
        if (!(context.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal("Only a player can start a Pathfinder circuit."));
            return 0;
        }
        var villager = io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderAttempts.nearestPathfinderVillager(player);
        if (villager.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No Pathfinder villager is nearby."));
            return 0;
        }
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return 0;
        }
        io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderAttempts.tryStart(
                player, serverLevel, villager.get(), io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderLevel.values()[level - 1]);
        return 1;
    }

    private static int tuneDrivenVehicle(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
            String setting, double value) {
        net.minecraft.world.entity.Entity actor = context.getSource().getEntity();
        if (!(actor instanceof net.minecraft.world.entity.player.Player player)
                || !(player.getVehicle() instanceof CarEntity car)
                || car.getControllingPassenger() != player) {
            context.getSource().sendFailure(Component.literal("You must be driving the vehicle you want to tune."));
            return 0;
        }
        if (!car.setVehicleTuning(setting, value)) {
            context.getSource().sendFailure(Component.literal("Unknown tuning. Use lsd_lock_percent, front_torque_split, "
                    + "vectoring_gain, camber_deg, or toe_deg."));
            return 0;
        }
        VehicleSpec.VehicleTuning tuned = car.vehicleTuning();
        context.getSource().sendSuccess(() -> Component.literal("Updated this vehicle: " + setting + " = "
                + switch (setting) {
                    case "lsd_lock_percent" -> tuned.lsdLockPercent();
                    case "front_torque_split" -> tuned.frontTorqueSplit();
                    case "vectoring_gain" -> tuned.vectoringGain();
                    case "camber_deg" -> tuned.camberDeg();
                    default -> tuned.toeDeg();
                }), true);
        return 1;
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
            event.accept(MOTORBOAT_ITEM);
            event.accept(CHOPPER_ITEM);
            event.accept(BIPLANE_ITEM);
            event.accept(DispatcherBlocks.DISPATCH_BOARD_ITEM);
            event.accept(PathfinderBlocks.ROUTE_BOARD_ITEM);
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
            event.registerEntityRenderer(MOTORBOAT.get(), MotorboatRenderer::new);
            event.registerEntityRenderer(CHOPPER.get(), ChopperRenderer::new);
            event.registerEntityRenderer(BIPLANE.get(), BiplaneRenderer::new);
        }

        @SubscribeEvent
        public static void registerScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
            event.register(DispatcherOfferMenus.DISPATCHER_OFFER.get(), DispatcherOfferScreen::new);
        }

        @SubscribeEvent
        public static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "delivery_hud"),
                    new io.github.brooswitminecraft.dynamicvehicles.delivery.ContractHudOverlay());
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "delivery_waypoint"),
                    new io.github.brooswitminecraft.dynamicvehicles.delivery.ContractWaypointOverlay());
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "pathfinder_hud"),
                    new io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderHudOverlay());
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "pathfinder_waypoint"),
                    new io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderWaypointOverlay());
        }
    }
}
