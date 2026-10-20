package io.github.brooswitminecraft.dynamicvehicles.town;

import java.util.EnumMap;
import java.util.Map;

/**
 * A town-level shared abstract resource store: food, stone, wood, as category credit counts, never
 * exact-block inventories. Mutable but self-contained and Minecraft-free, so it is directly
 * unit-testable; a thin {@code SavedData} wrapper (see {@code TownStorage}) persists one of these
 * per town.
 */
public final class TownResourceStore {
    private final Map<TownResourceCategory, Integer> balances = new EnumMap<>(TownResourceCategory.class);

    public TownResourceStore() {
        for (TownResourceCategory category : TownResourceCategory.values()) {
            balances.put(category, 0);
        }
    }

    public int balance(TownResourceCategory category) {
        return balances.get(category);
    }

    /** Credits can never be negative in amount; a donation or passive trickle only ever adds. */
    public void credit(TownResourceCategory category, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("credit amount must be >= 0: " + amount);
        }
        balances.merge(category, amount, Integer::sum);
    }

    /**
     * Attempts to debit {@code amount} from {@code category}. Returns {@code false} and leaves the
     * balance completely unchanged when the store cannot afford it — there is no partial charge and
     * the balance can never go negative, per MINECRAFT-388 ("no free construction, no negative
     * stores").
     */
    public boolean tryDebit(TownResourceCategory category, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("debit amount must be >= 0: " + amount);
        }
        int current = balances.get(category);
        if (current < amount) {
            return false;
        }
        balances.put(category, current - amount);
        return true;
    }

    public boolean canAfford(TownResourceCategory category, int amount) {
        return balances.get(category) >= amount;
    }

    public Map<TownResourceCategory, Integer> snapshot() {
        return Map.copyOf(balances);
    }

    public void loadFrom(Map<TownResourceCategory, Integer> snapshot) {
        for (TownResourceCategory category : TownResourceCategory.values()) {
            balances.put(category, snapshot.getOrDefault(category, 0));
        }
    }
}
