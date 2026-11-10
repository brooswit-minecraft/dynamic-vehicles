package io.github.brooswitminecraft.dynamicvehicles;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout;
import io.github.brooswitminecraft.dynamicvehicles.hud.Rect;

/**
 * MINECRAFT-493 review (comments 52327 onward, decision 52345, 52552): hosted, exact-head, real
 * NeoForge class-loading proof that Courier Craft's {@code ContractWaypointOverlay} genuinely
 * shares THIS mod's real {@code hud.MissionWaypointLayout} state with Pathfinder's own waypoint
 * when both mods are actually coinstalled and mod-loaded together -- not a plain-JUnit proxy.
 *
 * <p>Runs only inside the existing {@code -PclientAcceptance} harness, and only when Courier
 * Craft is actually loaded ({@link ModList#isLoaded}) -- a no-op otherwise, so the existing
 * standalone client-acceptance job (no Courier Craft jar on the classpath) is entirely
 * unaffected. See {@code tools/client-smoke/coinstalled_acceptance.py} for the CI job that
 * builds Courier Craft's exact paired-PR head and adds it to this same run.
 *
 * <p>Simulates an active Dispatcher contract and an active Pathfinder attempt pointed at the
 * same destination (so their screen-projected markers would coincide without coordination), then
 * -- gated on actually-rendered {@link RenderGuiEvent.Post} frames, NOT a fixed tick count (a
 * real first attempt here showed 5 client ticks can elapse in ~50ms with zero intervening render
 * frames under this harness's software-rendered xvfb load, so a tick-based wait cannot tell
 * "the bridge never ran" apart from "no frame has rendered yet since the state changed") --
 * reads {@code MissionWaypointLayout}'s own private {@code occupied} state via reflection (public
 * API only exposes {@code reserve}/{@code clear}, not a peek) to verify: both missions actually
 * reserved (Courier Craft's bridge really called through), the two reservations don't overlap,
 * and clearing the simulated Dispatcher contract releases its slot once an actual subsequent
 * frame has rendered. Edge-placement/shift-direction math itself is already covered by {@code
 * hud.MissionWaypointLayoutTest} and {@code WaypointMarkerLayoutTest} (pure-function unit tests
 * with controlled inputs); this class's job is proving the live, dual-mod WIRING reaches that
 * already-tested logic for real, not re-proving the math.
 */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID, value = Dist.CLIENT)
public final class CourierCraftAcceptance {
    private static final String COURIER_CRAFT_MODID = "couriercraft";
    /** Generous: real frames observed well under 100 in this harness's own baseline runs. */
    private static final int MAX_FRAMES_TO_WAIT = 600;

    private enum Stage { START, SETUP_DONE, RESERVED, CLEARED, DONE }

    private static Stage stage = Stage.START;
    private static int ticksSinceStart = -1;
    private static long renderedFrames;
    private static long framesAtLastTransition;

    private CourierCraftAcceptance() {}

    private static Object get(Class<?> type, String name, Object instance) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void fail(Exception failure) {
        DynamicVehiclesMod.LOGGER.error("COURIER_CRAFT_ACCEPTANCE_FAIL", failure);
        try {
            Files.writeString(Path.of("courier-craft-acceptance-failure.txt"), failure.toString());
        } catch (Exception ignored) {
            // best-effort diagnostic only
        }
        Minecraft.getInstance().stop();
    }

