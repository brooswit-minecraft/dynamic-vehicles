package io.github.brooswitminecraft.dynamicvehicles.town;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
@GameTestHolder("dynamicvehicles_m454_donation_box_finaltwo") @PrefixGameTestTemplate(false)
public final class TownDonationBoxFinalTwoGameTests {
 private TownDonationBoxFinalTwoGameTests() {}
 @GameTest(template="settlement_test") public static void completedBeWithAbsentReceiptQuarantinesWithoutConsumingNewOffer(GameTestHelper h){TownDonationBoxGameTests.completedBeWithAbsentReceiptQuarantinesWithoutConsumingNewOffer(h);}
 @GameTest(template="settlement_test") public static void pendingReceiptCancelsRealPlayerBreakWithoutStateLoss(GameTestHelper h){TownDonationBoxGameTests.pendingReceiptCancelsRealPlayerBreakWithoutStateLoss(h);}
}
