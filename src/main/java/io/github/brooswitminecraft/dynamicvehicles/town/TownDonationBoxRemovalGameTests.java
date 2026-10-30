package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated selector for the two normal-removal regressions; assertions stay with the box tests. */
@GameTestHolder("dynamicvehicles_m454_donation_box_removal")
@PrefixGameTestTemplate(false)
public final class TownDonationBoxRemovalGameTests {
    private TownDonationBoxRemovalGameTests() {}

    @GameTest(template = "settlement_test")
    public static void cleanReloadedBoxAllowsRealPlayerBreakAndRetiresExactBinding(GameTestHelper helper) {
        TownDonationBoxGameTests.cleanReloadedBoxAllowsRealPlayerBreakAndRetiresExactBinding(helper);
    }

    @GameTest(template = "settlement_test")
    public static void neverBoundUnfoundedBoxAllowsRealPlayerBreak(GameTestHelper helper) {
        TownDonationBoxGameTests.neverBoundUnfoundedBoxAllowsRealPlayerBreak(helper);
    }
}
