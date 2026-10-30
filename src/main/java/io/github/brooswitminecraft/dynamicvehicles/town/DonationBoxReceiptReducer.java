package io.github.brooswitminecraft.dynamicvehicles.town;

import java.util.Objects;
import java.util.Optional;

/** Minecraft-free reducer for the donation-box receipt protocol.  TownStorage persists and
 * supplies the authoritative high-water; this class makes the exact replay comparison testable
 * in the ordinary JVM suite. */
public final class DonationBoxReceiptReducer {
    private DonationBoxReceiptReducer() {}

    public record Payload(String itemId, int count, TownResourceCategory category, int credit) {
        public Payload {
            if (itemId == null || itemId.isEmpty() || count <= 0 || credit <= 0) throw new IllegalArgumentException("invalid payload");
        }
    }
    public record Pending(long sequence, Payload payload, boolean applied) {
        public Pending { if (sequence <= 0) throw new IllegalArgumentException("invalid sequence"); }
    }

    /** Only a clean intake state can receive the storage-supplied next sequence. */
    public static Optional<Pending> reserve(Pending existing, long nextSequence, Payload payload) {
        if (existing != null || nextSequence <= 0) return Optional.empty();
        return Optional.of(new Pending(nextSequence, payload, false));
    }

    /** A duplicate acknowledgement is permitted only for the exact still-pending receipt payload. */
    public static boolean matches(Pending pending, long sequence, Payload payload) {
        return pending != null && pending.sequence() == sequence && Objects.equals(pending.payload(), payload);
    }

    public static Optional<Pending> apply(Pending pending, long sequence, Payload payload) {
        return matches(pending, sequence, payload) ? Optional.of(new Pending(sequence, payload, true)) : Optional.empty();
    }
}
