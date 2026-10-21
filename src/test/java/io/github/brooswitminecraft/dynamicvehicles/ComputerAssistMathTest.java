package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComputerAssistMathTest {
    @Test
    void disabledLevelsPreserveCommandsExactly() {
        assertEquals(800.0, ComputerAssistMath.tractionFactor(0, 6, 1, 800) * 800, 0.0);
        assertEquals(900.0, ComputerAssistMath.absFactor(0, 0, 8, 900) * 900, 0.0);
    }

    @Test
    void tcOnlyReducesDrivenWheelspinInTheCommandDirection() {
        assertTrue(ComputerAssistMath.tractionFactor(1, 4, 1, 500) < 1.0);
        assertEquals(1.0, ComputerAssistMath.tractionFactor(1, 1, 4, 500), 0.0);
    }

    @Test
    void absOnlyReleasesAForwardBrakeWhenTheWheelIsLocking() {
        assertTrue(ComputerAssistMath.absFactor(1, 1, 5, 700) < 1.0);
        assertEquals(1.0, ComputerAssistMath.absFactor(1, 5, 1, 700), 0.0);
        assertEquals(1.0, ComputerAssistMath.absFactor(1, 0, 0.1, 700), 0.0);
    }
}
