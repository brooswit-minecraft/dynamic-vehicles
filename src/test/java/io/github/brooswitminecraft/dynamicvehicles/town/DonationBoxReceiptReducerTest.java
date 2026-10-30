package io.github.brooswitminecraft.dynamicvehicles.town;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DonationBoxReceiptReducerTest {
    private static DonationBoxReceiptReducer.Payload payload(int count) {
        return new DonationBoxReceiptReducer.Payload("minecraft:cobblestone", count, TownResourceCategory.STONE, count * 2);
    }

    @Test
    void staleOrExistingBlockEntitySequenceIsRefusedBeforeEscrow() {
        DonationBoxReceiptReducer.Pending stale = new DonationBoxReceiptReducer.Pending(4, payload(1), false);
        assertTrue(DonationBoxReceiptReducer.reserve(stale, 5, payload(1)).isEmpty());
        assertTrue(DonationBoxReceiptReducer.reserve(null, 0, payload(1)).isEmpty());
    }

    @Test
    void sameSequenceWithDifferentPayloadNeverLooksLikeAppliedReceipt() {
        DonationBoxReceiptReducer.Pending pending = DonationBoxReceiptReducer.reserve(null, 7, payload(2)).orElseThrow();
        assertFalse(DonationBoxReceiptReducer.matches(pending, 7, payload(3)));
        assertTrue(DonationBoxReceiptReducer.apply(pending, 7, payload(3)).isEmpty());
    }

    @Test
    void exactPendingPayloadCanApplyAndBeAcknowledged() {
        DonationBoxReceiptReducer.Pending pending = DonationBoxReceiptReducer.reserve(null, 7, payload(2)).orElseThrow();
        DonationBoxReceiptReducer.Pending applied = DonationBoxReceiptReducer.apply(pending, 7, payload(2)).orElseThrow();
        assertTrue(applied.applied());
        assertTrue(DonationBoxReceiptReducer.matches(applied, 7, payload(2)));
    }
}
