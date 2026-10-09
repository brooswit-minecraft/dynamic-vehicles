package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

/**
 * Plain encode/decode of a {@link DeliveryContract}'s field list
 * (MINECRAFT-110 AC3), using only {@link DataOutput}/{@link DataInput} - no
 * Minecraft or NBT type - so the persistence round trip is directly
 * unit-testable, following {@link DispatcherOfferPayloadCodec}'s pattern.
 * {@link DeliveryContractStorage} mirrors this same field list and order
 * against real {@code CompoundTag} for the actual saved-data path.
 */
public final class DeliveryContractCodec {

    private DeliveryContractCodec() {
    }

    public static void write(DataOutput out, DeliveryContract contract) throws IOException {
        out.writeLong(contract.playerId().getMostSignificantBits());
        out.writeLong(contract.playerId().getLeastSignificantBits());
        out.writeLong(contract.sourceVillagerId().getMostSignificantBits());
        out.writeLong(contract.sourceVillagerId().getLeastSignificantBits());
        out.writeUTF(contract.destinationDimension());
        out.writeInt(contract.destinationRegionX());
        out.writeInt(contract.destinationRegionZ());
        out.writeLong(contract.destinationStartX());
        out.writeLong(contract.destinationStartY());
        out.writeLong(contract.destinationStartZ());
        out.writeDouble(contract.danger());
        out.writeDouble(contract.reward());
        out.writeLong(contract.acceptedAtTick());
        out.writeLong(contract.deadlineTick());
    }

    public static DeliveryContract read(DataInput in) throws IOException {
        UUID playerId = new UUID(in.readLong(), in.readLong());
        UUID sourceVillagerId = new UUID(in.readLong(), in.readLong());
        String destinationDimension = in.readUTF();
        int destinationRegionX = in.readInt();
        int destinationRegionZ = in.readInt();
        long destinationStartX = in.readLong();
        long destinationStartY = in.readLong();
        long destinationStartZ = in.readLong();
        double danger = in.readDouble();
        double reward = in.readDouble();
        long acceptedAtTick = in.readLong();
        long deadlineTick = in.readLong();
        return new DeliveryContract(playerId, sourceVillagerId, destinationDimension, destinationRegionX, destinationRegionZ,
                destinationStartX, destinationStartY, destinationStartZ, danger, reward, acceptedAtTick, deadlineTick);
    }
}
