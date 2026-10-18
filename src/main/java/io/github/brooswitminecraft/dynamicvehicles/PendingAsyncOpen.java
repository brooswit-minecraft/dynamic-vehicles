package io.github.brooswitminecraft.dynamicvehicles;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Tracks one in-flight "open a {@link Closeable} resource asynchronously" operation and guarantees that
 * resource gets closed if {@link #cancel} is called before anyone claims the result -- whichever side of
 * the race wins (MINECRAFT-394 PR #168 review finding #4: {@link RadioSoundInstance} previously started
 * an untracked {@code CompletableFuture.supplyAsync} to open a {@link RadioAudioStream}; if the radio was
 * stopped/switched while that connect was still in flight, nothing ever cancelled it or closed the
 * {@link RadioAudioStream} it eventually produced, leaking its decode thread and live socket).
 *
 * <p>Deliberately has no Minecraft-specific types so it can be unit tested with a fake slow opener and a
 * fake {@link Closeable}, driving the stop-before-open-completes race deterministically rather than
 * relying on real network timing.
 *
 * <p>The ownership decision is made by exactly one atomic operation on a single {@link AtomicReference}
 * (MINECRAFT-394 PR #168 second review round, finding #2) -- not by a separate {@code volatile boolean}
 * flag checked and then independently CAS'd against a second field. That older two-step shape ({@code if
 * (cancelled || !opened.compareAndSet(null, value)) close(value);} in the opener, paired with {@code
 * cancelled = true; T value = opened.getAndSet(null); ...} in {@code cancel()}) has a real gap between
 * "read {@code cancelled}" and "CAS {@code opened}": the opener can read {@code cancelled == false}, then
 * {@code cancel()} can run entirely (setting {@code cancelled = true} and finding {@code opened} still
 * null, concluding there is nothing to close) {@code before} the opener's own {@code compareAndSet}
 * finally runs and succeeds -- publishing {@code value} into {@code opened} where nothing will ever look
 * again, since the one {@code cancel()} call already happened and already decided it had nothing to do.
 * The resource leaks forever. Collapsing both roles onto one {@link AtomicReference}, where the opener's
 * publish attempt and {@code cancel()}'s claim attempt are each a single atomic CAS/getAndSet against the
 * very same field, removes that gap entirely: whichever one executes its atomic operation on {@code state}
 * first is unconditionally the side whose outcome the other observes, with no window where both can believe
 * they "won" with nothing left to clean up.
 */
final class PendingAsyncOpen<T extends Closeable> {
    /** Nobody has published or cancelled yet. */
    private static final Object PENDING = new Object();
    /** {@link #cancel} ran before the opener published anything. */
    private static final Object CANCELLED = new Object();

    /** One of {@link #PENDING}, {@link #CANCELLED}, or the published {@code T} value. The sole arbiter of
     * the opener-vs-cancel race: every transition out of {@link #PENDING} is a single atomic CAS/getAndSet
     * against this field, never a check against some other field first. */
    private final AtomicReference<Object> state = new AtomicReference<>(PENDING);
    private final CompletableFuture<T> future;

    /** Starts {@code opener} on {@code executor} immediately. {@code opener} may throw any
     * {@code RuntimeException} (e.g. wrap a checked exception in a {@code CompletionException}) to fail
     * the open -- that propagates through {@link #future} exactly like a plain {@code supplyAsync}. */
    PendingAsyncOpen(Supplier<T> opener, ExecutorService executor) {
        this.future = CompletableFuture.supplyAsync(() -> {
            T value = opener.get();
            // The single atomic decision point: this succeeds only if cancel() has not already run. If it
            // has (state is already CANCELLED), nobody downstream is ever going to look at `value` -- close
            // it ourselves rather than leak it. There is no separate flag read before this: whichever of
            // this compareAndSet or cancel()'s getAndSet executes first on `state` is authoritative.
            if (!state.compareAndSet(PENDING, value)) {
                closeQuietly(value);
            }
            return value;
        }, executor);
    }

    /** The eventual opened resource, or an exceptionally-completed future if {@code opener} threw.
     * Completing normally does NOT mean the resource is still open and unclaimed -- see {@link #cancel}. */
    CompletableFuture<T> future() {
        return future;
    }

    /**
     * Cancels the pending open (best-effort -- {@link CompletableFuture#cancel} does not forcibly
     * interrupt an async task already running) and, whether the open had already finished, was still in
     * flight, or hadn't started yet, ensures the resource it produces (or already produced) is closed
     * rather than left dangling. Idempotent and safe to call from any thread, including concurrently with
     * the open finishing.
     */
    @SuppressWarnings("unchecked")
    void cancel() {
        future.cancel(true);
        // Single atomic claim: whatever was published (if anything) before this ran is swapped out for
        // CANCELLED, and we close exactly that. If the opener hasn't published yet, this stores CANCELLED
        // first, so the opener's later compareAndSet(PENDING, value) is guaranteed to fail and close its
        // own value itself -- see the constructor. If cancel() is called again (or was already beaten to
        // the punch), `previous` is CANCELLED and this is a no-op, keeping cancel() idempotent.
        Object previous = state.getAndSet(CANCELLED);
        if (previous != PENDING && previous != CANCELLED) {
            closeQuietly((T) previous);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort cleanup; nothing left to do about a resource that won't close cleanly.
        }
    }
}
