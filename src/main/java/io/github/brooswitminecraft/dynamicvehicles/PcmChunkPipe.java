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
 * already queued immediately, or {@code null} if nothing is queued right now, see {@link #drain}'s own
 * return-value contract spelled out there.
 *
 * <p>There used to be an {@code endOfStream} flag here distinguishing "decode thread finished" from "queue
 * is merely momentarily empty" (set via a since-removed {@code markEndOfStream}), with {@link #drain}
 * returning a non-null <em>empty</em> buffer for the latter and only {@code null} for the former. That
 * distinction is gone (MINECRAFT-394 PR #168 second review round, finding #1): {@code
 * com.mojang.blaze3d.audio.Channel.pumpBuffers} queues <strong>any</strong> non-null {@code read()} result
 * -- including a zero-byte one -- as a real (zero-duration) OpenAL buffer via {@code
 * AL10.alSourceQueueBuffers}, and {@code ChannelAccess.scheduleTick} permanently releases and stops
 * tracking any channel whose OpenAL source naturally reaches {@code AL_STOPPED} (which an artificially
 * injected zero-duration buffer helps bring about sooner than real playback would). Vanilla's {@code
 * Channel} has no notion of "try again later" versus "stream actually ended" in the first place -- both
 * just mean "nothing to queue this pump cycle" to it, and it naturally calls {@link #drain} again on a
 * later tick for as long as the already-queued real buffers keep the AL source out of {@code AL_STOPPED}.
 * So the only contract that matters to vanilla, and the only one {@link #drain} now implements, is: zero
 * bytes available right now = {@code null}, full stop; one or more bytes available = a buffer with that
 * many bytes. Whether the decode thread is done, mid-frame, or merely between network packets is no longer
 * caller-visible, because nothing downstream ever needed that distinction.
 */
final class PcmChunkPipe {
    private final BlockingQueue<byte[]> chunks;

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

    /**
     * Returns up to {@code size} bytes of already-queued PCM as a <strong>direct</strong> little-endian
     * {@link ByteBuffer} (direct because the real caller, {@code Channel.pumpBuffers}, passes this
     * straight into a native {@code AL10.alBufferData} call that requires one), without ever blocking.
     *
     * <p>Returns {@code null} -- never a non-null empty buffer -- whenever zero bytes are available right
     * now, regardless of whether the decode thread is still running: see this class's own javadoc for why
     * collapsing that distinction is required, not optional, for correctness against vanilla's {@code
     * Channel}/{@code ChannelAccess}. Otherwise returns a buffer with between 1 and {@code size} bytes,
     * assembled from however many already-queued chunks are needed to satisfy the request (or fewer, if
     * the queue runs dry partway through).
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
