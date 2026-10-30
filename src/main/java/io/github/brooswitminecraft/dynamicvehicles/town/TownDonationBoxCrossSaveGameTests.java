package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated one-method runtime selector; the assertion remains owned by the original test. */
@GameTestHolder("dynamicvehicles_m454_donation_box")
@PrefixGameTestTemplate(false)
public final class TownDonationBoxCrossSaveGameTests {
    private TownDonationBoxCrossSaveGameTests() {}
    @GameTest(template = "settlement_test")
    public static void oldPendingBeWithCompletedStorageClearsEscrowWithoutRecredit(GameTestHelper helper) {
        TownDonationBoxGameTests.oldPendingBeWithCompletedStorageClearsEscrowWithoutRecredit(helper);
    }
}
