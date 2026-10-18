package io.github.brooswitminecraft.dynamicvehicles;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Plain encode/decode of a {@link ReturnLocation}'s field list, using only
 * {@link DataOutput}/{@link DataInput} - no Minecraft or NBT type - so the
 * persistence round trip is directly unit-testable. {@link ReturnLocationStorage}
 * mirrors this same field list and order against real {@code CompoundTag}
 * for the actual saved-data path.
 */
final class ReturnLocationCodec {

    private ReturnLocationCodec() {
    }

    static void write(DataOutput out, ReturnLocation location) throws IOException {
        out.writeUTF(location.dimension());
        out.writeDouble(location.x());
        out.writeDouble(location.y());
        out.writeDouble(location.z());
        out.writeFloat(location.yaw());
        out.writeFloat(location.pitch());
    }

    static ReturnLocation read(DataInput in) throws IOException {
        String dimension = in.readUTF();
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        float yaw = in.readFloat();
        float pitch = in.readFloat();
        return new ReturnLocation(dimension, x, y, z, yaw, pitch);
    }
}
