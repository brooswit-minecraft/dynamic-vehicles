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
 */
final class PendingAsyncOpen<T extends Closeable> {
    private volatile boolean cancelled;
    private final AtomicReference<T> opened = new AtomicReference<>();
    private final CompletableFuture<T> future;

    /** Starts {@code opener} on {@code executor} immediately. {@code opener} may throw any
     * {@code RuntimeException} (e.g. wrap a checked exception in a {@code CompletionException}) to fail
     * the open -- that propagates through {@link #future} exactly like a plain {@code supplyAsync}. */
    PendingAsyncOpen(Supplier<T> opener, ExecutorService executor) {
        this.future = CompletableFuture.supplyAsync(() -> {
            T value = opener.get();
            // If we were told to stop before (or concurrently with) finishing, or some other caller of
            // cancel() already claimed ownership, nobody downstream is ever going to close this -- do it
            // ourselves rather than leak it. The CAS makes this race-safe against cancel() running on
            // another thread at the exact same moment.
            if (cancelled || !opened.compareAndSet(null, value)) {
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
    void cancel() {
        cancelled = true;
        future.cancel(true);
        T value = opened.getAndSet(null);
        if (value != null) {
            closeQuietly(value);
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
