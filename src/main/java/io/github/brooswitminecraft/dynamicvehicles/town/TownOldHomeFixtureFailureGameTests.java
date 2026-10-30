package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Sole selector for the old-home fixture's controlled failure-cleanup regression. */
@GameTestHolder("dynamicvehicles_m454_old_home_fixture_failure")
@PrefixGameTestTemplate(false)
public final class TownOldHomeFixtureFailureGameTests {
    private TownOldHomeFixtureFailureGameTests() {}

    @GameTest(template = "settlement_test")
    public static void ownedOldHomeFixtureFailureCleanupPreservesPrimaryError(GameTestHelper helper) {
        TownGameTests.ownedOldHomeFixtureFailureCleanupPreservesPrimaryError(helper);
    }
}
