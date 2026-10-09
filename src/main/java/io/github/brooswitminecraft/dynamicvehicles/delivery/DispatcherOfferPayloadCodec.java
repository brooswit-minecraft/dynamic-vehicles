package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain encode/decode of the field list {@link DispatcherOfferMenu} sends to
 * the client: the config's danger bounds plus every {@link DispatcherOfferRow}
 * (MINECRAFT-109 AC5, AC8). Uses only {@link DataOutput}/{@link DataInput} —
 * no Minecraft or Netty type — so the round trip is directly unit-testable,
 * which a {@code RegistryFriendlyByteBuf}-typed method cannot be (a plain JVM
 * test cannot instantiate one). {@link DispatcherOfferMenu} mirrors this same
 * field list and order against {@code RegistryFriendlyByteBuf} for the real
 * network path; that mirroring itself is thin Minecraft-facing glue and is
 * not covered by this test, only by a person's own manual check (see the
 * PR description for exactly what to check).
 */
public final class DispatcherOfferPayloadCodec {

    private DispatcherOfferPayloadCodec() {
    }

    public record Decoded(double dangerMin, double dangerMax, List<DispatcherOfferRow> rows) {
    }

    public static void write(DataOutput out, double dangerMin, double dangerMax, List<DispatcherOfferRow> rows) throws IOException {
        out.writeDouble(dangerMin);
        out.writeDouble(dangerMax);
        out.writeInt(rows.size());
        for (DispatcherOfferRow row : rows) {
            out.writeDouble(row.approxDistanceBlocks());
            out.writeDouble(row.danger());
            out.writeLong(row.timeAllowanceTicks());
            out.writeDouble(row.reward());
        }
    }

    public static Decoded read(DataInput in) throws IOException {
        double dangerMin = in.readDouble();
        double dangerMax = in.readDouble();
        int count = in.readInt();
        List<DispatcherOfferRow> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new DispatcherOfferRow(in.readDouble(), in.readDouble(), in.readLong(), in.readDouble()));
        }
        return new Decoded(dangerMin, dangerMax, List.copyOf(rows));
    }
}