    /** Counts actually-rendered frames, so the checks below can wait for a real intervening
     *  frame instead of a fixed tick count (MINECRAFT-493 review comment 52552). */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        renderedFrames++;
    }

    private static Rect dispatcherSlot() throws Exception {
        Rect[] occupied = (Rect[]) get(MissionWaypointLayout.class, "occupied", null);
        return occupied[MissionWaypointLayout.Mission.DISPATCHER.ordinal()];
    }

    private static Rect pathfinderSlot() throws Exception {
        Rect[] occupied = (Rect[]) get(MissionWaypointLayout.class, "occupied", null);
        return occupied[MissionWaypointLayout.Mission.PATHFINDER.ordinal()];
    }

    /** Frames rendered since the last stage transition -- "at least one real intervening frame". */
    private static long framesSinceTransition() {
        return renderedFrames - framesAtLastTransition;
    }

    private static void transitionTo(Stage next) {
        stage = next;
        framesAtLastTransition = renderedFrames;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (stage == Stage.DONE || !ModList.get().isLoaded(COURIER_CRAFT_MODID)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        try {
            ticksSinceStart++;
            double destX = minecraft.player.getX() + 40;
            double destY = minecraft.player.getY();
            double destZ = minecraft.player.getZ();

            if (stage == Stage.START && ticksSinceStart == 5) {
                // A real NeoForge mod file for Courier Craft is actually on this run's
                // classpath and FML reports it loaded -- the class-loading half of the claim.
                check(ModList.get().isLoaded(COURIER_CRAFT_MODID), "Courier Craft did not report as loaded");

                // Both missions pointed at the same destination: their screen projections would
                // coincide without MissionWaypointLayout's coordination.
                injectDispatcherContract(true, destX, destY, destZ);
                injectPathfinderAttempt(true, destX, destY, destZ);
                transitionTo(Stage.SETUP_DONE);
            }

            if (stage == Stage.SETUP_DONE && framesSinceTransition() >= 3) {
                Rect dispatcherBounds = dispatcherSlot();
                Rect pathfinderBounds = pathfinderSlot();
                check(dispatcherBounds != null,
                        "Dispatcher never reserved a slot -- Courier Craft's ModList-gated bridge did not run");
                check(pathfinderBounds != null, "Pathfinder never reserved a slot");
                check(!dispatcherBounds.overlaps(pathfinderBounds),
                        "Dispatcher (via Courier Craft's real bridge) and Pathfinder markers overlap: "
                                + dispatcherBounds + " vs " + pathfinderBounds);
                DynamicVehiclesMod.LOGGER.info("COURIER_CRAFT_ACCEPTANCE_SHARED_RESERVATION dispatcher={} pathfinder={}",
                        dispatcherBounds, pathfinderBounds);

                // Clear the simulated Dispatcher contract; Courier Craft's overlay must release
                // its slot through the same real bridge on a subsequent real frame.
                injectDispatcherContract(false, 0, 0, 0);
                transitionTo(Stage.RESERVED);
            }

            if (stage == Stage.RESERVED) {
                check(framesSinceTransition() <= MAX_FRAMES_TO_WAIT,
                        "no real frame released the Dispatcher slot within " + MAX_FRAMES_TO_WAIT
                                + " rendered frames after clearing the simulated contract");
                if (framesSinceTransition() >= 3 && dispatcherSlot() == null) {
                    DynamicVehiclesMod.LOGGER.info(
                            "COURIER_CRAFT_ACCEPTANCE_RELEASE_AFTER_CLEAR ok=true framesWaited={}",
                            framesSinceTransition());
                    injectPathfinderAttempt(false, 0, 0, 0);
                    transitionTo(Stage.CLEARED);
                }
            }

            if (stage == Stage.CLEARED && framesSinceTransition() >= 2) {
                Files.writeString(Path.of("courier-craft-acceptance-result.json"),
                        "{\"passed\":true,\"couriercraft_loaded\":true,\"shared_reservation_no_overlap\":true,\"dispatcher_release_on_clear\":true}");
                DynamicVehiclesMod.LOGGER.info("COURIER_CRAFT_ACCEPTANCE_PASS");
                transitionTo(Stage.DONE);
            }
        } catch (Exception failure) {
            fail(failure);
        }
    }

    /**
     * Injects a {@code DeliveryHudPayload} into Courier Craft's {@code ClientContractHud}
     * exactly as the server's own network handler would, via reflection -- the only way to drive
     * {@code ContractWaypointOverlay} from outside its own mod without a full server-side
     * contract-acceptance workflow, which this acceptance check does not need to also re-prove
     * (DispatcherGameTests already covers contract acceptance itself).
     */
    private static void injectDispatcherContract(boolean active, double x, double y, double z) throws Exception {
        Class<?> hudClass = Class.forName("io.github.brooswitminecraft.couriercraft.dispatcher.ClientContractHud");
        Class<?> payloadClass = Class.forName("io.github.brooswitminecraft.couriercraft.dispatcher.DeliveryHudPayload");
        Object payload = payloadClass
                .getDeclaredConstructor(boolean.class, String.class, double.class, double.class, double.class,
                        long.class, double.class, double.class, double.class)
                .newInstance(active, "minecraft:overworld", x, y, z, 999_999L, 0.5, 0.0, 1.0);
        Method update = hudClass.getDeclaredMethod("update", payloadClass);
        update.setAccessible(true);
        update.invoke(null, payload);
    }

    /** Same idea as {@link #injectDispatcherContract}, for Pathfinder's own client HUD holder. */
    private static void injectPathfinderAttempt(boolean active, double x, double y, double z) throws Exception {
        Class<?> hudClass = Class.forName("io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderClientHud");
        Class<?> payloadClass = Class.forName("io.github.brooswitminecraft.dynamicvehicles.pathfinder.PathfinderHudPayload");
        Object payload = payloadClass
                .getDeclaredConstructor(boolean.class, String.class, double.class, double.class, double.class,
                        int.class, int.class, long.class)
                .newInstance(active, "minecraft:overworld", x, y, z, 1, 3, 999_999L);
        Method update = hudClass.getDeclaredMethod("update", payloadClass);
        update.setAccessible(true);
        update.invoke(null, payload);
    }
}
