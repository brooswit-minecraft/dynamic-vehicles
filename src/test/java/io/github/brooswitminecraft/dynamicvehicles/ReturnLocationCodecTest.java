package io.github.brooswitminecraft.dynamicvehicles;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MINECRAFT-397: the persistence round-trip for {@link ReturnLocation}, following
 * {@code DeliveryContractCodecTest}'s pattern - plain {@link java.io.DataOutput}/
 * {@link java.io.DataInput}, no Minecraft or NBT type, since a plain JVM test
 * cannot instantiate a {@code CompoundTag}. {@link ReturnLocationStorage} mirrors
 * this same field list and order against real NBT for the actual saved-data path
 * (not covered here).
 */
class ReturnLocationCodecTest {

    private static byte[] encode(ReturnLocation location) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ReturnLocationCodec.write(new DataOutputStream(bytes), location);
        return bytes.toByteArray();
    }

    private static ReturnLocation decode(byte[] bytes) throws IOException {
        return ReturnLocationCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    @Test
    void roundTrip_preservesEveryField() throws IOException {
        ReturnLocation original = new ReturnLocation("minecraft:the_nether", 123.5, -6.0, -789.25, 91.5f, -12.25f);

        ReturnLocation decoded = decode(encode(original));

        assertEquals(original, decoded);
    }

    @Test
    void roundTrip_preservesFieldsAtExtremeValues() throws IOException {
        ReturnLocation original = new ReturnLocation("dynamicvehicles:flat_garage",
                Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE);

        ReturnLocation decoded = decode(encode(original));

        assertEquals(original, decoded);
    }
}
