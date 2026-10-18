package io.github.brooswitminecraft.dynamicvehicles;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The producer/consumer buffer and mono-downmix math behind {@link RadioAudioStream#read} (MINECRAFT-394
 * PR #168 review findings #1-3) -- deliberately has no Minecraft/LWJGL imports at all, so it can be unit
 * tested directly without the client classpath those pull in (this repo's plain unit tests, unlike its
 * GameTests, run without one -- {@code net.minecraft.client.sounds.AudioStream} is not resolvable there).
 *
 * <p>One background decode thread calls {@link #put} (blocking itself, never a reader) to push decoded
 * PCM chunks in; whoever is pumping the stream (vanilla's shared sound executor, for the real {@link
 * RadioAudioStream}) calls {@link #drain} to pull bytes out, which never blocks -- it returns whatever is
 * already queued immediately, even a short/empty read, and signals real end-of-stream (decode thread
 * finished via {@link #markEndOfStream} and every queued chunk has been drained) by returning a buffer
 * with nothing left to extend, see {@link #drain}'s own return-value contract spelled out there.
 */
final class PcmChunkPipe {
    private final BlockingQueue<byte[]> chunks;
    private final AtomicBoolean endOfStream = new AtomicBoolean(false);

    /** Chunk {@link #drain} is currently partway through handing out, and how far into it. Only ever
     * touched by whatever single thread calls {@link #drain} -- never concurrently for one pipe. */
    private byte[] leftover;
    private int leftoverPos;

    PcmChunkPipe(int queueCapacityChunks) {
        this.chunks = new ArrayBlockingQueue<>(queueCapacityChunks);
    }

    /** Test-only: wrap an already-populated queue directly, to exercise {@link #drain}'s contract without
     * a real decode thread ever running. */
    PcmChunkPipe(BlockingQueue<byte[]> prefilledChunks) {
        this.chunks = prefilledChunks;
    }

    /** Called only by the background decode thread; blocks (that thread only) if the queue is
     * momentarily full, which is the backpressure that keeps a fast decoder from outrunning memory. */
    void put(byte[] chunk) throws InterruptedException {
        chunks.put(chunk);
    }

    /** Called only by the background decode thread, once, when it is done (clean end of stream, a decode
     * error, or interrupted by {@link RadioAudioStream#close}) -- the real end-of-stream signal for
     * {@link #drain}, as opposed to the queue merely being momentarily empty. */
    void markEndOfStream() {
        endOfStream.set(true);
    }

    /**
     * Returns up to {@code size} bytes of already-queued PCM as a <strong>direct</strong> little-endian
     * {@link ByteBuffer} (direct because the real caller, {@code Channel.pumpBuffers}, passes this
     * straight into a native {@code AL10.alBufferData} call that requires one), without ever blocking.
     *
     * <p>If nothing is queued right now but {@link #markEndOfStream} has not been called, this returns a
     * zero-length buffer -- a valid short/partial read, not end of stream, since the decode thread may
     * simply not have produced anything yet. Only once {@link #markEndOfStream} has been called AND every
     * already-queued chunk has been fully drained does this return {@code null} -- genuine end of stream.
     */
    ByteBuffer drain(int size) {
        ByteBuffer out = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        int remaining = size;
        while (remaining > 0) {
            if (leftover == null || leftoverPos >= leftover.length) {
                byte[] next = chunks.poll();
                if (next == null) {
                    break;
                }
                leftover = next;
                leftoverPos = 0;
            }
            int toCopy = Math.min(leftover.length - leftoverPos, remaining);
            out.put(leftover, leftoverPos, toCopy);
            leftoverPos += toCopy;
            remaining -= toCopy;
        }
        out.flip();
        if (out.remaining() == 0 && endOfStream.get() && chunks.isEmpty()) {
            return null;
        }
        return out;
    }

    /**
     * Downmixes {@code length} interleaved stereo samples (L, R, L, R, ...) in {@code interleaved} to mono
     * by averaging each L/R pair. The sum is widened to {@code int} before narrowing back to {@code short}
     * so a loud sample pair near {@code Short.MAX_VALUE}/{@code MIN_VALUE} cannot overflow during the add.
     * OpenAL only applies 3D distance attenuation/panning to mono ({@code AL_FORMAT_MONO16}) buffers, not
     * stereo ones, which is why every stereo station must be downmixed before it ever reaches OpenAL.
     *
     * @param length the number of valid samples in {@code interleaved} (which may be larger than the data
     *      actually in use, as JLayer's {@code SampleBuffer} reuses its backing array) -- must be even.
     */
    static short[] downmixToMono(short[] interleaved, int length) {
        short[] mono = new short[length / 2];
        for (int i = 0, m = 0; i < length; i += 2, m++) {
            int sum = interleaved[i] + interleaved[i + 1];
            mono[m] = (short) (sum / 2);
        }
        return mono;
    }

    /** Converts {@code length} samples of {@code samples} to little-endian 16-bit PCM bytes, suitable for
     * {@link #put}. */
    static byte[] toLittleEndianBytes(short[] samples, int length) {
        byte[] bytes = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            short sample = samples[i];
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return bytes;
    }
}
