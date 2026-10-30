package io.github.brooswitminecraft.dynamicvehicles.town;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
@GameTestHolder("dynamicvehicles_m454_donation_box_interaction") @PrefixGameTestTemplate(false)
public final class TownDonationBoxInteractionGameTests {
 private TownDonationBoxInteractionGameTests() {}
 @GameTest(template="settlement_test") public static void interactionCreditsActualQuantityAndRefusesUnsupportedInput(GameTestHelper h){TownDonationBoxGameTests.interactionCreditsActualQuantityAndRefusesUnsupportedInput(h);}
 @GameTest(template="settlement_test") public static void sequentialDonationsShareOnlyTheBoxReceiptNotPlayerOwnership(GameTestHelper h){TownDonationBoxGameTests.sequentialDonationsShareOnlyTheBoxReceiptNotPlayerOwnership(h);}
}
