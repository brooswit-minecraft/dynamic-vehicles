package io.github.brooswitminecraft.dynamicvehicles;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Observes actual rendered frames and captures their framebuffer in the disposable client only. */
@EventBusSubscriber(modid=DynamicVehiclesMod.MODID,value=Dist.CLIENT)
public final class HudRenderAcceptance {
    private static final Set<ResourceLocation> GAUGES = Set.of(VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.EXPERIENCE_BAR,VanillaGuiLayers.FOOD_LEVEL,VanillaGuiLayers.ARMOR_LEVEL);
    private static final Set<ResourceLocation> seen = new HashSet<>();
    private static final List<Object> evidence = new ArrayList<>();
    private static boolean started, captured, exited, reentered;
    private static int stage, age, originalScale, originalSlot;
    private static GameType originalMode;
    private static ItemStack originalStack;

    static void tick(Minecraft mc, int mountedTicks, int carMounts) {
        if(!started && carMounts==1 && mountedTicks==100 && mc.player.getVehicle() instanceof CarEntity) {
            started=true;
            originalMode=mc.gameMode.getPlayerMode(); originalScale=mc.options.guiScale().get();
            originalSlot=mc.player.getInventory().selected;
            originalStack=mc.player.getInventory().getItem(0).copy();
            configure(mc);
        }
        if(!started || stage>=8) return;
        if(captured) {
            captured=false; age=0; stage++;
            if(stage<8) configure(mc);
            else {
                mc.gameMode.setLocalMode(originalMode);
                mc.options.guiScale().set(originalScale); mc.resizeDisplay();
                mc.player.getInventory().setItem(0,originalStack);
                mc.player.getInventory().selected=originalSlot;
            }
        }
        age++;
    }

    private static void configure(Minecraft mc) {
        mc.gameMode.setLocalMode(stage<4 ? GameType.SURVIVAL : GameType.CREATIVE);
        mc.options.guiScale().set(stage%4+1); mc.resizeDisplay();
        var item=new ItemStack(Items.STICK);
        item.set(DataComponents.CUSTOM_NAME,Component.literal("Long selected item crossing HUD — "+(stage<4?"survival":"creative")+" scale "+(stage%4+1)));
        mc.player.getInventory().setItem(0,item); mc.player.getInventory().selected=0;
    }

    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true)
    public static void layer(RenderGuiLayerEvent.Pre event) {
        var mc=Minecraft.getInstance();
        if(!started || mc.player==null || !GAUGES.contains(event.getName())) return;
        boolean driver=mc.player.getVehicle() instanceof CarEntity car && car.isRigidBody()
                && car.getControllingPassenger()==mc.player && !mc.options.hideGui && !mc.player.isSpectator();
        if(event.isCanceled()!=driver) throw new IllegalStateException("actual GUI layer cancellation/restoration failed: "+event.getName());
        seen.add(event.getName());
    }

    @SubscribeEvent
    public static void rendered(RenderGuiEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(!started || mc.player==null) return;
        try {
            if(stage<8 && age>=3 && !captured) {
                if(seen.size()!=4) throw new IllegalStateException("not all replacement layers were observed in actual render");
                capture(mc,"mounted-"+(stage<4?"survival":"creative")+"-scale-"+(stage%4+1));
                captured=true;
            } else if(stage==8 && !exited && mc.player.getVehicle()==null) {
                if(seen.size()!=4) throw new IllegalStateException("missing restored vanilla layers");
                capture(mc,"exited-vanilla"); exited=true;
            } else if(stage==8 && exited && !reentered && mc.player.getVehicle() instanceof CarEntity car && car.isRigidBody()) {
                if(seen.size()!=4) throw new IllegalStateException("missing reentered replacement layers");
                capture(mc,"reentered-driving"); reentered=true;
            }
        } catch(Exception failure) { throw new IllegalStateException("HUD rendered acceptance failed",failure); }
        finally { seen.clear(); }
    }

    private static void capture(Minecraft mc,String name) throws Exception {
        String file="client-acceptance-hud-"+name+".png";
        Screenshot.grab(mc.gameDirectory,file,mc.getMainRenderTarget(),message -> {});
        evidence.add(java.util.Map.of("file",file,"scenario",name,"guiWidth",mc.getWindow().getGuiScaledWidth(),
                "guiHeight",mc.getWindow().getGuiScaledHeight(),"requestedScale",mc.options.guiScale().get(),
                "actualLayerChecks",seen.size()));
        Files.writeString(Path.of("client-acceptance-hud-frames.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        DynamicVehiclesMod.LOGGER.info("CLIENT_ACCEPTANCE_HUD_FRAME {}",name);
    }

    static boolean complete() { return stage==8 && exited && reentered && evidence.size()==10; }
}
