package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-110 AC9: the plain contract state machine - accept -> active ->
 * complete, accept -> active -> expire, one-contract enforcement, and
 * reward payout - all driven by an injected {@link TickClock} rather than a
 * running server.
 */
class ContractBookTest {

    private static DeliveryContract contract(UUID playerId, long destX, long destZ, long acceptedAt, long deadline, double reward) {
        return new DeliveryContract(playerId, UUID.randomUUID(), "minecraft:overworld", 3, -2,
                destX, 64, destZ, 0.5, reward, acceptedAt, deadline);
    }

    @Test
    void accept_succeedsWhenNoContractIsActive() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        ContractBook.AcceptResult result = book.accept(contract(player, 100, 100, 0, 1000, 10.0));

        assertEquals(ContractBook.AcceptResult.ACCEPTED, result);
        assertTrue(book.hasActive(player));
    }

    @Test
    void accept_refusesASecondContractForTheSamePlayer_oneContractEnforcement() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        book.accept(contract(player, 100, 100, 0, 1000, 10.0));

        ContractBook.AcceptResult second = book.accept(contract(player, 200, 200, 0, 1000, 99.0));

        assertEquals(ContractBook.AcceptResult.ALREADY_ACTIVE, second);
        // the original contract must be untouched by the refused attempt
        assertEquals(10.0, book.active(player).orElseThrow().reward());
    }

    @Test
    void accept_allowsDifferentPlayersToEachHoldTheirOwnContract() {
        ContractBook book = new ContractBook();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        assertEquals(ContractBook.AcceptResult.ACCEPTED, book.accept(contract(playerA, 100, 100, 0, 1000, 10.0)));
        assertEquals(ContractBook.AcceptResult.ACCEPTED, book.accept(contract(playerB, 100, 100, 0, 1000, 20.0)));
        assertTrue(book.hasActive(playerA));
        assertTrue(book.hasActive(playerB));
    }

    @Test
    void evaluate_withNoActiveContract_reportsNoActiveContract() {
        ContractBook book = new ContractBook();
        ContractBook.Evaluation evaluation = book.evaluate(UUID.randomUUID(), 0, 0, () -> 0L);

        assertEquals(ContractBook.Outcome.NO_ACTIVE_CONTRACT, evaluation.outcome());
    }

    @Test
    void evaluate_stillActive_whenNeitherArrivedNorExpired() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        book.accept(contract(player, 1000, 1000, 0, 1000, 10.0));

        ContractBook.Evaluation evaluation = book.evaluate(player, 0, 0, () -> 500L);

        assertEquals(ContractBook.Outcome.STILL_ACTIVE, evaluation.outcome());
        assertTrue(book.hasActive(player), "an in-progress evaluation must not remove the contract");
    }

    @Test
    void evaluate_completes_andPaysTheRecordedReward_accept_active_complete() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        book.accept(contract(player, 1000, 1000, 0, 1000, 42.5));

        ContractBook.Evaluation evaluation = book.evaluate(player, 1000, 1000, () -> 500L);

        assertEquals(ContractBook.Outcome.COMPLETED, evaluation.outcome());
        assertEquals(42.5, evaluation.contract().reward(), "reward payout must reflect the contract's own recorded value");
        assertFalse(book.hasActive(player), "completing must clear the contract, freeing the player for another");
    }

    @Test
    void evaluate_completeNearMiss_doesNotComplete() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        book.accept(contract(player, 1000, 1000, 0, 1000, 42.5));

        // one block outside the destination's chunk - close, but not a tight arrival
        ContractBook.Evaluation evaluation = book.evaluate(player, 1016, 1000, () -> 500L);

        assertEquals(ContractBook.Outcome.STILL_ACTIVE, evaluation.outcome());
        assertTrue(book.hasActive(player));
    }

    @Test
    void evaluate_expires_pastDeadline_accept_active_expire() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        book.accept(contract(player, 1000, 1000, 0, 1000, 42.5));

        ContractBook.Evaluation evaluation = book.evaluate(player, 0, 0, () -> 1001L);

        assertEquals(ContractBook.Outcome.EXPIRED, evaluation.outcome());
        assertFalse(book.hasActive(player), "expiring must clear the contract, freeing the player for another");
    }

    @Test
    void evaluate_atExactDeadlineTick_isStillActiveNotExpired() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        book.accept(contract(player, 1000, 1000, 0, 1000, 42.5));

        ContractBook.Evaluation evaluation = book.evaluate(player, 0, 0, () -> 1000L);

        assertEquals(ContractBook.Outcome.STILL_ACTIVE, evaluation.outcome());
    }

    @Test
    void evaluate_expiryTakesPriorityOverArrivalOnTheSameEvaluation() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        book.accept(contract(player, 1000, 1000, 0, 1000, 42.5));

        // arrived AND past deadline in the same check: must be reported expired, never a late reward
        ContractBook.Evaluation evaluation = book.evaluate(player, 1000, 1000, () -> 1001L);

        assertEquals(ContractBook.Outcome.EXPIRED, evaluation.outcome());
    }

    @Test
    void snapshotAndRestore_roundTripsActiveContracts() {
        ContractBook book = new ContractBook();
        UUID player = UUID.randomUUID();
        DeliveryContract original = contract(player, 100, 100, 0, 1000, 10.0);
        book.accept(original);

        ContractBook restored = new ContractBook();
        restored.restore(List.copyOf(book.snapshot()));

        assertTrue(restored.hasActive(player));
        assertEquals(original, restored.active(player).orElseThrow());
    }
}
