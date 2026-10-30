package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated selector for the retained one-substep partial-contact timeout diagnostic. */
@GameTestHolder("dynamicvehicles_m454_partial_contact_diagnostic")
@PrefixGameTestTemplate(false)
public final class PartialContactDiagnosticGameTests {
    private PartialContactDiagnosticGameTests() {}

    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "clutchPartialOne")
    public static void partialContactEnergyAtOneSubstep(GameTestHelper helper) {
        ClutchContactLossGameTests.partialContactEnergyAtOneSubstep(helper);
    }
}
