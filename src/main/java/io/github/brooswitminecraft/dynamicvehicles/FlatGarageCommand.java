package io.github.brooswitminecraft.dynamicvehicles;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

/**
 * {@code /dvflat}: the MINECRAFT-397 there-and-back command. One command, no
 * arguments - it goes to the flat garage dimension if the player isn't there,
 * or back to wherever the player left from if they are, remembering each
 * player's departure dimension and exact position/facing so "back" is never
 * an unrelated world spawn. If the remembered return location is missing or
 * now points at an unloadable dimension, falls back to the overworld's
 * spawn point rather than leaving the player stuck or dropping them inside
 * terrain.
 */
final class FlatGarageCommand {
    private FlatGarageCommand() {
    }

    static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        ReturnLocationStorage storage = ReturnLocationStorage.of(server);

        if (player.level().dimension().equals(FlatGarageDimension.LEVEL_KEY)) {
            returnFromFlatGarage(context, player, server, storage);
        } else {
            goToFlatGarage(context, player, server, storage);
        }
        return 1;
    }

    private static void goToFlatGarage(CommandContext<CommandSourceStack> context, ServerPlayer player,
            MinecraftServer server, ReturnLocationStorage storage) {
        ServerLevel flatGarage = server.getLevel(FlatGarageDimension.LEVEL_KEY);
        if (flatGarage == null) {
            context.getSource().sendFailure(Component.literal("The flat garage dimension is not loaded."));
            return;
        }
        storage.put(player.getUUID(), new ReturnLocation(
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        player.teleportTo(flatGarage, FlatGarageDimension.spawnX(), FlatGarageDimension.spawnY(flatGarage), FlatGarageDimension.spawnZ(),
                Set.of(), player.getYRot(), player.getXRot());
        context.getSource().sendSuccess(() -> Component.literal("Teleported to the flat garage."), true);
    }

    private static void returnFromFlatGarage(CommandContext<CommandSourceStack> context, ServerPlayer player,
            MinecraftServer server, ReturnLocationStorage storage) {
        UUID playerId = player.getUUID();
        ReturnLocation location = storage.get(playerId);
        ServerLevel targetLevel = location != null ? resolveLevel(server, location.dimension()) : null;

        if (targetLevel == null) {
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot());
        } else {
            player.teleportTo(targetLevel, location.x(), location.y(), location.z(), Set.of(), location.yaw(), location.pitch());
        }
        storage.remove(playerId);
        context.getSource().sendSuccess(() -> Component.literal("Teleported back."), true);
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
