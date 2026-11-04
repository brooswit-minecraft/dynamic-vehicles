package io.github.brooswitminecraft.dynamicvehicles.town;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * [coordinator design 51290/51291, stage 1 part A - corrected per interim review 51292] The single
 * ordered, acknowledged writer for the EXISTING {@code TownStorage} file - both ordinary (engine
 * autosave) and explicit (this mod's own checkpoint call) saves route through one {@code
 * TownStorage#save(java.io.File, net.minecraft.core.HolderLookup.Provider)} override, which is
 * this class's only caller. There is no second, competing writer for this file anywhere.
 *
 * <p>Deliberately operates on a plain pre-serialized {@code byte[]} rather than NBT directly: this
 * class owns ordering, acknowledgment, retry, and the atomic-replace mechanics only, and otherwise
 * has NO Minecraft/NeoForge dependency at all, which is what lets {@code
 * TownStorageDurableWriterTest} exercise it with real file I/O as genuine, fast plain JUnit (this
 * project's plain {@code test} task deliberately excludes the Minecraft/NeoForge classpath - see
 * build.gradle). {@code TownStorage}'s own override does the NBT-specific serialization and hands
 * this class the resulting bytes.
 *
 * <p>What this closes: vanilla's own {@code SavedData#save(File, Provider)} hands the write off to
 * {@code IOUtilities.withIOWorker} and clears its {@code dirty} flag immediately, before the actual
 * disk write even starts - a failed write is only ever logged, never retried, and the caller has no
 * way to know it happened. This writer instead tracks, independently of the base class's simple
 * {@code dirty} boolean, whether the LATEST captured snapshot has actually been acknowledged
 * (written and replaced on disk) yet, and whether the CURRENT (latest-known) state still owes a
 * retry - based on the latest successfully persisted revision versus the latest revision with a
 * recorded failure, NOT on a flag that forgets a later superseding success (review 51292, finding
 * 3: queuing revisions 1 and 2, failing 1, then succeeding 2, must leave no retry owed - the
 * earlier failure is superseded, not merely masked by a bigger counter).
 *
 * <p>Atomic replacement is REQUIRED, not best-effort (review 51292, finding 1): unlike {@code
 * net.neoforged.neoforge.common.IOUtilities.atomicWrite}, which silently falls back to a
 * non-atomic {@code REPLACE_EXISTING} when {@code ATOMIC_MOVE} is unsupported, {@link
 * #writeAtomically} never performs that fallback at all - an unsupported atomic move is reported
 * as an explicit write FAILURE (the previously-committed target file is left completely
 * untouched), never as a weaker-but-still-acknowledged success.
 *
 * <p>What this does NOT close (see the design note and MINECRAFT-454 row 51264): this is a durable
 * WRITE-ORDERING primitive for one file, not a cross-file transaction. A crash between "this
 * writer's temp file was moved into place" and "the OS actually persisted that rename" is outside
 * what any user-space primitive can prove.
 *
 * <p>Deliberately reuses {@code net.neoforged.neoforge.common.IOUtilities}'s own single ordered IO
 * queue (wired by {@code TownStorage}) for the actual write, rather than introducing a second,
 * competing background thread: every write this class submits is automatically ordered relative to
 * (and drained together with) every other mod's {@code SavedData} write through the exact same
 * engine primitive. Every exception this class's own write can raise is caught INSIDE the
 * submitted task (never rethrown to the {@link Ordering} itself), so a failure here can never
 * poison that shared queue for any other caller - proven in {@code
 * TownStorageDurableWriterTest#aFailedWriteNeverPoisonsTheSharedOrderedQueueForLaterCallers}.
 */
final class TownStorageDurableWriter {
    /** The single ordered queue the actual write runs on. Production wires this to the engine's
     *  own shared IO worker (see {@code TownStorage}); tests substitute a locally-scoped,
     *  deterministic one so this class's own ordering/acknowledgment logic can be verified without
     *  depending on - or interfering with - that process-wide shared queue. */
    @FunctionalInterface
    interface Ordering {
        void submit(Runnable task);
    }

    /** The actual byte-level write for one snapshot - always either a genuinely atomic replace or
     *  a thrown {@link IOException}; see this class's own doc for why a non-atomic fallback is
     *  never an acceptable third outcome. {@link #real()} is the production implementation (temp
     *  file in the same directory, forced to disk, then replaced into place ONLY via an atomic
     *  move); tests substitute a fake to deterministically exercise the failure path without
     *  depending on real filesystem quirks that cannot be reliably provoked in CI. */
    @FunctionalInterface
    interface AtomicByteWriter {
        void write(byte[] content, Path targetPath) throws IOException;

        static AtomicByteWriter real() {
            return TownStorageDurableWriter::writeAtomically;
        }
    }

    /** The outcome of one enqueued write, delivered exactly once per {@link #enqueue}, strictly in
     *  submission order (same guarantee the underlying {@link Ordering} provides). */
    sealed interface WriteOutcome {
        record Acknowledged(long revision) implements WriteOutcome {}

        record Failed(long revision, IOException cause) implements WriteOutcome {}
    }

    private final Ordering ordering;
    private final AtomicByteWriter atomicByteWriter;
    private final AtomicLong nextRevision = new AtomicLong();
    private volatile long queuedRevision;
    private final AtomicLong persistedRevision = new AtomicLong();
    /** The highest revision whose outcome was a FAILURE that has not since been superseded by a
     *  successful write of that same or a later revision - {@code 0} means no outstanding failure.
     *  See {@link #needsRetry()}; this is what review 51292's finding 3 required: retry obligation
     *  is based on comparing this against {@link #persistedRevision}, never a sticky flag that a
     *  later success forgets to clear. */
    private final AtomicLong failedRevision = new AtomicLong();

    TownStorageDurableWriter(Ordering ordering, AtomicByteWriter atomicByteWriter) {
        this.ordering = ordering;
        this.atomicByteWriter = atomicByteWriter;
    }

    /** Captures {@code content} (already serialized; the caller took the snapshot it reflects on
     *  the server thread before calling this) as the next monotonic revision, and enqueues it for
     *  an ordered, acknowledged write to {@code targetPath}. {@code onOutcome} is invoked exactly
     *  once, on whichever thread the {@link Ordering} actually runs the write on - never on the
     *  calling thread synchronously - so it must never touch anything that is only safe to mutate
     *  from the server thread (see {@code TownStorage}'s own override, which keeps its base {@code
     *  dirty} flag server-thread-only for exactly this reason). Returns the assigned revision.
     *
     *  <p>[coordinator review 51294, finding 2] If {@link Ordering#submit} itself throws
     *  synchronously (a real executor rejecting new work, say) - BEFORE the write was ever
     *  accepted - this revision is immediately recorded as a failure (so {@link #needsRetry()}
     *  correctly reports {@code true} right away) and the exception is rethrown to the caller,
     *  which decides how to report that to ITS OWN caller (see {@code TownStorage}'s own catch
     *  around this call: submission being rejected must never be confused with "accepted and now
     *  in flight," which is exactly why {@code dirty} must not be cleared until this call returns
     *  normally). */
    long enqueue(byte[] content, Path targetPath, BiConsumer<Long, WriteOutcome> onOutcome) {
        long revision = nextRevision.incrementAndGet();
        queuedRevision = revision;
        try {
            submit(revision, content, targetPath, onOutcome);
        } catch (RuntimeException submissionRejected) {
            failedRevision.set(revision);
            throw submissionRejected;
        }
        return revision;
    }

    private void submit(long revision, byte[] content, Path targetPath, BiConsumer<Long, WriteOutcome> onOutcome) {
        ordering.submit(() -> {
            WriteOutcome outcome;
            try {
                atomicByteWriter.write(content, targetPath);
                // Writes are strictly FIFO (the Ordering's own contract - single ordered queue, no
                // competing writer), so completions for increasing revisions arrive in the same
                // increasing order: a plain set (not a max-merge) is correct here, and a max-merge
                // would wrongly suggest out-of-order completion is an expected, safely-handled case
                // rather than the ordering violation it would actually be.
                persistedRevision.set(revision);
                // This success supersedes any earlier recorded failure (review 51292, finding 3):
                // only clear it if THIS revision is at least as new as the recorded failure, so a
                // genuinely newer, still-outstanding failure (success then a LATER failure) is
                // never wrongly cleared by an older success completing after it could not happen
                // under FIFO, but guarded explicitly rather than assumed.
                failedRevision.updateAndGet(failed -> revision >= failed ? 0 : failed);
                outcome = new WriteOutcome.Acknowledged(revision);
            } catch (IOException e) {
                failedRevision.set(revision);
                outcome = new WriteOutcome.Failed(revision, e);
            }
            onOutcome.accept(revision, outcome);
        });
    }

    /** The revision most recently enqueued (captured, not necessarily yet written). */
    long queuedRevision() {
        return queuedRevision;
    }

    /** The highest revision actually acknowledged (written and replaced on disk) so far. */
    long persistedRevision() {
        return persistedRevision.get();
    }

    /** Whether every snapshot enqueued so far has been acknowledged and nothing is outstanding -
     *  {@code false} while a write is still in flight, and {@code false} after a failure until a
     *  later success (of that same or a newer revision) supersedes it. */
    boolean isFullyPersisted() {
        return failedRevision.get() == 0 && persistedRevision.get() >= queuedRevision;
    }

    /** Whether the CURRENT state still owes a retry: there exists a recorded failure that no
     *  later success has superseded. Per review 51292's finding 3: queuing revisions 1 and 2,
     *  failing 1, then succeeding 2, must report {@code false} here - the latest successfully
     *  persisted revision (2) already reflects current state, so revision 1's earlier failure is
     *  moot, never a reason to retry already-superseded work. */
    boolean needsRetry() {
        return failedRevision.get() > persistedRevision.get();
    }

    private static final String TEMP_FILE_SUFFIX = ".dynamicvehicles-tmp";

    /** Writes {@code content} to a temp file in {@code targetPath}'s own directory, forces it to
     *  disk, and only then replaces {@code targetPath} via a genuinely atomic move. Unlike {@code
     *  net.neoforged.neoforge.common.IOUtilities.atomicWrite} (which this otherwise mirrors - same
     *  temp-file-then-force-then-move strategy), an unsupported atomic move is NEVER silently
     *  downgraded to a non-atomic replace: {@code AtomicMoveNotSupportedException} is left to
     *  propagate as the real write failure it is, and the previously-committed {@code targetPath}
     *  is left completely untouched (only the abandoned temp file is cleaned up). Takes
     *  already-serialized bytes rather than NBT so this class stays entirely Minecraft-agnostic -
     *  see this class's own doc. */
    private static void writeAtomically(byte[] content, Path targetPath) throws IOException {
        Path parent = targetPath.toAbsolutePath().getParent();
        Path tempPath = Files.createTempFile(parent, targetPath.getFileName().toString(), TEMP_FILE_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(tempPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                // Written directly through the channel (never via Channels.newOutputStream): that
                // wrapper's own close() would close the underlying channel too, which would then
                // make the force(true) below fail with a ClosedChannelException - caught by this
                // class's own real-filesystem test, not merely reasoned about.
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // Ensure the bytes are actually on disk before the rename below makes them visible
                // as this file's own content - a crash between these two steps abandons only the
                // temp file, never corrupts the previously-committed targetPath.
                channel.force(true);
            }
            // No fallback to StandardCopyOption.REPLACE_EXISTING on failure here: an unsupported
            // atomic move must surface as a real write failure (propagated by the catch below),
            // never as a quietly-downgraded non-atomic overwrite of targetPath.
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception first) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (Exception second) {
                first.addSuppressed(second);
            }
            if (first instanceof IOException io) {
                throw io;
            }
            throw new IOException(first);
        }
    }
}
