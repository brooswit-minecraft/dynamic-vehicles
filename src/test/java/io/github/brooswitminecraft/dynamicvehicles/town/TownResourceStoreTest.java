package io.github.brooswitminecraft.dynamicvehicles.town;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownResourceStoreTest {
    @Test
    void newStoreStartsAtZeroForEveryCategory() {
        TownResourceStore store = new TownResourceStore();
        for (TownResourceCategory category : TownResourceCategory.values()) {
            assertEquals(0, store.balance(category));
        }
    }

    @Test
    void creditIncreasesBalance() {
        TownResourceStore store = new TownResourceStore();
        store.credit(TownResourceCategory.STONE, 5);
        store.credit(TownResourceCategory.STONE, 3);
        assertEquals(8, store.balance(TownResourceCategory.STONE));
    }

    @Test
    void debitSucceedsWhenAffordableAndReducesBalance() {
        TownResourceStore store = new TownResourceStore();
        store.credit(TownResourceCategory.WOOD, 10);
        assertTrue(store.tryDebit(TownResourceCategory.WOOD, 4));
        assertEquals(6, store.balance(TownResourceCategory.WOOD));
    }

    @Test
    void debitFailsAndLeavesBalanceUnchangedWhenInsufficientFunds() {
        TownResourceStore store = new TownResourceStore();
        store.credit(TownResourceCategory.FOOD, 2);
        assertFalse(store.tryDebit(TownResourceCategory.FOOD, 3));
        assertEquals(2, store.balance(TownResourceCategory.FOOD));
    }

    @Test
    void balanceNeverGoesNegative() {
        TownResourceStore store = new TownResourceStore();
        assertFalse(store.tryDebit(TownResourceCategory.STONE, 1));
        assertEquals(0, store.balance(TownResourceCategory.STONE));
    }

    @Test
    void creditRejectsNegativeAmounts() {
        TownResourceStore store = new TownResourceStore();
        assertThrows(IllegalArgumentException.class, () -> store.credit(TownResourceCategory.STONE, -1));
    }

    @Test
    void snapshotAndLoadFromRoundTrip() {
        TownResourceStore store = new TownResourceStore();
        store.credit(TownResourceCategory.FOOD, 7);
        store.credit(TownResourceCategory.WOOD, 2);

        TownResourceStore restored = new TownResourceStore();
        restored.loadFrom(store.snapshot());

        assertEquals(store.balance(TownResourceCategory.FOOD), restored.balance(TownResourceCategory.FOOD));
        assertEquals(store.balance(TownResourceCategory.WOOD), restored.balance(TownResourceCategory.WOOD));
        assertEquals(store.balance(TownResourceCategory.STONE), restored.balance(TownResourceCategory.STONE));
    }
}
