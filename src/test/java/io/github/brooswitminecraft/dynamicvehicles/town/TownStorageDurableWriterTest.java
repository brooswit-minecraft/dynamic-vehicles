package io.github.brooswitminecraft.dynamicvehicles.town;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * MINECRAFT-454 [coordinator design 51290/51291, stage 1 part C - corrected per interim review
 * 51292] focused non-crash tests for {@link TownStorageDurableWriter}, the single ordered,
 * acknowledged writer for the existing TownStorage file. {@code TownStorageDurableWriter} is
 * deliberately Minecraft/NeoForge-agnostic (operates on plain {@code byte[]}, not NBT - see its
 * own class doc), which is what lets this file run as genuine, fast plain JUnit: this project's
 * plain {@code test} task deliberately excludes the Minecraft/NeoForge classpath (see
 * build.gradle), so nothing here may reference {@code CompoundTag}, {@code IOUtilities}, or any
 * other Minecraft/NeoForge type - every test below is REAL COMPONENT evidence about THIS class's
 * own ordering/acknowledgment/retry logic and (where noted) real filesystem behavior, never a mock
 * standing in for Minecraft/NeoForge itself. The one thing this file cannot prove - that the real
 * shared {@code net.neoforged.neoforge.common.IOUtilities} IO worker this class is wired to in
 * production actually drains it correctly - is proven instead, end to end with no fakes, as a
 * GameTest in {@code TownStorageDurableSaveGameTests} (compiled here, run only by the
 * coordinator's own GameTest pass, never by this worker).
 */
class TownStorageDurableWriterTest {
    private static byte[] bytesOf(int n) {
        return ("n=" + n).getBytes(StandardCharsets.UTF_8);
    }

    /** Runs each submitted task synchronously, immediately, on the calling thread - sufficient for
     *  any test that does not itself need genuine cross-thread asynchrony. */
    private static TownStorageDurableWriter.Ordering immediate() {
        return Runnable::run;
    }

    @Test
    void enqueuedWritesAreAcknowledgedInSubmissionOrderWithMonotonicRevisions() {
        List<byte[]> written = new ArrayList<>();
        TownStorageDurableWriter writer = new TownStorageDurableWriter(immediate(), (content, path) -> written.add(content));
        List<Long> acknowledgedRevisions = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            long revision = writer.enqueue(bytesOf(i), Path.of("ignored-" + i), (rev, outcome) -> {
                assertInstanceOf(TownStorageDurableWriter.WriteOutcome.Acknowledged.class, outcome);
                acknowledgedRevisions.add(rev);
            });
            assertEquals(i, revision, "revisions must be assigned in strict increasing order");
        }
        assertEquals(List.of(1L, 2L, 3L), acknowledgedRevisions, "acknowledgments must arrive in the same order they were submitted");
        assertEquals(3, written.size());
        assertEquals(3L, writer.persistedRevision());
        assertTrue(writer.isFullyPersisted());
        assertFalse(writer.needsRetry());
    }

    @Test
    void aFailedWriteIsReportedAsFailedAndLeavesARetryOwed() {
        IOException boom = new IOException("simulated disk failure");
        TownStorageDurableWriter writer = new TownStorageDurableWriter(immediate(), (content, path) -> {
            throw boom;
        });
        AtomicReference<TownStorageDurableWriter.WriteOutcome> outcomeRef = new AtomicReference<>();
        writer.enqueue(bytesOf(1), Path.of("ignored"), (rev, outcome) -> outcomeRef.set(outcome));

        assertInstanceOf(TownStorageDurableWriter.WriteOutcome.Failed.class, outcomeRef.get());
        assertEquals(boom, ((TownStorageDurableWriter.WriteOutcome.Failed) outcomeRef.get()).cause(),
                "the real cause must be propagated to the caller, never swallowed into a bare log line");
        assertTrue(writer.needsRetry(), "a failed write must leave an explicit retry owed");
        assertFalse(writer.isFullyPersisted());
        assertEquals(0L, writer.persistedRevision(), "a failed write must never be counted as persisted");
    }

    @Test
    void aFailedWriteIsRetriedWithWhateverIsCurrentOnTheNextEnqueueNeverTheStaleFailedBytes() {
        AtomicInteger attempt = new AtomicInteger();
        List<byte[]> actuallyWritten = new ArrayList<>();
        TownStorageDurableWriter writer = new TownStorageDurableWriter(immediate(), (content, path) -> {
            actuallyWritten.add(content);
            if (attempt.incrementAndGet() == 1) {
                throw new IOException("first attempt fails");
            }
        });

        writer.enqueue(bytesOf(1), Path.of("ignored"), (rev, outcome) -> { });
        assertTrue(writer.needsRetry());

        // Simulates the next save tick: current state has moved on since the failed attempt (a
        // real mutator would have called setDirty() again by now), so the retry carries revision
        // 2's CURRENT content, never a replay of revision 1's stale bytes.
        byte[] currentState = bytesOf(2);
        AtomicReference<TownStorageDurableWriter.WriteOutcome> secondOutcome = new AtomicReference<>();
        long secondRevision = writer.enqueue(currentState, Path.of("ignored"), (rev, outcome) -> secondOutcome.set(outcome));

        assertEquals(2L, secondRevision);
        assertInstanceOf(TownStorageDurableWriter.WriteOutcome.Acknowledged.class, secondOutcome.get());
        assertFalse(writer.needsRetry(), "a fresh successful enqueue must clear the owed retry");
        assertEquals(2L, writer.persistedRevision());
        assertEquals(2, actuallyWritten.size());
        assertArrayEquals(currentState, actuallyWritten.get(1), "the retry must write the CURRENT snapshot, never the stale failed one");
    }

    /** [coordinator review 51292, finding 3] Queues revisions 1 and 2 BEFORE either executes (a
     *  realistic case: two mutations happened before the ordered queue got to either one), then
     *  lets 1 FAIL and 2 SUCCEED. Retry obligation must be based on the latest successfully
     *  persisted revision versus the latest failure, never a flag that forgets a later superseding
     *  success merely because an earlier revision also happened to fail. */
    @Test
    void failingAnOlderQueuedRevisionThenSucceedingANewerOneLeavesNoRetryOwed() {
        List<Runnable> queuedTasks = new ArrayList<>();
        TownStorageDurableWriter writer = new TownStorageDurableWriter(queuedTasks::add, (content, path) -> {
            if (java.util.Arrays.equals(content, bytesOf(1))) {
                throw new IOException("revision 1's write fails");
            }
        });

        writer.enqueue(bytesOf(1), Path.of("ignored-1"), (rev, outcome) -> { });
        writer.enqueue(bytesOf(2), Path.of("ignored-2"), (rev, outcome) -> { });
        assertEquals(2, queuedTasks.size(), "test setup: both must be queued before either runs");

        queuedTasks.get(0).run(); // revision 1: fails
        assertTrue(writer.needsRetry(), "immediately after the older revision's own failure, a retry is correctly owed");

        queuedTasks.get(1).run(); // revision 2: succeeds, in the same real submission order
        assertFalse(writer.needsRetry(),
                "a later, newer revision's successful write must supersede an earlier revision's failure - the latest state IS persisted");
        assertTrue(writer.isFullyPersisted());
        assertEquals(2L, writer.persistedRevision());
    }

    @Test
    void aFailedWriteNeverPoisonsTheSharedOrderedQueueForLaterCallers() throws InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger attempt = new AtomicInteger();
            TownStorageDurableWriter writer = new TownStorageDurableWriter(pool::submit, (content, path) -> {
                if (attempt.incrementAndGet() == 1) {
                    throw new IOException("first write fails");
                }
            });
            CountDownLatch bothDone = new CountDownLatch(2);
            AtomicReference<TownStorageDurableWriter.WriteOutcome> first = new AtomicReference<>();
            AtomicReference<TownStorageDurableWriter.WriteOutcome> second = new AtomicReference<>();

            writer.enqueue(bytesOf(1), Path.of("ignored-1"), (rev, outcome) -> {
                first.set(outcome);
                bothDone.countDown();
            });
            writer.enqueue(bytesOf(2), Path.of("ignored-2"), (rev, outcome) -> {
                second.set(outcome);
                bothDone.countDown();
            });

            assertTrue(bothDone.await(5, TimeUnit.SECONDS), "both tasks must complete - the real executor must never be poisoned by the first task's exception");
            assertInstanceOf(TownStorageDurableWriter.WriteOutcome.Failed.class, first.get());
            assertInstanceOf(TownStorageDurableWriter.WriteOutcome.Acknowledged.class, second.get(),
                    "the SAME real single-thread executor must still run the next task normally after an earlier one's exception - nothing here may let that exception escape to the Ordering itself");
        } finally {
            pool.shutdown();
        }
    }

    /** [coordinator review 51292, finding 1] An unsupported atomic move must be reported as an
     *  explicit write FAILURE, never silently downgraded to an acknowledged, non-atomic success -
     *  {@code AtomicMoveNotSupportedException} is a real {@code IOException} subtype, so it flows
     *  through {@link TownStorageDurableWriter}'s own failure path exactly like any other write
     *  failure, with no special-cased fallback branch left anywhere to catch it. */
    /** [coordinator review 51294, finding 2] A rejected synchronous submission (the {@link
     *  TownStorageDurableWriter.Ordering} itself refusing new work, e.g. a real executor rejection)
     *  must be recorded as a failure (so {@link TownStorageDurableWriter#needsRetry()} reports
     *  {@code true} immediately) AND rethrown to the caller - {@code TownStorage} relies on this
     *  rethrow to avoid clearing {@code dirty} for a submission that was never actually accepted. */
    @Test
    void aRejectedSubmissionIsRecordedAsAFailureAndRethrownToTheCaller() {
        TownStorageDurableWriter writer = new TownStorageDurableWriter(
                task -> {
                    throw new java.util.concurrent.RejectedExecutionException("simulated: queue refusing new work");
                },
                (content, path) -> { });

        try {
            writer.enqueue(bytesOf(1), Path.of("ignored"), (rev, outcome) -> fail("a rejected submission must never reach the outcome callback at all"));
            fail("a rejected submission must propagate to the caller, never be silently swallowed");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            // Expected: the Ordering's own rejection, rethrown unchanged.
        }
        assertTrue(writer.needsRetry(), "a rejected submission must already be recorded as a retry-owed failure");
        assertEquals(0L, writer.persistedRevision());
    }

    @Test
    void anUnsupportedAtomicMoveIsReportedAsAnExplicitFailureNeverADegradedSuccess() {
        TownStorageDurableWriter writer = new TownStorageDurableWriter(immediate(), (content, path) -> {
            throw new AtomicMoveNotSupportedException("temp", path.toString(), "simulated: this filesystem cannot atomically replace");
        });
        AtomicReference<TownStorageDurableWriter.WriteOutcome> outcomeRef = new AtomicReference<>();
        writer.enqueue(bytesOf(1), Path.of("ignored"), (rev, outcome) -> outcomeRef.set(outcome));

        assertInstanceOf(TownStorageDurableWriter.WriteOutcome.Failed.class, outcomeRef.get(),
                "an unsupported atomic move must be a Failed outcome, never Acknowledged under any guise");
        assertTrue(writer.needsRetry());
        assertEquals(0L, writer.persistedRevision());
    }

    @Test
    void aLaterMutationEnqueuedWhileAnOlderWriteIsStillInFlightIsNeverLostBehindIt() throws InterruptedException {
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch bothAcknowledged = new CountDownLatch(2);
        List<Long> order = new ArrayList<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TownStorageDurableWriter writer = new TownStorageDurableWriter(pool::submit, (content, path) -> {
                if (new String(content, StandardCharsets.UTF_8).equals("n=1")) {
                    try {
                        assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS), "test setup: release signal must arrive");
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            writer.enqueue(bytesOf(1), Path.of("ignored-1"), (rev, outcome) -> {
                synchronized (order) {
                    order.add(rev);
                }
                bothAcknowledged.countDown();
            });
            // The later mutation: enqueued while revision 1's write is still blocked in flight.
            // Since the Ordering here is a real single-thread executor, this is a genuine
            // cross-thread race, not merely sequential test code.
            long secondRevision = writer.enqueue(bytesOf(2), Path.of("ignored-2"), (rev, outcome) -> {
                synchronized (order) {
                    order.add(rev);
                }
                bothAcknowledged.countDown();
            });
            assertEquals(2L, secondRevision);
            assertFalse(writer.isFullyPersisted(), "the in-flight first write must not be reported as fully persisted while revision 2 is still only queued");

            releaseFirstWrite.countDown();
            assertTrue(bothAcknowledged.await(5, TimeUnit.SECONDS), "both writes must eventually be acknowledged");

            assertEquals(List.of(1L, 2L), order, "the later mutation must never be processed ahead of (or lose) the older in-flight write");
            assertEquals(2L, writer.persistedRevision());
            assertTrue(writer.isFullyPersisted());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void realWriteAtomicallyReallyReplacesTheTargetFileAndLeavesNoTempFileBehind(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("dynamicvehicles_towns.dat");
        Files.writeString(target, "stale-pre-existing-content");

        TownStorageDurableWriter.AtomicByteWriter.real().write(bytesOf(42), target);

        assertArrayEquals(bytesOf(42), Files.readAllBytes(target));
        try (var listing = Files.list(tempDir)) {
            List<Path> leftovers = listing.filter(p -> p.getFileName().toString().endsWith(".dynamicvehicles-tmp")).toList();
            assertTrue(leftovers.isEmpty(), "no temp file must be left behind after a successful write: " + leftovers);
        }
    }

    @Test
    void realWriteAtomicallyPropagatesARealIoFailureRatherThanSwallowingIt(@TempDir Path tempDir) {
        Path target = tempDir.resolve("missing-parent-directory").resolve("dynamicvehicles_towns.dat");
        try {
            TownStorageDurableWriter.AtomicByteWriter.real().write(bytesOf(1), target);
            fail("writing under a non-existent parent directory must raise a real IOException, never silently succeed");
        } catch (IOException expected) {
            // Expected: the parent directory for the temp file does not exist.
        }
    }

    /** [coordinator review 51292, finding 1] When the real final replace genuinely fails (here:
     *  the target is itself a directory, so {@code ATOMIC_MOVE} fails with a real {@code
     *  FileSystemException} - a reliably reproducible way to force that specific step to fail on
     *  any filesystem, unlike provoking an actual {@code AtomicMoveNotSupportedException}), the
     *  pre-existing target must be left COMPLETELY untouched - never partially replaced, never
     *  silently downgraded to a non-atomic overwrite. */
    @Test
    void writeAtomicallyLeavesAPreexistingTargetCompletelyIntactWhenTheFinalReplaceFails(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("dynamicvehicles_towns.dat");
        Files.createDirectory(target);
        Path marker = target.resolve("marker.txt");
        Files.writeString(marker, "this directory must survive untouched");

        try {
            TownStorageDurableWriter.AtomicByteWriter.real().write(bytesOf(99), target);
            fail("replacing a directory with ATOMIC_MOVE must fail, never silently succeed");
        } catch (IOException expected) {
            // Expected: a directory cannot be atomically replaced by a regular file this way.
        }

        assertTrue(Files.isDirectory(target), "the pre-existing target must still be exactly what it was before the failed replace");
        assertEquals("this directory must survive untouched", Files.readString(marker), "the pre-existing target's own content must be completely untouched");
        try (var listing = Files.list(tempDir)) {
            List<Path> leftovers = listing.filter(p -> p.getFileName().toString().endsWith(".dynamicvehicles-tmp")).toList();
            assertTrue(leftovers.isEmpty(), "no temp file must be left behind after a failed write either: " + leftovers);
        }
    }

    @Test
    void enqueueThroughTheRealAtomicByteWriterEndToEndActuallyPersistsOnThisRealFilesystem(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("dynamicvehicles_towns.dat");
        TownStorageDurableWriter writer = new TownStorageDurableWriter(immediate(), TownStorageDurableWriter.AtomicByteWriter.real());
        AtomicReference<TownStorageDurableWriter.WriteOutcome> outcomeRef = new AtomicReference<>();

        writer.enqueue(bytesOf(7), target, (rev, outcome) -> outcomeRef.set(outcome));

        assertInstanceOf(TownStorageDurableWriter.WriteOutcome.Acknowledged.class, outcomeRef.get());
        assertTrue(Files.exists(target));
        assertArrayEquals(bytesOf(7), Files.readAllBytes(target));
    }
}
