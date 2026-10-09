package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-109 AC8: round-trip coverage for the menu/packet field list,
 * extracted into {@link DispatcherOfferPayloadCodec} (plain
 * {@link java.io.DataOutput}/{@link java.io.DataInput}) because a plain JVM
 * test cannot instantiate a {@code RegistryFriendlyByteBuf}. See
 * {@link DispatcherOfferMenu} for the hand-mirrored real network path this
 * does not cover, and the PR description for what that still needs a manual
 * check for.
 */
class DispatcherOfferPayloadCodecTest {

    private static byte[] encode(double dangerMin, double dangerMax, List<DispatcherOfferRow> rows) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DispatcherOfferPayloadCodec.write(new DataOutputStream(bytes), dangerMin, dangerMax, rows);
        return bytes.toByteArray();
    }

    private static DispatcherOfferPayloadCodec.Decoded decode(byte[] bytes) throws IOException {
        return DispatcherOfferPayloadCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    @Test
    void roundTrip_preservesDangerBoundsAndEmptyRowList() throws IOException {
        DispatcherOfferPayloadCodec.Decoded decoded = decode(encode(0.1, 0.9, List.of()));

        assertEquals(0.1, decoded.dangerMin(), 1e-9);
        assertEquals(0.9, decoded.dangerMax(), 1e-9);
        assertTrue(decoded.rows().isEmpty());
    }

    @Test
    void roundTrip_preservesEveryRowFieldInOrder() throws IOException {
        List<DispatcherOfferRow> rows = List.of(
                new DispatcherOfferRow(1234.5, 0.25, 6000L, 42.0),
                new DispatcherOfferRow(0.0, 0.0, 0L, 0.0),
                new DispatcherOfferRow(999999.9, 1.0, Long.MAX_VALUE, -3.5));

        DispatcherOfferPayloadCodec.Decoded decoded = decode(encode(0.0, 1.0, rows));

        assertEquals(rows.size(), decoded.rows().size());
        for (int i = 0; i < rows.size(); i++) {
            DispatcherOfferRow expected = rows.get(i);
            DispatcherOfferRow actual = decoded.rows().get(i);
            assertEquals(expected.approxDistanceBlocks(), actual.approxDistanceBlocks(), 1e-9);
            assertEquals(expected.danger(), actual.danger(), 1e-9);
            assertEquals(expected.timeAllowanceTicks(), actual.timeAllowanceTicks());
            assertEquals(expected.reward(), actual.reward(), 1e-9);
        }
    }
}
