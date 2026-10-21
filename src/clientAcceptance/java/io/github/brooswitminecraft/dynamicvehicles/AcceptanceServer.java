package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Only compiled with -PclientAcceptance, never part of a normal release. */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID)
public final class AcceptanceServer {
    private static int ticks;
    private static CarEntity car;
    private static MotorboatEntity boat;
    private static boolean receivedInput;
    private static final boolean[] throttleReceived = new boolean[3];
    private static final boolean[] steeringReceived = new boolean[3];
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        var players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        ServerPlayer player = players.getFirst();
        var level = player.serverLevel();
        if (ticks++ == 0) {
            player.setInvulnerable(true);
            for (int x=-12;x<=12;x++) for(int z=-12;z<=80;z++) {
                level.setBlock(new BlockPos(x,99,z),Blocks.STONE.defaultBlockState(),3);
                for(int y=100;y<=104;y++) level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
            }
            for (int x=-12;x<=12;x++) for(int z=-12;z<=80;z++) {
                if(x==-12 || x==12 || z==-12 || z==80)
                    for(int y=100;y<=104;y++) level.setBlock(new BlockPos(x,y,z),Blocks.BEDROCK.defaultBlockState(),3);
            }
            for(int x=4;x<=9;x++) for(int z=-4;z<=4;z++)
                level.setBlock(new BlockPos(x,100,z),Blocks.WATER.defaultBlockState(),3);
            car = DynamicVehiclesMod.CAR.get().create(level);
            car.moveTo(.5,101,0.5,0,0); level.addFreshEntity(car);
            boat = DynamicVehiclesMod.MOTORBOAT.get().create(level);
            boat.moveTo(6.5,101,0.5,0,0); level.addFreshEntity(boat);
            player.teleportTo(.5,102,.5); player.startRiding(car,true);
            DynamicVehiclesMod.LOGGER.info("CLIENT_ACCEPTANCE_SERVER_READY");
        }
        if (player.getVehicle()!=null && Math.abs(player.zza)>.1) receivedInput=true;
        int phase = player.getVehicle()==boat ? 1 : player.getVehicle()==car ? (ticks<470 ? 0 : 2) : -1;
        if (phase>=0) {
            throttleReceived[phase] |= Math.abs(player.zza)>.1;
            steeringReceived[phase] |= Math.abs(player.xxa)>.01;
        }
        if (ticks==220) { player.stopRiding(); player.startRiding(boat,true); }
        if (ticks==420) player.stopRiding();
        if (ticks==470) { player.teleportTo(car.getX(),car.getY()+2,car.getZ()); player.startRiding(car,true); }
        // Exercise the production optional-mod/network route; this is receipt coverage, not visual density.
        if (ticks%10==0 && ticks<650) {
            BlockPos pos=player.blockPosition().offset(2,0,0);
            AtmosphereCompat.dust(level,pos,64);
            AtmosphereCompat.exhaust(level,pos.offset(0,0,2),64);
        }
        if (ticks==650) {
            if (!receivedInput) throw new IllegalStateException("client analog input never reached server rider");
            for(int phaseIndex=0;phaseIndex<3;phaseIndex++) {
                if(!throttleReceived[phaseIndex] || !steeringReceived[phaseIndex])
                    throw new IllegalStateException("missing server input phase="+phaseIndex
                            +" throttle="+throttleReceived[phaseIndex]+" steering="+steeringReceived[phaseIndex]);
            }
            DynamicVehiclesMod.LOGGER.info("CLIENT_ACCEPTANCE_SERVER_PASS car_throttle=true car_steering=true boat_throttle=true boat_steering=true reentry_throttle=true reentry_steering=true");
        }
    }
}
