package io.github.brooswitminecraft.dynamicvehicles.town;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
@GameTestHolder("dynamicvehicles_m454_donation_box_reversesave") @PrefixGameTestTemplate(false)
public final class TownDonationBoxReverseSaveGameTests {
 private TownDonationBoxReverseSaveGameTests() {}
 @GameTest(template="settlement_test") public static void completedBeWithReservedStorageCreditsExactlyOnceOnReconcile(GameTestHelper h){TownDonationBoxGameTests.completedBeWithReservedStorageCreditsExactlyOnceOnReconcile(h);}
}
