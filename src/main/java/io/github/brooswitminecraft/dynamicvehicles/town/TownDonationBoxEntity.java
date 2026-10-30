package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

/** Server-only intake boundary.  The offered stack is removed only after TownStorage has durably
 * reserved its exact payload; a copied/unbound entity cannot call the credit path. */
public final class TownDonationBoxEntity extends BlockEntity {
    enum ReconciliationResult { CLEAN, REPLAY_APPLIED, ACKNOWLEDGED_COMPLETED, QUARANTINED }
    private TownStorage.DonationBoxBinding binding;
    private ItemStack escrow = ItemStack.EMPTY;
    private TownStorage.DonationOperation pending;
    private UUID savedBoxId;
    private long savedGeneration;
    private String savedTown = "";
    private long savedSequence;
    private TownStorage.DonationPayload savedPayload;
    private boolean quarantined;
    private long completedSequence;
    private TownStorage.DonationPayload completedPayload;

    public TownDonationBoxEntity(BlockPos pos, BlockState state) { super(TownDonationBoxEntities.DONATION_BOX.get(), pos, state); }
    void bind(ServerLevel level) {
        reconcileLoadedState(level, TownStorage.of(level.getServer()));
    }
    ReconciliationResult reconcileLoadedState(ServerLevel level, TownStorage storage) {
        if (binding == null && savedBoxId != null) {
            binding = storage.activeDonationBoxAt(level.dimension(), worldPosition)
                    .filter(candidate -> candidate.boxId().equals(savedBoxId) && candidate.generation() == savedGeneration
                            && candidate.townKey().equals(savedTown)).orElse(null);
            if (binding == null) quarantined = true; // copied/moved/replaced NBT never gets a fresh identity
            else if (savedPayload != null) {
                var receipt = storage.donationOperationReceipt(binding);
                if (receipt.filter(operation -> operation.sequence() == savedSequence && operation.payload().equals(savedPayload)
                        && operation.state() == TownStorage.DonationOperationState.COMPLETED).isPresent()) {
                    completedSequence = savedSequence; completedPayload = savedPayload;
                    escrow = ItemStack.EMPTY; savedPayload = null; savedSequence = 0;
                } else if (receipt.filter(operation -> operation.sequence() == savedSequence && operation.payload().equals(savedPayload)
                        && (operation.state() == TownStorage.DonationOperationState.RESERVED || operation.state() == TownStorage.DonationOperationState.APPLIED)).isPresent()) {
                    pending = new TownStorage.DonationOperation(binding, savedSequence, savedPayload, TownStorage.DonationOperationState.RESERVED);
                    if (!storage.applyDonationReceipt(pending) || !storage.completeDonationOperation(pending)) quarantined = true;
                    else { escrow = ItemStack.EMPTY; completedSequence = savedSequence; completedPayload = savedPayload; pending = null; savedPayload = null; savedSequence = 0; }
                } else quarantined = true;
            } else if (!escrow.isEmpty()) quarantined = true;
            if (!quarantined && completedPayload != null && savedPayload == null) {
                TownStorage.DonationOperation watermark = new TownStorage.DonationOperation(binding, completedSequence, completedPayload,
                        TownStorage.DonationOperationState.RESERVED);
                // BE may have been saved after consuming/completing while storage was saved before
                // APPLIED. Reconcile only the exact immutable watermark; never create a new receipt.
                var receipt = storage.donationOperationReceipt(binding);
                if (receipt.filter(operation -> operation.sequence() == completedSequence && operation.payload().equals(completedPayload)
                        && operation.state() == TownStorage.DonationOperationState.COMPLETED).isPresent()) {
                    // Exact terminal storage receipt acknowledges a stale BE snapshot: no recredit.
                    pending = null; escrow = ItemStack.EMPTY;
                } else if (receipt.filter(operation -> operation.sequence() == completedSequence && operation.payload().equals(completedPayload)
                        && (operation.state() == TownStorage.DonationOperationState.RESERVED || operation.state() == TownStorage.DonationOperationState.APPLIED)).isPresent()) {
                    if (!storage.applyDonationReceipt(watermark) || !storage.completeDonationOperation(watermark)) quarantined = true;
                } else quarantined = true;
            }
        }
        if (binding == null && !quarantined && savedBoxId == null) binding = storage.bindDonationBox(level.dimension(), worldPosition).orElse(null);
        setChanged();
        return reconciliationResult();
    }
    ReconciliationResult reconciliationResult() {
        if (quarantined) return ReconciliationResult.QUARANTINED;
        if (completedPayload != null) return ReconciliationResult.ACKNOWLEDGED_COMPLETED;
        return pending == null ? ReconciliationResult.CLEAN : ReconciliationResult.REPLAY_APPLIED;
    }
    boolean accept(ServerLevel level, ItemStack offered) {
        TownStorage storage = TownStorage.of(level.getServer());
        if (binding == null) bind(level);
        if (binding == null || quarantined || !binding.dimension().equals(level.dimension()) || !binding.pos().equals(worldPosition)
                || !storage.isActiveDonationBox(binding) || pending != null || !escrow.isEmpty() || offered.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(offered.getItem()).toString();
        TownDonationConversion.Credit credit = TownDonationConversion.creditFor(id);
        if (credit == null || offered.getCount() <= 0) return false;
        long total = (long) credit.amount() * offered.getCount();
        if (total > Integer.MAX_VALUE) return false;
        TownStorage.DonationPayload payload = new TownStorage.DonationPayload(id, offered.getCount(), credit.category(), (int) total);
        pending = storage.reserveDonationOperation(binding, payload).orElse(null);
        if (pending == null) return false;
        escrow = offered.split(offered.getCount());
        setChanged();
        if (!storage.applyDonationReceipt(pending)) return false; // recovery retains escrow/pending
        escrow = ItemStack.EMPTY;
        if (!storage.completeDonationOperation(pending)) return false;
        completedSequence = pending.sequence();
        completedPayload = pending.payload();
        pending = null;
        setChanged();
        return true;
    }
    /** Test seam: stages the same storage reservation and escrow state that production creates
     * between reserve and apply, so serializer tests can snapshot the otherwise synchronous gap. */
    boolean stagePendingForSerializerTest(ServerLevel level, ItemStack offered) {
        TownStorage storage = TownStorage.of(level.getServer());
        if (binding == null) bind(level);
        if (binding == null || quarantined || pending != null || !escrow.isEmpty() || offered.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(offered.getItem()).toString();
        TownDonationConversion.Credit credit = TownDonationConversion.creditFor(id);
        if (credit == null) return false;
        long total = (long) credit.amount() * offered.getCount();
        if (total <= 0 || total > Integer.MAX_VALUE) return false;
        pending = storage.reserveDonationOperation(binding, new TownStorage.DonationPayload(id, offered.getCount(), credit.category(), (int) total)).orElse(null);
        if (pending == null) return false;
        escrow = offered.split(offered.getCount()); setChanged(); return true;
    }
    boolean completePendingForSerializerTest(ServerLevel level) {
        TownStorage storage = TownStorage.of(level.getServer());
        if (pending == null || !storage.applyDonationReceipt(pending) || !storage.completeDonationOperation(pending)) return false;
        completedSequence = pending.sequence(); completedPayload = pending.payload(); pending = null; escrow = ItemStack.EMPTY; setChanged();
        return true;
    }
    boolean isEmptyAndRetirable(ServerLevel level) {
        TownStorage storage = TownStorage.of(level.getServer());
        return !quarantined && escrow.isEmpty() && pending == null && binding != null
                && storage.isActiveDonationBox(binding) && storage.activeDonationOperation(binding).isEmpty();
    }
    boolean retireAfterRemoval(ServerLevel level) {
        return isEmptyAndRetirable(level) && TownStorage.of(level.getServer()).retireDonationBox(binding);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        TownStorage.DonationBoxBinding savedBinding = binding;
        if (savedBinding != null) { tag.putString("Town", savedBinding.townKey()); tag.putUUID("Box", savedBinding.boxId()); tag.putLong("Generation", savedBinding.generation()); }
        else if (savedBoxId != null) { tag.putString("Town", savedTown); tag.putUUID("Box", savedBoxId); tag.putLong("Generation", savedGeneration); }
        if (!escrow.isEmpty()) tag.put("Escrow", escrow.save(registries));
        TownStorage.DonationOperation savedOperation = pending;
        if (savedOperation != null) { tag.putLong("Sequence", savedOperation.sequence()); tag.putString("Item", savedOperation.payload().itemId()); tag.putInt("Count", savedOperation.payload().count()); tag.putString("Category", savedOperation.payload().category().name()); tag.putInt("Credit", savedOperation.payload().credit()); }
        else if (savedPayload != null) { tag.putLong("Sequence", savedSequence); tag.putString("Item", savedPayload.itemId()); tag.putInt("Count", savedPayload.count()); tag.putString("Category", savedPayload.category().name()); tag.putInt("Credit", savedPayload.credit()); }
        if (completedPayload != null) { tag.putLong("CompletedSequence", completedSequence); tag.putString("CompletedItem", completedPayload.itemId()); tag.putInt("CompletedCount", completedPayload.count()); tag.putString("CompletedCategory", completedPayload.category().name()); tag.putInt("CompletedCredit", completedPayload.credit()); }
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        escrow = tag.contains("Escrow") ? ItemStack.parseOptional(registries, tag.getCompound("Escrow")) : ItemStack.EMPTY;
        binding = null; pending = null; quarantined = false;
        savedPayload = null; completedPayload = null; savedSequence = 0; completedSequence = 0;
        savedBoxId = tag.hasUUID("Box") ? tag.getUUID("Box") : null;
        savedGeneration = tag.getLong("Generation"); savedTown = tag.getString("Town"); savedSequence = tag.getLong("Sequence");
        if (savedSequence > 0 && tag.contains("Item")) {
            try { savedPayload = new TownStorage.DonationPayload(tag.getString("Item"), tag.getInt("Count"),
                    TownResourceCategory.valueOf(tag.getString("Category")), tag.getInt("Credit")); }
            catch (IllegalArgumentException ignored) { quarantined = true; }
        }
        if (tag.getLong("CompletedSequence") > 0 && tag.contains("CompletedItem")) {
            try { completedSequence = tag.getLong("CompletedSequence"); completedPayload = new TownStorage.DonationPayload(tag.getString("CompletedItem"), tag.getInt("CompletedCount"), TownResourceCategory.valueOf(tag.getString("CompletedCategory")), tag.getInt("CompletedCredit")); }
            catch (IllegalArgumentException ignored) { quarantined = true; }
        }
    }
}
