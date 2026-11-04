package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MINECRAFT-454 [coordinator review 51294, stage 1 part A]: deterministic tests at the production
 * {@code TownStorage} save boundary for the pending-checkpoint-result state correction - review
 * 51294's own finding 1 ("a clean-but-inflight explicit request returns NothingToDo") and finding
 * 2 ("dirty still clears before enqueue rejection can occur"). Every test here injects only the
 * minimal {@code TownStorageDurableWriter.Ordering}/{@code AtomicByteWriter} seam (via {@code
 * TownStorage}'s package-private test constructor) to make pending/in-flight timing fully
 * deterministic - no real filesystem or thread-pool races, no sleeps. Compiled only; NOT executed
 * by this worker, per the standing instruction and review 51294's own "new GameTests remain
 * unexecuted until this correctness review clears."
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownStorageCheckpointPendingStateGameTests {
    private TownStorageCheckpointPendingStateGameTests() {}

    private static TownStorageDurableWriter.Ordering queueing(List<Runnable> queuedTasks) {
        return queuedTasks::add;
    }

    private static TownStorage.CheckpointResult get(GameTestHelper helper, CompletableFuture<TownStorage.CheckpointResult> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            helper.fail("test infrastructure: checkpoint result future never completed - " + e);
            throw new AssertionError(e);
        }
    }

    /** (1) Ordinary save blocked in queue; an explicit checkpoint made while it is still pending
     *  must observe that SAME attempt, never an immediate {@code NothingToDo} - then, once the
     *  write actually completes, both must agree it succeeded. */
    @GameTest(template = "settlement_test")
    public static void explicitCheckpointDuringAPendingOrdinarySaveObservesTheSameEventualSuccess(GameTestHelper helper) {
        List<Runnable> queuedTasks = new ArrayList<>();
        TownStorage storage = new TownStorage(new TownStorageDurableWriter(queueing(queuedTasks), (content, path) -> { }));
        File file = new File("ignored-pending-success.dat");
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        storage.markTownFounded("minecraft:overworld:0:0");
        storage.save(file, registries);
        helper.assertTrue(!storage.isDirty(), "test setup: the ordinary save must have captured and accepted its snapshot");
        helper.assertTrue(queuedTasks.size() == 1, "test setup: exactly one write must be pending");

        CompletableFuture<TownStorage.CheckpointResult> explicitCheckpoint = storage.requestAcknowledgedCheckpoint(file, registries);
        helper.assertTrue(!explicitCheckpoint.isDone(), "the explicit checkpoint must observe the SAME still-pending attempt, not resolve immediately");
        helper.assertTrue(queuedTasks.size() == 1, "merely asking for the pending result must never enqueue a second, redundant write");

        queuedTasks.get(0).run(); // the ordinary save's own write actually completes now
        TownStorage.CheckpointResult outcome = get(helper, explicitCheckpoint);
        helper.assertTrue(outcome instanceof TownStorage.CheckpointResult.Persisted,
                "the explicit checkpoint must observe the SAME real success the ordinary save's own write produced - found " + outcome);
        helper.succeed();
    }

    /** (2) Same setup as (1), but the pending write actually fails - the explicit checkpoint must
     *  observe that SAME real failure, never a stale success or a premature no-op. */
    @GameTest(template = "settlement_test")
    public static void explicitCheckpointDuringAPendingOrdinarySaveObservesTheSameEventualFailure(GameTestHelper helper) {
        List<Runnable> queuedTasks = new ArrayList<>();
        IOException boom = new IOException("simulated write failure");
        TownStorage storage = new TownStorage(new TownStorageDurableWriter(queueing(queuedTasks), (content, path) -> {
            throw boom;
        }));
        File file = new File("ignored-pending-failure.dat");
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        storage.markTownFounded("minecraft:overworld:0:0");
        storage.save(file, registries);
        helper.assertTrue(!storage.isDirty(), "test setup: the ordinary save must have captured and accepted its snapshot");
        helper.assertTrue(queuedTasks.size() == 1, "test setup: exactly one write must be pending");

        CompletableFuture<TownStorage.CheckpointResult> explicitCheckpoint = storage.requestAcknowledgedCheckpoint(file, registries);
        helper.assertTrue(!explicitCheckpoint.isDone(), "the explicit checkpoint must observe the SAME still-pending attempt");

        queuedTasks.get(0).run(); // the pending write now actually fails
        TownStorage.CheckpointResult outcome = get(helper, explicitCheckpoint);
        helper.assertTrue(outcome instanceof TownStorage.CheckpointResult.WriteFailed,
                "the explicit checkpoint must observe the SAME real failure, never Persisted or NothingToDo - found " + outcome);
        helper.succeed();
    }

    /** (3) The ordered queue itself rejects a submission - dirty and a retry remain owed, the
     *  result is an explicit failure, and a SUBSEQUENT, accepted submission succeeds normally. */
    @GameTest(template = "settlement_test")
    public static void aRejectedSubmissionPreservesDirtyAndRetryThenASubsequentAcceptedSaveSucceeds(GameTestHelper helper) {
        AtomicBoolean reject = new AtomicBoolean(true);
        TownStorageDurableWriter.Ordering flaky = task -> {
            if (reject.get()) {
                throw new RejectedExecutionException("simulated: queue refusing new work");
            }
            task.run();
        };
        TownStorage storage = new TownStorage(new TownStorageDurableWriter(flaky, (content, path) -> { }));
        File file = new File("ignored-rejected-submission.dat");
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        storage.markTownFounded("minecraft:overworld:0:0");
        CompletableFuture<TownStorage.CheckpointResult> first = storage.requestAcknowledgedCheckpoint(file, registries);
        helper.assertTrue(storage.isDirty(), "a rejected submission must leave dirty untouched (still true) - it was never actually accepted");
        TownStorage.CheckpointResult firstOutcome = get(helper, first);
        helper.assertTrue(firstOutcome instanceof TownStorage.CheckpointResult.SubmissionRejected,
                "a rejected submission must be reported as an explicit failure, never Persisted or a silent no-op - found " + firstOutcome);

        reject.set(false);
        CompletableFuture<TownStorage.CheckpointResult> second = storage.requestAcknowledgedCheckpoint(file, registries);
        helper.assertTrue(!storage.isDirty(), "the subsequent, accepted submission must clear dirty normally");
        TownStorage.CheckpointResult secondOutcome = get(helper, second);
        helper.assertTrue(secondOutcome instanceof TownStorage.CheckpointResult.Persisted,
                "a subsequent, accepted submission with current state must succeed normally - found " + secondOutcome);
        helper.succeed();
    }

    /** (4) [coordinator review 51300 correction] A new mutation happens AFTER snapshot 1 is
     *  captured (while it is still pending) - the new mutation must mark dirty again. The OLDER
     *  snapshot 1 is then completed WHILE that newer dirty=true state is still actually true (not
     *  after it has already been cleared by capturing snapshot 2 - the previous round's version of
     *  this test completed snapshot 1 too late to actually exercise "newer dirty survives an older
     *  completion," since dirty was already false by then for an unrelated reason). Only after
     *  that is snapshot 2 captured (clearing dirty again) and completed. The recorded, actually
     *  gzip-compressed snapshot bytes are decoded back into real {@code TownStorage} instances and
     *  checked by CONTENT - snapshot 1 must lack mutation 2 and snapshot 2 must include both -
     *  rather than merely counting how many byte arrays were written. */
    @GameTest(template = "settlement_test")
    public static void aNewerMutationDuringAPendingSnapshotGetsItsOwnSnapshotAndTheOlderCompletionNeverClobbersIt(GameTestHelper helper) {
        List<Runnable> queuedTasks = new ArrayList<>();
        List<byte[]> written = new ArrayList<>();
        TownStorage storage = new TownStorage(new TownStorageDurableWriter(queueing(queuedTasks), (content, path) -> written.add(content)));
        File file = new File("ignored-newer-mutation.dat");
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        storage.markTownFounded("minecraft:overworld:0:0"); // mutation 1
        storage.save(file, registries); // snapshot 1 captured and enqueued, still pending
        helper.assertTrue(!storage.isDirty(), "test setup: snapshot 1 must have been captured and accepted");
        helper.assertTrue(queuedTasks.size() == 1, "test setup: snapshot 1 must be the only pending write so far");

        storage.markTownFounded("minecraft:overworld:1:0"); // mutation 2, while snapshot 1 is still pending
        helper.assertTrue(storage.isDirty(), "a new mutation after an in-flight snapshot must mark dirty again");

        // Complete the OLDER snapshot 1 NOW, while dirty is still genuinely true from mutation 2 -
        // this is the actual case under test, not merely a formality.
        queuedTasks.get(0).run();
        helper.assertTrue(storage.isDirty(), "the OLDER snapshot 1's own completion must never clear the NEWER dirty state from mutation 2, which is still real at this point");

        CompletableFuture<TownStorage.CheckpointResult> duringNewMutation = storage.requestAcknowledgedCheckpoint(file, registries);
        helper.assertTrue(queuedTasks.size() == 2, "the new mutation must get its OWN new snapshot/task, never reuse snapshot 1's already-completed one");
        helper.assertTrue(!storage.isDirty(), "capturing snapshot 2 (AFTER snapshot 1's completion) must clear dirty again");

        queuedTasks.get(1).run(); // snapshot 2 (current, reflecting BOTH mutations) completes
        TownStorage.CheckpointResult outcome = get(helper, duringNewMutation);
        helper.assertTrue(outcome instanceof TownStorage.CheckpointResult.Persisted,
                "snapshot 2 - the one actually reflecting current state - must be the one this call observes as persisted - found " + outcome);

        helper.assertTrue(written.size() == 2, "both snapshots must have actually been written, in order");
        TownStorage snapshot1Decoded = decode(written.get(0), registries);
        TownStorage snapshot2Decoded = decode(written.get(1), registries);
        helper.assertTrue(snapshot1Decoded.hasFoundedTown("minecraft:overworld:0:0") && !snapshot1Decoded.hasFoundedTown("minecraft:overworld:1:0"),
                "snapshot 1's own ACTUAL decoded content must reflect ONLY mutation 1 - it was captured before mutation 2 ever happened");
        helper.assertTrue(snapshot2Decoded.hasFoundedTown("minecraft:overworld:0:0") && snapshot2Decoded.hasFoundedTown("minecraft:overworld:1:0"),
                "snapshot 2's own ACTUAL decoded content must reflect BOTH mutations");
        helper.succeed();
    }

    /** Decodes one recorded snapshot's real gzip-compressed NBT bytes (exactly what {@code
     *  TownStorage#saveAndReportOutcome} actually serializes) back into a real {@code
     *  TownStorage}, so a test can assert by ACTUAL CONTENT rather than merely counting how many
     *  byte arrays were written. */
    private static TownStorage decode(byte[] content, HolderLookup.Provider registries) {
        try {
            CompoundTag wrapper = net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(content), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return TownStorage.load(wrapper.getCompound("data"), registries);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** (5) [coordinator review 51294, test seam] A capture failure (forced here via a package-
     *  private test subclass overriding the data-level {@code save(CompoundTag, Provider)} once -
     *  see {@code TownStorage}'s own class doc for why the class is no longer {@code final}) must
     *  leave dirty untouched and never reach the writer at all; the VERY NEXT attempt (capture no
     *  longer forced to fail) must retry with current state and actually succeed. */
    @GameTest(template = "settlement_test")
    public static void aCaptureFailureLeavesDirtyUntouchedAndTheNextAttemptActuallyRetriesAndSucceeds(GameTestHelper helper) {
        List<Runnable> queuedTasks = new ArrayList<>();
        TownStorage storage = new ThrowOnceOnCaptureTownStorage(new TownStorageDurableWriter(queueing(queuedTasks), (content, path) -> { }));
        File file = new File("ignored-capture-failure.dat");
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        ThrowOnceOnCaptureTownStorage throwing = (ThrowOnceOnCaptureTownStorage) storage;

        storage.markTownFounded("minecraft:overworld:0:0");
        throwing.throwOnNextCapture = true;
        CompletableFuture<TownStorage.CheckpointResult> failedCapture = storage.requestAcknowledgedCheckpoint(file, registries);
        helper.assertTrue(storage.isDirty(), "a capture failure must leave dirty untouched");
        helper.assertTrue(queuedTasks.isEmpty(), "a capture failure must never reach the writer at all");
        TownStorage.CheckpointResult outcome = get(helper, failedCapture);
        helper.assertTrue(outcome instanceof TownStorage.CheckpointResult.CaptureFailed,
                "a forced capture failure must be reported as CaptureFailed - found " + outcome);

        CompletableFuture<TownStorage.CheckpointResult> retried = storage.requestAcknowledgedCheckpoint(file, registries);
        helper.assertTrue(!storage.isDirty(), "the retry's own successful capture must clear dirty normally");
        helper.assertTrue(queuedTasks.size() == 1, "the retry must actually reach the writer this time");
        queuedTasks.get(0).run();
        TownStorage.CheckpointResult retriedOutcome = get(helper, retried);
        helper.assertTrue(retriedOutcome instanceof TownStorage.CheckpointResult.Persisted,
                "the retry must actually succeed with current state - found " + retriedOutcome);
        helper.succeed();
    }

    /** Test-only subclass: throws exactly once from the data-level {@code save(CompoundTag,
     *  Provider)} that {@code TownStorage}'s own save-boundary capture step calls, to
     *  deterministically force test (5)'s capture failure without adding a dedicated
     *  "simulate failure" method to production API surface. */
    private static final class ThrowOnceOnCaptureTownStorage extends TownStorage {
        private boolean throwOnNextCapture;

        ThrowOnceOnCaptureTownStorage(TownStorageDurableWriter durableWriter) {
            super(durableWriter);
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            if (throwOnNextCapture) {
                throwOnNextCapture = false;
                throw new RuntimeException("simulated capture failure");
            }
            return super.save(tag, registries);
        }
    }
}
