package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The plain contract state machine (MINECRAFT-110 AC1-2, AC5-7, AC9): at
 * most one {@link DeliveryContract} per player, with transitions driven by
 * an injected {@link TickClock} rather than a running server, so the whole
 * state machine - accept, one-contract enforcement, complete, expire - is
 * directly unit-testable. The thin Minecraft layer
 * ({@link DeliveryContractStorage}) is the only thing that persists this
 * book's contents or calls it from a tick handler; this class touches no
 * Minecraft type.
 */
public final class ContractBook {

    public enum AcceptResult {
        ACCEPTED,
        ALREADY_ACTIVE
    }

    public enum Outcome {
        STILL_ACTIVE,
        COMPLETED,
        EXPIRED,
        NO_ACTIVE_CONTRACT
    }

    /** @param contract the contract that was evaluated; {@code null} only for {@link Outcome#NO_ACTIVE_CONTRACT} */
    public record Evaluation(Outcome outcome, DeliveryContract contract) {
    }

    private final Map<UUID, DeliveryContract> contracts = new HashMap<>();

    /** AC2: refuses (rather than replacing) when the player already has an active contract. */
    public AcceptResult accept(DeliveryContract contract) {
        Objects.requireNonNull(contract, "contract");
        if (contracts.containsKey(contract.playerId())) {
            return AcceptResult.ALREADY_ACTIVE;
        }
        contracts.put(contract.playerId(), contract);
        return AcceptResult.ACCEPTED;
    }

    public boolean hasActive(UUID playerId) {
        return contracts.containsKey(playerId);
    }

    public Optional<DeliveryContract> active(UUID playerId) {
        return Optional.ofNullable(contracts.get(playerId));
    }

    /**
     * AC5/AC7: expires the active contract if the deadline has passed,
     * otherwise completes it if {@code candidateX,candidateZ} satisfies
     * {@link ArrivalPredicate}. Either terminal outcome removes the
     * contract from the book; deadline is checked first, so a candidate
     * that both arrived and is past deadline is reported EXPIRED, never a
     * late reward.
     */
    public Evaluation evaluate(UUID playerId, long candidateX, long candidateZ, TickClock clock) {
        DeliveryContract contract = contracts.get(playerId);
        if (contract == null) {
            return new Evaluation(Outcome.NO_ACTIVE_CONTRACT, null);
        }
        if (clock.currentTick() > contract.deadlineTick()) {
            contracts.remove(playerId);
            return new Evaluation(Outcome.EXPIRED, contract);
        }
        if (ArrivalPredicate.hasArrived(contract.destinationStartX(), contract.destinationStartZ(), candidateX, candidateZ)) {
            contracts.remove(playerId);
            return new Evaluation(Outcome.COMPLETED, contract);
        }
        return new Evaluation(Outcome.STILL_ACTIVE, contract);
    }

    /** Unconditional clear, e.g. for admin/debug use; AC7's own expiry path goes through {@link #evaluate}. */
    public Optional<DeliveryContract> clear(UUID playerId) {
        return Optional.ofNullable(contracts.remove(playerId));
    }

    /** For persistence (AC3): every active contract, in no particular order. */
    public List<DeliveryContract> snapshot() {
        return List.copyOf(contracts.values());
    }

    /** For persistence (AC3): replaces this book's contents with {@code loaded}, keyed by each contract's own player id. */
    public void restore(List<DeliveryContract> loaded) {
        contracts.clear();
        for (DeliveryContract contract : loaded) {
            contracts.put(contract.playerId(), contract);
        }
    }
}
