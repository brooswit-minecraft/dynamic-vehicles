package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated selector for the distant-claimant construction regression. */
@GameTestHolder("dynamicvehicles_m454_wandering_claim")
@PrefixGameTestTemplate(false)
public final class TownWanderingClaimGameTests {
    private TownWanderingClaimGameTests() {}

    @GameTest(template = "settlement_test")
    public static void claimSurvivesTheClaimingVillagerWanderingFarFromTheCampfire(GameTestHelper helper) {
        TownGameTests.claimSurvivesTheClaimingVillagerWanderingFarFromTheCampfire(helper);
    }
}
