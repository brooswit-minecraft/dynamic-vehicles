package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link PcmChunkPipe#drain}'s buffering/non-blocking contract and {@link
 * PcmChunkPipe#downmixToMono} -- the logic behind {@link RadioAudioStream#read} directly implicated by
 * MINECRAFT-394 PR #168 review round 1 findings #1-3 and round 2 finding #1 (the null-vs-empty-buffer
 * fix). Deliberately targets {@link PcmChunkPipe} rather than {@link RadioAudioStream} itself: {@code
 * RadioAudioStream} implements {@code net.minecraft.client.sounds.AudioStream}, which is not resolvable
 * from this plain unit test's classpath (this repo's GameTests, not these, are where the Minecraft client
 * classes are available).
 */
class PcmChunkPipeTest {

    @Test
    void drainReturnsADirectBufferOfTheExactSizeAndContentQueued() {
        // Finding #1: vanilla's Channel.pumpBuffers passes whatever read() returns straight into a native
        // AL10.alBufferData call, which requires a direct buffer -- a heap ByteBuffer.wrap is unsafe there.
        byte[] chunk = {1, 2, 3, 4, 5, 6};
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        queue.add(chunk);
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        ByteBuffer out = pipe.drain(6);

        assertTrue(out.isDirect(), "drain() must return a direct buffer for the native OpenAL call");
        assertEquals(6, out.remaining());
        byte[] actual = new byte[6];
        out.get(actual);
        assertArrayEquals(chunk, actual);
    }

    @Test
    void drainAssemblesMultipleQueuedChunksAndLeavesTheRemainderForNextTime() {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        queue.add(new byte[] {1, 2});
        queue.add(new byte[] {3, 4, 5, 6});
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        ByteBuffer first = pipe.drain(5);
        assertEquals(5, first.remaining());
        byte[] firstBytes = new byte[5];
        first.get(firstBytes);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, firstBytes);

        ByteBuffer second = pipe.drain(5);
        assertEquals(1, second.remaining());
        byte[] secondBytes = new byte[1];
        second.get(secondBytes);
        assertArrayEquals(new byte[] {6}, secondBytes);
    }

    @Test
    void drainReturnsPromptlyAndNonBlockingWhenTheProducerHasStalled() {
        // Finding #3 (round 1): a stalled station must never block whatever thread calls read() -- which
        // in the real engine is vanilla's single shared sound executor, not RadioAudioStream.EXECUTOR.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4); // nothing enqueued: simulates a stall
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        ByteBuffer out = assertTimeoutPreemptively(Duration.ofMillis(200), () -> pipe.drain(1024),
                "drain() must return immediately instead of blocking on a stalled producer");

        // Finding #1 (round 2): vanilla's Channel.pumpBuffers queues *any* non-null read() result --
        // including a zero-byte one -- as a real, instantly-completing OpenAL buffer, which can trip
        // AL_STOPPED and get the channel permanently released by ChannelAccess.scheduleTick well before
        // the decode thread is done producing real audio. drain() must return null, not an empty buffer,
        // whenever nothing is queued, so vanilla never queues a phantom zero-duration buffer.
        assertNull(out, "nothing queued right now must be null, never a non-null empty buffer "
                + "(a non-null empty buffer gets queued by vanilla's Channel as a real, "
                + "instantly-completing OpenAL buffer and can prematurely stop the channel)");
    }

    @Test
    void drainReturnsNullWheneverNothingIsQueuedRegardlessOfWhetherTheProducerIsStillGoing() {
        // The old endOfStream-gated distinction ("empty but not EOF" vs. "null means EOF") is gone: vanilla
        // never cared, and the only contract that matters is "0 bytes available now" vs. "1+ bytes now".
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        queue.add(new byte[] {9, 9});
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        // Still one real chunk queued -- must be delivered.
        ByteBuffer leftoverData = pipe.drain(2);
        assertEquals(2, leftoverData.remaining());

        // Queue is now empty -- null, exactly as it would be for a transient stall, since nothing ever
        // marked (or needs to mark) this pipe as "really" done.
        assertNull(pipe.drain(2));
    }

    @Test
    void aTransientEmptyPollFollowedByRealDataArrivingIsNotMisreadAsEndOfStreamByAnyone() {
        // Proves the actual bug this round's fix addresses: a momentary "nothing queued yet" poll must not
        // leave any caller-visible trace that could be mistaken for "the stream ended" once real data
        // shows up a moment later -- there is no separate end-of-stream flag left to get out of sync.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        // Nothing queued yet (e.g. decode thread hasn't produced its first chunk, or a brief network
        // hiccup) -- drain() reports null, exactly like a real end-of-stream would look, but...
        assertNull(pipe.drain(4));

        // ...real data now arrives, exactly as it would once the decode thread catches up.
        queue.add(new byte[] {1, 2, 3, 4});

        ByteBuffer out = pipe.drain(4);
        assertEquals(4, out.remaining(), "a prior empty poll must not have latched any "
                + "end-of-stream-like state that would stop real data from being drained afterward");
        byte[] actual = new byte[4];
        out.get(actual);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, actual);
    }

    @Test
    void downmixAveragesEachStereoSamplePairIntoOneMonoSampleWithoutOverflow() {
        // Finding #2: OpenAL only spatializes mono (AL_FORMAT_MONO16) buffers, so stereo stations must be
        // downmixed before they reach the AudioFormat/pipe. Averaging must widen to int first so loud
        // samples near the short range cannot overflow.
        short[] interleavedStereo = {
            0, 0,                                   // silence -> 0
            100, -100,                              // symmetric -> 0
            Short.MAX_VALUE, Short.MAX_VALUE,       // both loud positive -> MAX, not overflowed negative
            Short.MIN_VALUE, Short.MIN_VALUE,       // both loud negative -> MIN, not overflowed positive
            10, 20,                                  // ordinary case -> 15
        };

        short[] mono = PcmChunkPipe.downmixToMono(interleavedStereo, interleavedStereo.length);

        assertArrayEquals(new short[] {0, 0, Short.MAX_VALUE, Short.MIN_VALUE, 15}, mono);
    }

    @Test
    void littleEndianBytesRoundTripEachSample() {
        short[] samples = {0x1234, (short) 0xFFFE, -1};

        byte[] bytes = PcmChunkPipe.toLittleEndianBytes(samples, samples.length);

        assertEquals(6, bytes.length);
        ByteBuffer wrapped = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x1234, wrapped.getShort(0));
        assertEquals((short) 0xFFFE, wrapped.getShort(2));
        assertEquals((short) -1, wrapped.getShort(4));
    }
}
