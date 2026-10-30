package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Isolates the three moved old-home owners and their two cleanup protections. */
@GameTestHolder("dynamicvehicles_m454_old_home_fixture")
@PrefixGameTestTemplate(false)
public final class TownOldHomeFixtureGameTests {
    private TownOldHomeFixtureGameTests() {}

    @GameTest(template = "settlement_test")
    public static void anExistingHomeIsNeverLeakedOrErasedWithoutAnActualReplacement(GameTestHelper helper) {
        TownGameTests.anExistingHomeIsNeverLeakedOrErasedWithoutAnActualReplacement(helper);
    }

    @GameTest(template = "settlement_test")
    public static void aHandoffNeverReleasesAnOldHomeAnotherVillagerGenuinelyTookOver(GameTestHelper helper) {
        TownGameTests.aHandoffNeverReleasesAnOldHomeAnotherVillagerGenuinelyTookOver(helper);
    }

    @GameTest(template = "settlement_test")
    public static void aHandoffPreservesAnOldHomeWhoseTakeoverOwnerUnloadedAcrossSaveAndReload(GameTestHelper helper) {
        TownGameTests.aHandoffPreservesAnOldHomeWhoseTakeoverOwnerUnloadedAcrossSaveAndReload(helper);
    }

    @GameTest(template = "settlement_test")
    public static void constructionAbortsWhenAForeignBedOccupiesTheSelectedFootprint(GameTestHelper helper) {
        TownGameTests.constructionAbortsWhenAForeignBedOccupiesTheSelectedFootprint(helper);
    }

    @GameTest(template = "settlement_test")
    public static void ownedOldHomeFixtureFailureCleanupPreservesPrimaryError(GameTestHelper helper) {
        TownGameTests.ownedOldHomeFixtureFailureCleanupPreservesPrimaryError(helper);
    }
}
