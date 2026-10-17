package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

/**
 * GameTest mock players whose connection never ran the real client's mod-channel negotiation, so
 * NeoForge refuses to send them any payload the connection has not declared. Sable and Dynamic
 * Atmosphere are both on the GameTest runtime classpath; Sable pushes its datapack payloads ({@code sable:dimension_physics},
 * {@code floating_material}, {@code physics_property}) during {@code PlayerList#placeNewPlayer} itself, so the channels must be declared on the connection
 * BEFORE login, which {@link GameTestHelper#makeMockServerPlayerInLevel()} gives no hook for. This
 * builds the same player that helper does (same profile, creative, non-spectator, embedded channel)
 * with the mod channels declared first — the test-only equivalent of the negotiation having happened.
 */
final class GameTestMockPlayers {
    private GameTestMockPlayers() {}

    static ServerPlayer create(GameTestHelper helper) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "test-mock-player"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        declare(connection, "sable", "dimension_physics");
        declare(connection, "sable", "floating_material");
        declare(connection, "sable", "physics_property");
        declare(connection, "dynamicatmosphere", "atmosphere_grid");
        declare(connection, "dynamicatmosphere", "smoke_grid");
        declare(connection, "dynamicatmosphere", "material_grid");
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void declare(Connection connection, String namespace, String path) {
        ChannelAttributes.getOrCreateAdHocChannels(connection).add(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
