package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/** Production-path checks for {@link CarEntity}'s collision-safe mob ejection decision. */
class CarEntityEjectionTest {

    @Test
    void allBlockedCandidatesKeepTheMobMountedAndNeverPlaceIt() {
        AtomicBoolean stoppedRiding = new AtomicBoolean();
        AtomicBoolean placed = new AtomicBoolean();

        boolean ejected = CarEntity.tryEjectMobAtSafePosition(3.0, 7.0, -2.0, candidate -> false,
                () -> stoppedRiding.set(true), candidate -> placed.set(true));

        assertFalse(ejected, "an all-blocked search must defer ejection");
        assertFalse(stoppedRiding.get(), "the mob must remain mounted when no safe point exists");
        assertFalse(placed.get(), "the mob must never be placed at an unsafe origin or candidate");
    }

    @Test
    void aSafeCandidateStopsRidingAndPlacesTheMobThroughTheProductionPath() {
        AtomicBoolean stoppedRiding = new AtomicBoolean();
        AtomicBoolean placed = new AtomicBoolean();

        boolean ejected = CarEntity.tryEjectMobAtSafePosition(3.0, 7.0, -2.0,
                candidate -> candidate[0] == 4.0 && candidate[1] == 7.0 && candidate[2] == -2.0,
                () -> stoppedRiding.set(true), candidate -> placed.set(true));

        assertTrue(ejected);
        assertTrue(stoppedRiding.get());
        assertTrue(placed.get());
    }

    @Test
    void ejectionBlockedOnTheRisingEdgeIsRetriedWhileLatchedAndSucceedsOnceASafeSpotOpens() {
        CrashEjectionLatch latch = new CrashEjectionLatch();
        double fast = CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD + 1.0;
        AtomicBoolean mounted = new AtomicBoolean(true);
        AtomicBoolean placed = new AtomicBoolean();
        boolean[] safeNow = {false};

        // Tick 1: the speed crosses the threshold (rising edge) but every candidate is blocked.
        boolean edge = latch.tick(fast);
        assertTrue(CarEntity.shouldAttemptCrashEjection(edge, latch.isLatched(), mounted.get()));
        assertFalse(CarEntity.tryEjectMobAtSafePosition(0, 0, 0, c -> safeNow[0], () -> mounted.set(false), c -> placed.set(true)));
        assertTrue(mounted.get(), "a blocked ejection must keep the mob mounted, never place it in a block");
        assertFalse(placed.get());

        // Tick 2: still too fast, so the latch does NOT fire again -- but the retry must still run.
        edge = latch.tick(fast);
        assertFalse(edge, "the latch only fires on the rising edge");
        safeNow[0] = true;
        assertTrue(CarEntity.shouldAttemptCrashEjection(edge, latch.isLatched(), mounted.get()),
                "a latched vehicle with a mob still aboard must retry ejection");
        assertTrue(CarEntity.tryEjectMobAtSafePosition(0, 0, 0, c -> safeNow[0], () -> mounted.set(false), c -> placed.set(true)));
        assertFalse(mounted.get());
        assertTrue(placed.get());

        // Tick 3: the mob is gone, so there is nothing left to retry.
        edge = latch.tick(fast);
        assertFalse(CarEntity.shouldAttemptCrashEjection(edge, latch.isLatched(), mounted.get()));
    }

    @Test
    void noRetryWhenNotLatchedEvenWithAMobAboard() {
        assertFalse(CarEntity.shouldAttemptCrashEjection(false, false, true));
    }
}
