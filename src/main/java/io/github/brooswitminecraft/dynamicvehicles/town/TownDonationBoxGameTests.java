package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import io.github.brooswitminecraft.dynamicvehicles.delivery.GameTestMockPlayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Serializer-level coverage for the real registered box entity.  These tests are intentionally
 * compiled here but require the separately assigned GameTest runtime to execute. */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownDonationBoxGameTests {
    private static final BlockPos POS = new BlockPos(5, 2, 5);
    private TownDonationBoxGameTests() {}

    private static TownDonationBoxEntity placeAndBind(GameTestHelper helper) {
        helper.setBlock(POS, TownDonationBoxes.DONATION_BOX.get());
        BlockPos absolute = helper.absolutePos(POS);
        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), TownRegion.fromBlockCoords(absolute.getX(), absolute.getZ())));
        TownDonationBoxEntity box = (TownDonationBoxEntity) helper.getLevel().getBlockEntity(absolute);
        box.bind(helper.getLevel());
        return box;
    }

    @GameTest(template = "settlement_test")
    public static void loadedMetadataSurvivesSaveBeforeInteraction(GameTestHelper helper) {
        TownDonationBoxEntity box = placeAndBind(helper);
        CompoundTag saved = new CompoundTag();
        box.saveAdditional(saved, helper.getLevel().registryAccess());
        TownDonationBoxEntity reloaded = new TownDonationBoxEntity(helper.absolutePos(POS), TownDonationBoxes.DONATION_BOX.get().defaultBlockState());
        reloaded.loadAdditional(saved, helper.getLevel().registryAccess());
        CompoundTag resaved = new CompoundTag();
        reloaded.saveAdditional(resaved, helper.getLevel().registryAccess());
        if (!resaved.hasUUID("Box") || resaved.getLong("Generation") <= 0) helper.fail("loaded binding metadata was dropped before interaction");
        else helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void storageBeforeBeRetainsExactReceiptForReplay(GameTestHelper helper) {
        TownDonationBoxEntity box = placeAndBind(helper);
        if (!box.accept(helper.getLevel(), new ItemStack(Items.COBBLESTONE, 2))) helper.fail("initial accepted receipt failed");
        else if (box.reconciliationResult() != TownDonationBoxEntity.ReconciliationResult.ACKNOWLEDGED_COMPLETED) helper.fail("completion watermark missing");
        else helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void oldPendingBeWithCompletedStorageClearsEscrowWithoutRecredit(GameTestHelper helper) {
        TownDonationBoxEntity box = placeAndBind(helper);
        if (!box.stagePendingForSerializerTest(helper.getLevel(), new ItemStack(Items.COBBLESTONE, 2))) { helper.fail("could not stage pending receipt"); return; }
        CompoundTag oldBe = new CompoundTag(); box.saveAdditional(oldBe, helper.getLevel().registryAccess());
        TownStorage live = TownStorage.of(helper.getLevel().getServer());
        if (!box.completePendingForSerializerTest(helper.getLevel())) { helper.fail("could not complete storage receipt"); return; }
        int credited = live.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), TownRegion.fromBlockCoords(helper.absolutePos(POS).getX(), helper.absolutePos(POS).getZ()))).balance(TownResourceCategory.STONE);
        TownStorage completed = TownStorage.load(live.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        TownDonationBoxEntity old = new TownDonationBoxEntity(helper.absolutePos(POS), TownDonationBoxes.DONATION_BOX.get().defaultBlockState());
        old.loadAdditional(oldBe, helper.getLevel().registryAccess());
        CompoundTag recovered = new CompoundTag();
        old.reconcileLoadedState(helper.getLevel(), completed); old.saveAdditional(recovered, helper.getLevel().registryAccess());
        if (old.reconciliationResult() != TownDonationBoxEntity.ReconciliationResult.ACKNOWLEDGED_COMPLETED
                || completed.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), TownRegion.fromBlockCoords(helper.absolutePos(POS).getX(), helper.absolutePos(POS).getZ()))).balance(TownResourceCategory.STONE) != credited
                || recovered.contains("Escrow") || recovered.contains("Sequence")
                || recovered.getLong("CompletedSequence") != oldBe.getLong("Sequence")
                || !recovered.getString("CompletedItem").equals(oldBe.getString("Item"))) helper.fail("completed receipt recredited, retained escrow/pending, or lost exact completed watermark");
        else helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void completedBeWithReservedStorageCreditsExactlyOnceOnReconcile(GameTestHelper helper) {
        TownDonationBoxEntity box = placeAndBind(helper);
        if (!box.stagePendingForSerializerTest(helper.getLevel(), new ItemStack(Items.COBBLESTONE, 2))) { helper.fail("could not stage receipt"); return; }
        TownStorage live = TownStorage.of(helper.getLevel().getServer());
        TownStorage reserved = TownStorage.load(live.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        if (!box.completePendingForSerializerTest(helper.getLevel())) { helper.fail("could not complete BE snapshot"); return; }
        CompoundTag completedBe = new CompoundTag(); box.saveAdditional(completedBe, helper.getLevel().registryAccess());
        TownDonationBoxEntity restored = new TownDonationBoxEntity(helper.absolutePos(POS), TownDonationBoxes.DONATION_BOX.get().defaultBlockState());
        restored.loadAdditional(completedBe, helper.getLevel().registryAccess());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), TownRegion.fromBlockCoords(helper.absolutePos(POS).getX(), helper.absolutePos(POS).getZ()));
        int expected = TownDonationConversion.creditFor("minecraft:cobblestone").amount() * 2;
        int before = reserved.storeFor(key).balance(TownResourceCategory.STONE);
        restored.reconcileLoadedState(helper.getLevel(), reserved);
        int once = reserved.storeFor(key).balance(TownResourceCategory.STONE);
        CompoundTag recovered = new CompoundTag(); restored.saveAdditional(recovered, helper.getLevel().registryAccess());
        TownDonationBoxEntity reloaded = new TownDonationBoxEntity(helper.absolutePos(POS), TownDonationBoxes.DONATION_BOX.get().defaultBlockState());
        reloaded.loadAdditional(recovered, helper.getLevel().registryAccess()); reloaded.reconcileLoadedState(helper.getLevel(), reserved);
        TownStorage.DonationBoxBinding expectedBinding = new TownStorage.DonationBoxBinding(helper.getLevel().dimension(), helper.absolutePos(POS),
                completedBe.getString("Town"), completedBe.getUUID("Box"), completedBe.getLong("Generation"));
        TownStorage.DonationPayload expectedPayload = new TownStorage.DonationPayload(completedBe.getString("CompletedItem"),
                completedBe.getInt("CompletedCount"), TownResourceCategory.valueOf(completedBe.getString("CompletedCategory")), completedBe.getInt("CompletedCredit"));
        boolean completedReceipt = reserved.donationOperationReceipt(expectedBinding).filter(operation -> operation.state() == TownStorage.DonationOperationState.COMPLETED
                && operation.sequence() == completedBe.getLong("CompletedSequence") && operation.payload().equals(expectedPayload)).isPresent();
        if (restored.reconciliationResult() != TownDonationBoxEntity.ReconciliationResult.ACKNOWLEDGED_COMPLETED || once != before + expected
                || reserved.storeFor(key).balance(TownResourceCategory.STONE) != once || recovered.contains("Escrow") || recovered.contains("Sequence")
                || reloaded.reconciliationResult() != TownDonationBoxEntity.ReconciliationResult.ACKNOWLEDGED_COMPLETED || !completedReceipt) helper.fail("reserved storage reconciliation recredited or retained pending state");
        else helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void completedBeWithAbsentReceiptQuarantinesWithoutConsumingNewOffer(GameTestHelper helper) {
        TownDonationBoxEntity box = placeAndBind(helper); BlockPos absolute = helper.absolutePos(POS);
        TownStorage live = TownStorage.of(helper.getLevel().getServer());
        TownStorage oldStorage = TownStorage.load(live.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        if (!box.accept(helper.getLevel(), new ItemStack(Items.COBBLESTONE, 2))) { helper.fail("could not make completed BE"); return; }
        CompoundTag completedBe = new CompoundTag(); box.saveAdditional(completedBe, helper.getLevel().registryAccess());
        TownDonationBoxEntity restored = new TownDonationBoxEntity(absolute, TownDonationBoxes.DONATION_BOX.get().defaultBlockState());
        restored.loadAdditional(completedBe, helper.getLevel().registryAccess());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), TownRegion.fromBlockCoords(absolute.getX(), absolute.getZ()));
        int before = oldStorage.storeFor(key).balance(TownResourceCategory.STONE); int liveBefore = live.storeFor(key).balance(TownResourceCategory.STONE); ItemStack offered = new ItemStack(Items.COBBLESTONE, 1);
        TownStorage.DonationBoxBinding binding = new TownStorage.DonationBoxBinding(helper.getLevel().dimension(), absolute, completedBe.getString("Town"), completedBe.getUUID("Box"), completedBe.getLong("Generation"));
        boolean absentBefore = oldStorage.donationOperationReceipt(binding).isEmpty();
        restored.reconcileLoadedState(helper.getLevel(), oldStorage); boolean refused = restored.accept(helper.getLevel(), offered);
        CompoundTag resaved = new CompoundTag(); restored.saveAdditional(resaved, helper.getLevel().registryAccess());
        boolean absentAfter = oldStorage.donationOperationReceipt(binding).isEmpty();
        if (restored.reconciliationResult() != TownDonationBoxEntity.ReconciliationResult.QUARANTINED || refused || offered.getCount() != 1 || oldStorage.storeFor(key).balance(TownResourceCategory.STONE) != before || live.storeFor(key).balance(TownResourceCategory.STONE) != liveBefore || !absentBefore || !absentAfter
                || !resaved.getUUID("Box").equals(completedBe.getUUID("Box")) || resaved.getLong("Generation") != completedBe.getLong("Generation") || !resaved.getString("Town").equals(completedBe.getString("Town"))
                || resaved.getLong("CompletedSequence") != completedBe.getLong("CompletedSequence") || !resaved.getString("CompletedItem").equals(completedBe.getString("CompletedItem")) || resaved.getInt("CompletedCount") != completedBe.getInt("CompletedCount") || !resaved.getString("CompletedCategory").equals(completedBe.getString("CompletedCategory")) || resaved.getInt("CompletedCredit") != completedBe.getInt("CompletedCredit")) helper.fail("absent receipt did not preserve quarantined completed BE");
        else helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void pendingReceiptCancelsRealPlayerBreakWithoutStateLoss(GameTestHelper helper) {
        TownDonationBoxEntity box = placeAndBind(helper); BlockPos absolute = helper.absolutePos(POS);
        if (!box.stagePendingForSerializerTest(helper.getLevel(), new ItemStack(Items.COBBLESTONE, 2))) { helper.fail("could not stage pending receipt"); return; }
        CompoundTag before = new CompoundTag(); box.saveAdditional(before, helper.getLevel().registryAccess());
        TownStorage storage = TownStorage.of(helper.getLevel().getServer()); String key = TownKeys.keyFor(helper.getLevel().dimension(), TownRegion.fromBlockCoords(absolute.getX(), absolute.getZ())); int balance = storage.storeFor(key).balance(TownResourceCategory.STONE);
        TownStorage.DonationBoxBinding binding = new TownStorage.DonationBoxBinding(helper.getLevel().dimension(), absolute, before.getString("Town"), before.getUUID("Box"), before.getLong("Generation"));
        var receipt = storage.donationOperationReceipt(binding);
        var player = GameTestMockPlayers.create(helper);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(helper.getLevel(), absolute, helper.getLevel().getBlockState(absolute), player);
        NeoForge.EVENT_BUS.post(event);
        CompoundTag after = new CompoundTag(); box.saveAdditional(after, helper.getLevel().registryAccess());
        if (!event.isCanceled() || !helper.getLevel().getBlockState(absolute).is(TownDonationBoxes.DONATION_BOX.get())
                || helper.getLevel().getBlockEntity(absolute) != box || !before.contains("Escrow") || !before.contains("Sequence")
                || receipt.isEmpty() || receipt.get().state() != TownStorage.DonationOperationState.RESERVED || !before.equals(after) || storage.storeFor(key).balance(TownResourceCategory.STONE) != balance || !receipt.equals(storage.donationOperationReceipt(binding))) helper.fail("pending donation break was not safely cancelled");
        else helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void movedOrCopiedLoadedMetadataQuarantines(GameTestHelper helper) {
        TownDonationBoxEntity box = placeAndBind(helper);
        CompoundTag saved = new CompoundTag(); box.saveAdditional(saved, helper.getLevel().registryAccess());
        TownDonationBoxEntity copy = new TownDonationBoxEntity(helper.absolutePos(POS).offset(1, 0, 0), TownDonationBoxes.DONATION_BOX.get().defaultBlockState());
        copy.loadAdditional(saved, helper.getLevel().registryAccess());
        copy.bind(helper.getLevel());
        if (copy.reconciliationResult() != TownDonationBoxEntity.ReconciliationResult.QUARANTINED) helper.fail("copied binding was not quarantined");
        else helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void interactionCreditsActualQuantityAndRefusesUnsupportedInput(GameTestHelper helper) {
        placeAndBind(helper);
        var player = GameTestMockPlayers.create(helper); BlockPos absolute = helper.absolutePos(POS);
        String key = TownKeys.keyFor(helper.getLevel().dimension(), TownRegion.fromBlockCoords(helper.absolutePos(POS).getX(), helper.absolutePos(POS).getZ()));
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 3);
        ItemStack diamond = new ItemStack(Items.DIAMOND, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, cobble);
        int before = TownStorage.of(helper.getLevel().getServer()).storeFor(key).balance(TownResourceCategory.STONE);
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute), net.minecraft.core.Direction.UP, absolute, false);
        if (helper.getLevel().getBlockState(absolute).useItemOn(cobble, helper.getLevel(), player, InteractionHand.MAIN_HAND, hit) != net.minecraft.world.ItemInteractionResult.CONSUME || !cobble.isEmpty()) { helper.fail("accepted quantity was not consumed"); return; }
        int credit = TownDonationConversion.creditFor("minecraft:cobblestone").amount() * 3;
        player.setItemInHand(InteractionHand.MAIN_HAND, diamond);
        if (TownStorage.of(helper.getLevel().getServer()).storeFor(key).balance(TownResourceCategory.STONE) != before + credit
                || helper.getLevel().getBlockState(absolute).useItemOn(diamond, helper.getLevel(), player, InteractionHand.MAIN_HAND, hit) == net.minecraft.world.ItemInteractionResult.CONSUME || player.getItemInHand(InteractionHand.MAIN_HAND).getCount() != 1
                || TownStorage.of(helper.getLevel().getServer()).storeFor(key).balance(TownResourceCategory.STONE) != before + credit) helper.fail("quantity credit or unsupported refusal incorrect");
        else helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void sequentialDonationsShareOnlyTheBoxReceiptNotPlayerOwnership(GameTestHelper helper) {
        placeAndBind(helper); BlockPos absolute = helper.absolutePos(POS);
        var playerA = GameTestMockPlayers.create(helper); var playerB = GameTestMockPlayers.create(helper);
        String key = TownKeys.keyFor(helper.getLevel().dimension(), TownRegion.fromBlockCoords(helper.absolutePos(POS).getX(), helper.absolutePos(POS).getZ()));
        ItemStack first = new ItemStack(Items.COBBLESTONE, 1), second = new ItemStack(Items.COBBLESTONE, 2);
        playerA.setItemInHand(InteractionHand.MAIN_HAND, first); playerB.setItemInHand(InteractionHand.MAIN_HAND, second);
        int before = TownStorage.of(helper.getLevel().getServer()).storeFor(key).balance(TownResourceCategory.STONE);
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute), net.minecraft.core.Direction.UP, absolute, false);
        if (helper.getLevel().getBlockState(absolute).useItemOn(first, helper.getLevel(), playerA, InteractionHand.MAIN_HAND, hit) != net.minecraft.world.ItemInteractionResult.CONSUME
                || helper.getLevel().getBlockState(absolute).useItemOn(second, helper.getLevel(), playerB, InteractionHand.MAIN_HAND, hit) != net.minecraft.world.ItemInteractionResult.CONSUME) { helper.fail("a later donor was blocked by prior receipt ownership"); return; }
        int expected = TownDonationConversion.creditFor("minecraft:cobblestone").amount() * 3;
        if (!playerA.getItemInHand(InteractionHand.MAIN_HAND).isEmpty() || !playerB.getItemInHand(InteractionHand.MAIN_HAND).isEmpty() || TownStorage.of(helper.getLevel().getServer()).storeFor(key).balance(TownResourceCategory.STONE) != before + expected) helper.fail("sequential donation receipt accounting incorrect");
        else helper.succeed();
    }
}
