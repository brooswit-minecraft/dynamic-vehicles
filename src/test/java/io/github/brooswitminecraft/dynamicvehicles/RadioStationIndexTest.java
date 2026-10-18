package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-function checks for {@link RadioStationIndex} (MINECRAFT-394). */
class RadioStationIndexTest {

    @Test
    void cyclesFromOffThroughEveryStationAndBackToOff() {
        int index = RadioStationIndex.OFF;
        index = RadioStationIndex.next(index, 3);
        assertEquals(0, index);
        index = RadioStationIndex.next(index, 3);
        assertEquals(1, index);
        index = RadioStationIndex.next(index, 3);
        assertEquals(2, index);
        index = RadioStationIndex.next(index, 3);
        assertTrue(RadioStationIndex.isOff(index));
    }

    @Test
    void noStationsConfiguredAlwaysStaysOff() {
        assertTrue(RadioStationIndex.isOff(RadioStationIndex.next(RadioStationIndex.OFF, 0)));
        assertTrue(RadioStationIndex.isOff(RadioStationIndex.next(0, 0)));
    }

    @Test
    void singleStationTogglesBetweenOffAndIt() {
        int index = RadioStationIndex.next(RadioStationIndex.OFF, 1);
        assertEquals(0, index);
        index = RadioStationIndex.next(index, 1);
        assertTrue(RadioStationIndex.isOff(index));
    }

    @Test
    void clampFallsBackToOffForNegativeOrOutOfRangeOrEmpty() {
        assertTrue(RadioStationIndex.isOff(RadioStationIndex.clamp(-1, 5)));
        assertTrue(RadioStationIndex.isOff(RadioStationIndex.clamp(5, 5)));
        assertTrue(RadioStationIndex.isOff(RadioStationIndex.clamp(0, 0)));
        assertEquals(2, RadioStationIndex.clamp(2, 5));
    }

    @Test
    void shrunkStationListFallsBackToOffRatherThanAStaleIndex() {
        // A previously saved index of 4 ("station 5") no longer fits a 2-station list -- clamp (used when
        // reloading saved NBT) and next (used when cycling) must both treat that the same as Off, not
        // silently keep pointing at a station that no longer exists.
        assertTrue(RadioStationIndex.isOff(RadioStationIndex.clamp(4, 2)));
        assertEquals(0, RadioStationIndex.next(4, 2));
    }

    @Test
    void labelsOffAndStationsReadably() {
        assertEquals("Off", RadioStationIndex.label(RadioStationIndex.OFF, 3));
        assertEquals("Station 1/3", RadioStationIndex.label(0, 3));
        assertEquals("Station 3/3", RadioStationIndex.label(2, 3));
    }
}
