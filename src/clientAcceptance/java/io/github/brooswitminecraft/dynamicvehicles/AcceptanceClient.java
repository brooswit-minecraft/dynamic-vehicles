package io.github.brooswitminecraft.dynamicvehicles;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/** Separate test JVM; simulation does not claim physical joystick or visual-quality acceptance. */
@EventBusSubscriber(modid=DynamicVehiclesMod.MODID,value=Dist.CLIENT)
public final class AcceptanceClient {
    private static int ticks, mountedTicks, carMounts;
    private static Object lastMount;
    private static boolean carInput, boatInput, boatCamera, dismounted, dust, exhaust, freeLook, disabled;
    private static float initialError;
    private static boolean recentered, hudMounted, hudExited, hudReentered;
    private static final Map<String, Probe> probes = new LinkedHashMap<>();
    private static Probe active;
    private static final class Probe {
        final String name;
        float lookYaw, lookPitch;
        boolean input, recentered, freeLook, disabled;
        Probe(String name) { this.name=name; }
        @Override public String toString() {
            return name+"[input="+input+",recenter="+recentered+",freeLook="+freeLook+",disabled="+disabled+"]";
        }
    }
    private static Object get(Class<?> type,String name,Object object) throws Exception {
        Field field=type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Class<?> type,String name,Object value) throws Exception {
        Field field=type.getDeclaredField(name); field.setAccessible(true); field.set(null,value);
    }
    private static void check(boolean condition,String message) {
        if(!condition) throw new IllegalStateException(message);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void inputBefore(MovementInputUpdateEvent event) {
        try {
            ClientConfig.WHEEL_ENABLED.set(true);
            ClientConfig.WHEEL_STEER_AXIS.set(0); ClientConfig.WHEEL_THROTTLE_AXIS.set(1);
            ClientConfig.WHEEL_BRAKE_AXIS.set(2);
            set(WheelInput.class,"device",0); set(WheelInput.class,"deviceName","acceptance simulated wheel");
            set(WheelInput.class,"detectedThrottleRest",1.0); set(WheelInput.class,"detectedBrakeRest",1.0);
            set(WheelInput.class,"axes",new float[]{.25f,-1,1});
        } catch(Exception failure) { fail(failure); }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void inputAfter(MovementInputUpdateEvent event) {
        var mount=event.getEntity().getVehicle();
        if(mount instanceof CarEntity) carInput |= event.getInput().forwardImpulse>.1f && Math.abs(event.getInput().leftImpulse)>.01;
        if(mount instanceof MotorboatEntity) boatInput |= event.getInput().forwardImpulse>.1f && Math.abs(event.getInput().leftImpulse)>.01;
        if(mount==lastMount && active!=null)
            active.input |= event.getInput().forwardImpulse>.1f && Math.abs(event.getInput().leftImpulse)>.01;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance(); if(mc.player==null || mc.level==null) return;
        try {
            ticks++;
            Object mount=mc.player.getVehicle();
            if(mount!=lastMount) {
                mountedTicks=0; lastMount=mount;
                if(mount instanceof CarEntity) carMounts++;
                active = mount instanceof CarEntity ? new Probe("car-"+carMounts)
                        : mount instanceof MotorboatEntity ? new Probe("boat") : null;
                if(active!=null) probes.put(active.name,active);
                if(mount==null && boatCamera) dismounted=true;
            }
            mountedTicks++;
            HudRenderAcceptance.tick(mc,mountedTicks,carMounts);
            if(mount instanceof DriverVehicleEntity vehicle && vehicle.isRigidBody()) {
                if(mountedTicks==5) {
                    var q=vehicle.renderOrientation(1);
                    float yaw=ChaseCameraMath.angles(q.x,q.y,q.z,q.w).yaw();
                    mc.player.setYRot(yaw+70); initialError=70;
                    active.lookYaw=mc.player.getYRot(); active.lookPitch=mc.player.getXRot();
                }
                if(mountedTicks==7) {
                    check((int)get(ChaseCamera.class,"idleTicks",null)<5,"free-look timer did not reset: "+active.name);
                    check(Math.abs(Mth.wrapDegrees(mc.player.getYRot()-active.lookYaw))<.001
                            && Math.abs(mc.player.getXRot()-active.lookPitch)<.001,
                            "camera modified player look during free-look: "+active.name);
                    active.freeLook=true; freeLook=true;
                }
                if(mountedTicks==70) {
                    var q=vehicle.renderOrientation(1);
                    float yaw=ChaseCameraMath.angles(q.x,q.y,q.z,q.w).yaw();
                    active.recentered = Math.abs(Mth.wrapDegrees(mc.player.getYRot()-yaw))<initialError/2;
                    check(active.recentered,"camera did not recenter: "+active.name);
                    recentered |= active.recentered;
                    if(mount instanceof MotorboatEntity) boatCamera = get(ChaseCamera.class,"trackedVehicle",null)==mount;
                    ClientConfig.CHASE_CAMERA.set(false);
                }
                if(mountedTicks==73) {
                    active.disabled = !(boolean)get(ChaseCamera.class,"tracking",null) && (float)get(ChaseCamera.class,"cameraRoll",null)==0;
                    check(active.disabled,"camera did not disable: "+active.name);
                    disabled |= active.disabled;
                    ClientConfig.CHASE_CAMERA.set(true);
                }
            }
            if(mount==null && dismounted) check(!(boolean)get(ChaseCamera.class,"tracking",null),"camera tracking leaked after dismount");
            if (mount instanceof CarEntity car && car.isRigidBody() && mountedTicks == 30) {
                HudAcceptanceChecks.verify(mc, true);
                if (carMounts == 1) hudMounted = true;
                else hudReentered = true;
            } else if (mount == null && hudMounted && !hudExited) {
                HudAcceptanceChecks.verify(mc, false);
                hudExited = true;
            }
            if(ticks%10==0) {
                Class<?> type=Class.forName("io.github.brooswitminecraft.dynamicatmosphere.client.AtmosphereClient");
                Map<?,?> materials=(Map<?,?>)get(type,"MATERIALS",null);
                for(var entry:materials.entrySet()) {
                    var method=entry.getValue().getClass().getDeclaredMethod("cache"); method.setAccessible(true);
                    Object cache=method.invoke(entry.getValue());
                    int count=(int)cache.getClass().getMethod("size").invoke(cache);
                    if(entry.getKey().toString().equals("DUST") && count>0) dust=true;
                    if(entry.getKey().toString().equals("EXHAUST") && count>0) exhaust=true;
                }
            }
            if(ticks==700) {
                DynamicVehiclesMod.LOGGER.info("CLIENT_ACCEPTANCE_STATE cars={} boatCamera={} dismount={} recenter={} freeLook={} disabled={} dust={} exhaust={}",
                        carMounts,boatCamera,dismounted,recentered,freeLook,disabled,dust,exhaust);
                for(String required : new String[]{"car-1","boat","car-2"}) {
                    Probe probe=probes.get(required);
                    check(probe!=null && probe.input && probe.freeLook && probe.recentered && probe.disabled,
                            "per-mount acceptance incomplete: "+required+" "+probe);
                    DynamicVehiclesMod.LOGGER.info("CLIENT_ACCEPTANCE_MOUNT {}",probe);
                }
                check(carInput && boatInput,"simulated input missing: car="+carInput+" boat="+boatInput);
                check(carMounts>=2 && boatCamera && dismounted,"car/boat/dismount/car transition incomplete: cars="+carMounts+" boat="+boatCamera+" dismount="+dismounted);
                check(recentered && freeLook && disabled,"camera recenter/free-look/disable incomplete");
                check(HudRenderAcceptance.complete(), "rendered HUD screenshots/restoration incomplete");
                check(hudMounted && hudExited && hudReentered, "HUD mount/exit/re-entry coverage incomplete");
                check(dust && exhaust,"client receipt missing: dust="+dust+" exhaust="+exhaust);
                Files.writeString(Path.of("client-acceptance-result.json"),"{\"passed\":true,\"simulated_input\":true,\"camera_transitions\":true,\"dust_receipt\":true,\"exhaust_receipt\":true,\"physical_wheel_tested\":false,\"visual_quality_tested\":false,\"hud_geometry_and_restoration\":true,\"rendered_hud_frames\":10}");
                DynamicVehiclesMod.LOGGER.info("CLIENT_ACCEPTANCE_PASS"); mc.stop();
            }
        } catch(Exception failure) { fail(failure); }
    }
    private static void fail(Exception failure) {
        DynamicVehiclesMod.LOGGER.error("CLIENT_ACCEPTANCE_FAIL",failure);
        try { Files.writeString(Path.of("client-acceptance-failure.txt"),failure.toString()); } catch(Exception ignored) {}
        Minecraft.getInstance().stop();
    }
}
