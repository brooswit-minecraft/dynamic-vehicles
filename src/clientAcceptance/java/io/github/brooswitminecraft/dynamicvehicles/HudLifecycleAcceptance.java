package io.github.brooswitminecraft.dynamicvehicles;

import io.github.brooswitminecraft.dynamicvehicles.hud.DrivingHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Test-only lifecycle assertions in the connected disposable client. */
final class HudLifecycleAcceptance {
    static boolean seatProbe, deadMountedProbe, nether, returned, death, disconnected;

    @SuppressWarnings("unchecked")
    static void mountedProbe(Minecraft mc, CarEntity car) throws Exception {
        if(seatProbe) return;
        var field=CarEntity.class.getDeclaredField("seats"); field.setAccessible(true);
        var seats=(SeatAssignment<net.minecraft.world.entity.Entity>)field.get(car);
        int original=seats.indexOf(mc.player);
        try {
            seats.remove(mc.player);
            if(!seats.restore(mc.player,1)) throw new IllegalStateException("test passenger seat unavailable");
            checkLayers(mc,false);
            seatProbe=true;
        } finally { seats.remove(mc.player); seats.restore(mc.player,original); }
        checkLayers(mc,true);
        float health=mc.player.getHealth();
        try {
            mc.player.setHealth(0);
            checkLayers(mc,false);
            deadMountedProbe=true;
        } finally { mc.player.setHealth(health); }
        checkLayers(mc,true);
        DynamicVehiclesMod.LOGGER.info("CLIENT_ACCEPTANCE_HUD_LOCAL_SEAT_AND_DEAD_MOUNT_PASS");
    }

    static void tick(Minecraft mc) {
        if(mc.player!=null && mc.level!=null) {
            if(mc.level.dimension()==Level.NETHER) { checkLayers(mc,false); nether=true; }
            if(nether && mc.level.dimension()==Level.OVERWORLD && mc.player.getVehicle()==null) {
                checkLayers(mc,false); returned=true;
            }
            if(mc.player.isDeadOrDying()) { checkLayers(mc,false); death=true; }
        } else if(death && returned && mc.getConnection()==null) {
            checkLayers(mc,false); disconnected=true;
        }
    }

    static boolean complete() { return seatProbe && deadMountedProbe && nether && returned && death && disconnected; }

    private static void checkLayers(Minecraft mc,boolean expected) {
        int left=mc.gui.leftHeight, right=mc.gui.rightHeight;
        try {
            mc.gui.leftHeight=mc.gui.rightHeight=39;
            for(var layer : new net.minecraft.resources.ResourceLocation[]{VanillaGuiLayers.PLAYER_HEALTH,
                    VanillaGuiLayers.FOOD_LEVEL,VanillaGuiLayers.ARMOR_LEVEL,VanillaGuiLayers.EXPERIENCE_BAR,
                    VanillaGuiLayers.EXPERIENCE_LEVEL,VanillaGuiLayers.VEHICLE_HEALTH,VanillaGuiLayers.SELECTED_ITEM_NAME}) {
                var event=new RenderGuiLayerEvent.Pre(null,null,layer,null);
                DrivingHudOverlay.replacePlayerLayers(event);
                if(event.isCanceled()!=(expected && !layer.equals(VanillaGuiLayers.SELECTED_ITEM_NAME)))
                    throw new IllegalStateException("HUD lifecycle cancellation mismatch: "+layer);
            }
            if(!expected && (mc.gui.leftHeight!=39 || mc.gui.rightHeight!=39))
                throw new IllegalStateException("HUD lifecycle leaked row reservation");
        } finally { mc.gui.leftHeight=left; mc.gui.rightHeight=right; }
    }
}
