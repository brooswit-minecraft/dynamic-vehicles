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

import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /dvflat}: the MINECRAFT-397 there-and-back command. One command, no
 * arguments - it goes to the flat garage dimension if the player isn't there,
 * or back to wherever the player left from if they are, remembering each
 * player's departure dimension and exact position/facing so "back" is never
 * an unrelated world spawn.
 *
 * <p>Every candidate landing (the garage's fixed spawn, the remembered
 * return position, and the overworld's shared spawn fallback) is run through
 * {@link #findSafeLanding}, which can relocate it vertically if the actual
 * current terrain at that column has since become obstructed - a block
 * placed there, terrain regenerated, or (review fix, MINECRAFT-397) simply
 * stale information - so a player is never teleported into solid blocks. If
 * no safe landing can be found at all, the command fails outright and, for a
 * return trip, the stored return location is preserved rather than consumed,
 * so a later retry (e.g. once the obstruction is cleared) can still use it.
 */
final class FlatGarageCommand {
    private FlatGarageCommand() {
    }

    static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        ReturnLocationStorage storage = ReturnLocationStorage.of(server);

        if (player.level().dimension().equals(FlatGarageDimension.LEVEL_KEY)) {
            return returnFromFlatGarage(context, player, server, storage);
        } else {
            return goToFlatGarage(context, player, server, storage);
        }
    }

    private static int goToFlatGarage(CommandContext<CommandSourceStack> context, ServerPlayer player,
            MinecraftServer server, ReturnLocationStorage storage) {
        ServerLevel flatGarage = server.getLevel(FlatGarageDimension.LEVEL_KEY);
        if (flatGarage == null) {
            context.getSource().sendFailure(Component.literal("The flat garage dimension is not loaded."));
            return 0;
        }
        BlockPos target = BlockPos.containing(FlatGarageDimension.spawnX(), FlatGarageDimension.spawnY(flatGarage), FlatGarageDimension.spawnZ());
        BlockPos landing = findSafeLanding(flatGarage, target);
        if (landing == null) {
            context.getSource().sendFailure(Component.literal("Could not find a safe spot to land in the flat garage."));
            return 0;
        }
        // Only remember the departure location once a safe landing is confirmed, so a failed
        // attempt (no safe spot found) never records a return trip the player never actually took.
        storage.put(player.getUUID(), new ReturnLocation(
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        player.teleportTo(flatGarage, landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());
        context.getSource().sendSuccess(() -> Component.literal("Teleported to the flat garage."), true);
        return 1;
    }

    private static int returnFromFlatGarage(CommandContext<CommandSourceStack> context, ServerPlayer player,
            MinecraftServer server, ReturnLocationStorage storage) {
        UUID playerId = player.getUUID();
        ReturnLocation location = storage.get(playerId);
        ServerLevel targetLevel = location != null ? resolveLevel(server, location.dimension()) : null;

        ServerLevel landingLevel;
        BlockPos target;
        float yaw;
        float pitch;
        if (targetLevel != null) {
            landingLevel = targetLevel;
            target = BlockPos.containing(location.x(), location.y(), location.z());
            yaw = location.yaw();
            pitch = location.pitch();
        } else {
            landingLevel = server.overworld();
            target = landingLevel.getSharedSpawnPos();
            yaw = player.getYRot();
            pitch = player.getXRot();
        }

        BlockPos landing = findSafeLanding(landingLevel, target);
        if (landing == null) {
            context.getSource().sendFailure(Component.literal("Could not find a safe spot to return to - try again later."));
            // Preserve the return record: the obstruction may clear, and the player never actually left.
            return 0;
        }
        player.teleportTo(landingLevel, landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5, Set.of(), yaw, pitch);
        storage.remove(playerId);
        context.getSource().sendSuccess(() -> Component.literal("Teleported back."), true);
        return 1;
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /**
     * Finds a nearby column position at which both {@code target} and the block above it are
     * unobstructed, searching outward vertically via {@link SafeLandingSearch} - the
     * Minecraft-free algorithm this method is a thin, untested wrapper around (mirrored exactly;
     * see that class's javadoc for why the split exists). Returns {@code null} if none was found.
     */
    private static BlockPos findSafeLanding(ServerLevel level, BlockPos target) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        OptionalLong safeY = SafeLandingSearch.findSafeY(target.getY(), minY, maxY,
                y -> level.getBlockState(new BlockPos(target.getX(), y, target.getZ())).blocksMotion());
        return safeY.isPresent() ? new BlockPos(target.getX(), (int) safeY.getAsLong(), target.getZ()) : null;
    }
}
