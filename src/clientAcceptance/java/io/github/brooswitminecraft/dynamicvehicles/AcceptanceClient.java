package io.github.brooswitminecraft.dynamicvehicles;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    private static boolean recentered;
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
                if(mount==null && boatCamera) dismounted=true;
            }
            mountedTicks++;
            if(mount instanceof DriverVehicleEntity vehicle && vehicle.isRigidBody()) {
                if(mountedTicks==5) {
                    var q=vehicle.renderOrientation(1);
                    float yaw=ChaseCameraMath.angles(q.x,q.y,q.z,q.w).yaw();
                    mc.player.setYRot(yaw+70); initialError=70;
                }
                if(mountedTicks==7) freeLook |= (int)get(ChaseCamera.class,"idleTicks",null)<5;
                if(mountedTicks==70) {
                    var q=vehicle.renderOrientation(1);
                    float yaw=ChaseCameraMath.angles(q.x,q.y,q.z,q.w).yaw();
                    recentered |= Math.abs(Mth.wrapDegrees(mc.player.getYRot()-yaw))<initialError/2;
                    if(mount instanceof MotorboatEntity) boatCamera = get(ChaseCamera.class,"trackedVehicle",null)==mount;
                    ClientConfig.CHASE_CAMERA.set(false);
                }
                if(mountedTicks==73) {
                    disabled |= !(boolean)get(ChaseCamera.class,"tracking",null) && (float)get(ChaseCamera.class,"cameraRoll",null)==0;
                    ClientConfig.CHASE_CAMERA.set(true);
                }
            }
            if(mount==null && dismounted) check(!(boolean)get(ChaseCamera.class,"tracking",null),"camera tracking leaked after dismount");
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
                check(carInput && boatInput,"simulated input missing: car="+carInput+" boat="+boatInput);
                check(carMounts>=2 && boatCamera && dismounted,"car/boat/dismount/car transition incomplete");
                check(recentered && freeLook && disabled,"camera recenter/free-look/disable incomplete");
                check(dust && exhaust,"client receipt missing: dust="+dust+" exhaust="+exhaust);
                Files.writeString(Path.of("client-acceptance-result.json"),"{\"passed\":true,\"simulated_input\":true,\"camera_transitions\":true,\"dust_receipt\":true,\"exhaust_receipt\":true,\"physical_wheel_tested\":false,\"visual_quality_tested\":false}");
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
