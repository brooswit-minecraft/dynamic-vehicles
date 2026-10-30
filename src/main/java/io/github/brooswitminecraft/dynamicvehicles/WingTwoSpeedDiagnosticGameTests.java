package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated selector for the retained two-speed wing setup diagnostic. */
@GameTestHolder("dynamicvehicles_m454_wing_two_speed_diagnostic")
@PrefixGameTestTemplate(false)
public final class WingTwoSpeedDiagnosticGameTests {
    private WingTwoSpeedDiagnosticGameTests() {}

    @GameTest(template = "settlement_test", timeoutTicks = 400 + VehicleLanes.CHUNK_LOAD_ALLOWANCE,
            batch = "wingTwoSpeedDownforceIsolated")
    public static void wingDownforceLoadsTheSuspensionMoreAtHigherSpeed(GameTestHelper helper) {
        WingAeroGameTests.wingDownforceLoadsTheSuspensionMoreAtHigherSpeed(helper);
    }
}
