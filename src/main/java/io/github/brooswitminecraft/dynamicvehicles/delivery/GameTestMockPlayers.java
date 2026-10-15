package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

/**
 * GameTest mock players whose connection never ran the real client's mod-channel negotiation, so
 * NeoForge refuses to send them any payload the connection has not declared. Sable and Dynamic
 * Atmosphere are both on the GameTest runtime classpath and both push periodic payloads to every
 * logged-in player; declaring those channels is the test-only equivalent of the negotiation having
 * happened.
 */
final class GameTestMockPlayers {
    private GameTestMockPlayers() {}

    static ServerPlayer create(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        declare(player, "sable", "dimension_physics");
        declare(player, "dynamicatmosphere", "atmosphere_grid");
        declare(player, "dynamicatmosphere", "smoke_grid");
        declare(player, "dynamicatmosphere", "material_grid");
        return player;
    }

    private static void declare(ServerPlayer player, String namespace, String path) {
        ChannelAttributes.getOrCreateAdHocChannels(player.connection.getConnection())
                .add(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
