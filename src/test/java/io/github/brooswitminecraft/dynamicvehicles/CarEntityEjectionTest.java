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
}
