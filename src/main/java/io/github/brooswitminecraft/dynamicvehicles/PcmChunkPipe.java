package io.github.brooswitminecraft.dynamicvehicles;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The producer/consumer buffer and mono-downmix math behind {@link RadioAudioStream#read} (MINECRAFT-394
 * PR #168 review findings #1-3, plus the second-round non-blocking-read fix below) -- deliberately has no
 * Minecraft/LWJGL imports at all, so it can be unit tested directly without the client classpath those
 * pull in (this repo's plain unit tests, unlike its GameTests, run without one -- {@code
 * net.minecraft.client.sounds.AudioStream} is not resolvable there).
 *
 * <p>One background decode thread calls {@link #put} (blocking itself, never a reader) to push decoded
 * PCM chunks in; whoever is pumping the stream (vanilla's shared sound executor, for the real {@link
 * RadioAudioStream}) calls {@link #drain} to pull bytes out, which never blocks -- it returns whatever is
 * already queued immediately, padded out with silence if that is not enough, or {@code null} only for a
 * genuine end of stream, see {@link #drain}'s own return-value contract spelled out there.
 *
 * <p>MINECRAFT-394 PR #168's third review round found the second round's fix above (collapsing "merely
 * momentarily empty" and "decode thread actually finished" into a single {@code null} outcome) was wrong
 * about <em>which two cases to collapse</em>. The real {@code com.mojang.blaze3d.audio.Channel} is a
 * strict producer with a capped, non-catching-up consumer: {@code Channel.attachBufferStream} calls {@code
 * pumpBuffers(4)} exactly once, and every later {@code Channel.updateStream} calls {@code
 * pumpBuffers(this.removeProcessedBuffers())} -- capped by how many <em>previously queued</em> buffers have
 * actually finished playing, never by how many slots are "missing". A slot where {@code read()} (i.e.
 * {@link #drain}) returns {@code null} is simply skipped and never queued, so it can never later become
 * "processed" -- that slot is lost for good, and if enough slots are lost (worst case: all 4 of the initial
 * {@code pumpBuffers(4)}, well before our background decode thread has produced anything), the OpenAL
 * source has nothing queued, reads {@code AL_STOPPED} almost immediately, and {@code
 * ChannelAccess.scheduleTick} permanently releases/removes the channel -- {@link #drain} is then never
 * called again for this instance no matter how much real audio the decode thread goes on to produce.
 *
 * <p>So the correct collapse is the other way around: "temporarily nothing real ready" must join "real
 * data ready" (both return a real, correctly-sized, non-null buffer -- padded with trailing silence when
 * real data runs short), and only "genuinely, permanently done" (the decode thread has actually finished --
 * see {@link #markEndOfStream}) <em>and</em> there is no real data left buffered either stays {@code null}.
 * Every {@code pumpBuffers} attempt therefore always succeeds in queuing something playable while the
 * stream is alive, so the "replenish exactly what finished" bookkeeping never silently loses a slot: every
 * queued buffer eventually finishes playing and is correctly counted as processed, keeping the refill loop
 * self-sustaining forever instead of ratcheting down to zero. A transient stall is heard as a brief, bounded
 * gap of silence -- acceptable here (sample-accurate cross-client sync was never a requirement for this
 * feature) and far better than the channel dying permanently.
 */
final class PcmChunkPipe {
    private final BlockingQueue<byte[]> chunks;

    /** Chunk {@link #drain} is currently partway through handing out, and how far into it. Only ever
     * touched by whatever single thread calls {@link #drain} -- never concurrently for one pipe. */
    private byte[] leftover;
    private int leftoverPos;

    /** Set once by the decode thread (via {@link #markEndOfStream}) when it has genuinely finished --
     * clean EOF, a decode error, or {@code close()}/interruption -- and never unset. {@code volatile}
     * because it is written by the decode thread and read by whatever thread calls {@link #drain} (never
     * the same thread). Only matters to {@link #drain} once the queue is also empty -- see its javadoc. */
    private volatile boolean endOfStream;

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

    /** Called only by the background decode thread, exactly once, when it is genuinely and permanently
     * done producing -- clean EOF, a decode error, or {@code close()}/interruption -- so that {@link
     * #drain} knows a future empty queue means real end-of-stream rather than a momentary stall. Any real
     * chunks already {@link #put} before this call are still delivered normally first. */
    void markEndOfStream() {
        endOfStream = true;
    }

    /**
     * Returns up to {@code size} bytes of PCM as a <strong>direct</strong> little-endian {@link ByteBuffer}
     * (direct because the real caller, {@code Channel.pumpBuffers}, passes this straight into a native
     * {@code AL10.alBufferData} call that requires one), without ever blocking.
     *
     * <p>While the stream is still alive (i.e. {@link #markEndOfStream} has not been called, or it has but
     * some real data is still queued), this <strong>never</strong> returns {@code null} or an empty
     * buffer: as much real queued data as available is copied first, and any shortfall is padded with
     * trailing silence (zero bytes -- a zero sample is silence in any byte order) so the result always has
     * exactly {@code size} bytes. This is required, not optional, for correctness against vanilla's real
     * {@code Channel}/{@code ChannelAccess} consumer contract -- see this class's own javadoc for exactly
     * why a {@code null}/empty result for a merely-stalled-but-alive stream permanently starves the
     * channel. A buffer mixing real bytes followed by silence padding is allowed and expected (it
     * minimizes how much silence is ever audible) rather than forcing a read to be "fully real" or "fully
     * silence".
     *
     * <p>Returns {@code null} only once the stream has genuinely, permanently ended: {@link
     * #markEndOfStream} has been called <em>and</em> there is no real data left buffered to drain either.
     * If the stream has ended but some real data is still queued, that partial-or-full real data is
     * returned trimmed to however many real bytes remain (never padded with silence -- there is no more
     * real audio coming, so there is nothing meaningful left to pad towards); only once that real data is
     * also exhausted does a later call return {@code null}.
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
        if (remaining > 0) {
            if (endOfStream) {
                // Genuinely done and nothing real left queued either: null only if this call collected no
                // real bytes at all. If it collected some (a final partial chunk), return that trimmed --
                // no silence padding, there is no more real audio coming to bridge towards.
                if (out.position() == 0) {
                    return null;
                }
            } else {
                // Stream is still alive but momentarily short (including totally empty, e.g. the decode
                // thread hasn't produced its first chunk yet, or a brief network stall) -- pad with
                // silence so this call still returns a full-size, non-null, playable buffer.
                for (int i = 0; i < remaining; i++) {
                    out.put((byte) 0);
                }
            }
        }
        out.flip();
        return out.remaining() == 0 ? null : out;
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
