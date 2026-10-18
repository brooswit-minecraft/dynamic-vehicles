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
 * Covers {@link PcmChunkPipe#drain}'s buffering/non-blocking/EOF contract and {@link
 * PcmChunkPipe#downmixToMono} -- the logic behind {@link RadioAudioStream#read} directly implicated by
 * MINECRAFT-394 PR #168 review findings #1-3. Deliberately targets {@link PcmChunkPipe} rather than
 * {@link RadioAudioStream} itself: {@code RadioAudioStream} implements {@code
 * net.minecraft.client.sounds.AudioStream}, which is not resolvable from this plain unit test's
 * classpath (this repo's GameTests, not these, are where the Minecraft client classes are available).
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
        // Finding #3: a stalled station must never block whatever thread calls read() -- which in the
        // real engine is vanilla's single shared sound executor, not RadioAudioStream.EXECUTOR.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4); // nothing enqueued: simulates a stall
        PcmChunkPipe pipe = new PcmChunkPipe(queue); // markEndOfStream() never called: decode thread "still going"

        ByteBuffer out = assertTimeoutPreemptively(Duration.ofMillis(200), () -> pipe.drain(1024),
                "drain() must return immediately instead of blocking on a stalled producer");

        assertEquals(0, out.remaining(), "nothing buffered yet is a short/empty read, not end of stream");
    }

    @Test
    void drainReturnsNullOnlyOnceEndOfStreamIsMarkedAndEverythingQueuedIsDrained() {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        queue.add(new byte[] {9, 9});
        PcmChunkPipe pipe = new PcmChunkPipe(queue);
        pipe.markEndOfStream(); // decode thread already finished

        // Still one real chunk queued -- must be delivered, not swallowed just because EOF is marked.
        ByteBuffer leftoverData = pipe.drain(2);
        assertEquals(2, leftoverData.remaining());

        // Only once the queue is actually empty does a finished decode thread mean "end of stream".
        assertNull(pipe.drain(2));
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
