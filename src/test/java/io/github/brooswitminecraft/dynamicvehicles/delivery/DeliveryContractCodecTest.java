package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MINECRAFT-110 AC3: the persistence round-trip for {@link DeliveryContract},
 * following {@link DispatcherOfferPayloadCodecTest}'s pattern - plain
 * {@link java.io.DataOutput}/{@link java.io.DataInput}, no Minecraft or NBT
 * type, since a plain JVM test cannot instantiate a {@code CompoundTag}.
 * {@link DeliveryContractStorage} mirrors this same field list and order
 * against real NBT for the actual saved-data path (not covered here - see
 * its own javadoc).
 */
class DeliveryContractCodecTest {

    private static byte[] encode(DeliveryContract contract) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DeliveryContractCodec.write(new DataOutputStream(bytes), contract);
        return bytes.toByteArray();
    }

    private static DeliveryContract decode(byte[] bytes) throws IOException {
        return DeliveryContractCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    @Test
    void roundTrip_preservesEveryField() throws IOException {
        DeliveryContract original = new DeliveryContract(
                UUID.randomUUID(), UUID.randomUUID(), "minecraft:the_nether",
                7, -11, 12345, 64, -6789, 0.42, 123.75, 1000L, 7000L);

        DeliveryContract decoded = decode(encode(original));

        assertEquals(original, decoded);
    }

    @Test
    void roundTrip_preservesFieldsAtExtremeValues() throws IOException {
        DeliveryContract original = new DeliveryContract(
                new UUID(Long.MIN_VALUE, Long.MAX_VALUE), new UUID(Long.MAX_VALUE, Long.MIN_VALUE), "minecraft:overworld",
                Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, Long.MIN_VALUE / 2,
                0.0, 0.0, 0L, Long.MAX_VALUE);

        DeliveryContract decoded = decode(encode(original));

        assertEquals(original, decoded);
    }
}
