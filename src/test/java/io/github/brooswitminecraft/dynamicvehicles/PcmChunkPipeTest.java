package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * MINECRAFT-394 PR #168 review round 1 findings #1-3, round 2 finding #1 (the null-vs-empty-buffer fix),
 * and round 3 finding #1 (the silence-fill fix: round 2's fix was collapsing the wrong two cases -- see
 * {@link PcmChunkPipe}'s own javadoc). Deliberately targets {@link PcmChunkPipe} rather than {@link
 * RadioAudioStream} itself: {@code RadioAudioStream} implements {@code
 * net.minecraft.client.sounds.AudioStream}, which is not resolvable from this plain unit test's classpath
 * (this repo's GameTests, not these, are where the Minecraft client classes are available).
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

        // Only one real byte (6) left queued and the stream is still alive (markEndOfStream was never
        // called) -- the shortfall is silence-padded, not returned short.
        ByteBuffer second = pipe.drain(5);
        assertEquals(5, second.remaining());
        byte[] secondBytes = new byte[5];
        second.get(secondBytes);
        assertArrayEquals(new byte[] {6, 0, 0, 0, 0}, secondBytes);
    }

    @Test
    void drainReturnsPromptlyAndNonBlockingWhenTheProducerHasStalled() {
        // Finding #3 (round 1): a stalled station must never block whatever thread calls read() -- which
        // in the real engine is vanilla's single shared sound executor, not RadioAudioStream.EXECUTOR.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4); // nothing enqueued: simulates a stall
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        ByteBuffer out = assertTimeoutPreemptively(Duration.ofMillis(200), () -> pipe.drain(1024),
                "drain() must return immediately instead of blocking on a stalled producer");

        // Finding #1 (round 3): round 2's fix made this null, but vanilla's Channel never retries a slot
        // that read() returned null for (Channel.pumpBuffers/updateStream only ever requests
        // removeProcessedBuffers() more, never "what's still missing") -- a null here can permanently
        // starve the channel. The stream is still alive (markEndOfStream was never called), so this must
        // be a full-size, non-null, silence-padded buffer instead.
        assertFalse(out == null, "a momentarily-stalled-but-still-alive stream must never return null -- "
                + "vanilla's Channel never retries a slot it got null for, so null here can permanently "
                + "starve the channel (MINECRAFT-394 PR #168 third review round)");
        assertEquals(1024, out.remaining());
        byte[] actual = new byte[1024];
        out.get(actual);
        for (byte b : actual) {
            assertEquals(0, b, "a stalled-but-alive read must be padded entirely with silence (zero bytes)");
        }
    }

    @Test
    void drainPadsWithSilenceWheneverNothingIsQueuedButTheStreamIsStillAlive() {
        // Mirrors the old "regardless of whether the producer is still going" test, but the correct
        // contract is now the opposite of what it asserted: nothing queued + stream still alive must pad
        // with silence, never return null, however many times in a row this happens.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        queue.add(new byte[] {9, 9});
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        // Still one real chunk queued -- must be delivered as-is, no padding needed.
        ByteBuffer leftoverData = pipe.drain(2);
        assertEquals(2, leftoverData.remaining());

        // Queue is now empty but markEndOfStream() was never called -- silence-padded, non-null.
        ByteBuffer padded = pipe.drain(2);
        assertEquals(2, padded.remaining());
        assertEquals(0, padded.get(0));
        assertEquals(0, padded.get(1));
    }

    @Test
    void aTransientEmptyPollFollowedByRealDataArrivingIsNotMisreadAsEndOfStreamByAnyone() {
        // Proves the actual bug the round 3 fix addresses: a momentary "nothing queued yet" poll must
        // return a real, non-null (silence-padded) buffer -- never null, which vanilla's Channel cannot
        // tell apart from genuine end of stream and will never retry.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        // Nothing queued yet (e.g. decode thread hasn't produced its first chunk, or a brief network
        // hiccup), and the stream is still alive -- must be silence, not null.
        ByteBuffer stalled = pipe.drain(4);
        assertFalse(stalled == null, "a stalled-but-alive stream must return silence, never null");
        assertEquals(4, stalled.remaining());

        // ...real data now arrives, exactly as it would once the decode thread catches up.
        queue.add(new byte[] {1, 2, 3, 4});

        ByteBuffer out = pipe.drain(4);
        assertEquals(4, out.remaining(), "a prior silence-padded read must not have latched any "
                + "end-of-stream-like state that would stop real data from being drained afterward");
        byte[] actual = new byte[4];
        out.get(actual);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, actual);
    }

    @Test
    void drainPadsAPartialRealChunkWithTrailingSilenceRatherThanReturningItShort() {
        // A mid-request shortfall (some real bytes, not enough to fill the request, and the stream is
        // still alive) must be padded out to the full requested size, not returned short -- Channel.
        // pumpBuffers passes whatever size it asks for straight into AL10.alBufferData; a short buffer is
        // not itself unsafe, but the review's prescribed fix is "frame-aligned silence during a bounded
        // temporary stall," i.e. every live read is exactly `size` bytes, real-then-silence.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        queue.add(new byte[] {1, 2, 3});
        PcmChunkPipe pipe = new PcmChunkPipe(queue);

        ByteBuffer out = pipe.drain(6);

        assertEquals(6, out.remaining());
        byte[] actual = new byte[6];
        out.get(actual);
        assertArrayEquals(new byte[] {1, 2, 3, 0, 0, 0}, actual,
                "real bytes first, then zero-byte silence padding for the shortfall");
    }

    @Test
    void drainReturnsRealTrimmedDataAtGenuineEndOfStreamWithoutPaddingNoFurtherAudioIsComing() {
        // Once markEndOfStream() has been called (the decode thread genuinely, permanently finished), a
        // final partial real chunk must be returned trimmed to its real length -- not padded with silence,
        // since there is no more real audio ever coming to bridge towards.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        queue.add(new byte[] {1, 2, 3});
        PcmChunkPipe pipe = new PcmChunkPipe(queue);
        pipe.markEndOfStream();

        ByteBuffer out = pipe.drain(6);

        assertEquals(3, out.remaining(), "a genuine end-of-stream partial chunk must be trimmed, "
                + "not padded with silence -- there is no more real audio coming");
        byte[] actual = new byte[3];
        out.get(actual);
        assertArrayEquals(new byte[] {1, 2, 3}, actual);
    }

    @Test
    void drainReturnsNullOnlyOnceGenuinelyEndedAndFullyDrained() {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4);
        queue.add(new byte[] {1, 2});
        PcmChunkPipe pipe = new PcmChunkPipe(queue);
        pipe.markEndOfStream();

        // Real data still queued even though the stream has ended -- must still be delivered.
        ByteBuffer out = pipe.drain(2);
        assertEquals(2, out.remaining());

        // Now genuinely nothing left, and the stream has ended -- this, and only this, is null.
        assertNull(pipe.drain(2));
        // Stays null forever afterward, never flips back to silence.
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

    // ------------------------------------------------------------------------------------------------
    // MINECRAFT-394 PR #168 third review round: a lifecycle-accurate regression modeling the real
    // com.mojang.blaze3d.audio.Channel/net.minecraft.client.sounds.ChannelAccess consumer contract, not
    // just "call drain() again and see what happens." The review's exact complaint about the old test:
    // "The new aTransientEmptyPollFollowedByRealDataArrivingIsNotMisreadAsEndOfStreamByAnyone test directly
    // calls drain again itself; the actual Channel will not necessarily make that second call." The real
    // constraint this was missing: Channel.attachBufferStream calls pumpBuffers(4) exactly once, and every
    // later Channel.updateStream calls pumpBuffers(this.removeProcessedBuffers()) -- capped by how many
    // *previously queued* buffers have actually finished playing, never by how many slots are "missing".
    // ------------------------------------------------------------------------------------------------

    /**
     * Minimal model of {@code com.mojang.blaze3d.audio.Channel}'s queuing contract, good enough to catch
     * exactly the class of bug the third review round found: a {@link PcmChunkPipe#drain} that returns
     * {@code null} for a slot that was merely temporarily short (not genuinely end-of-stream) loses that
     * slot forever, because nothing ever retries a slot {@code pumpBuffers} skipped -- it can only ever
     * replenish slots that were actually queued and have since finished playing.
     */
    private static final class FakeChannel {
        private final PcmChunkPipe pipe;
        private final int bufferSize;
        /** FIFO of buffers currently "queued" on the simulated OpenAL source, oldest (soonest to finish
         * playing) first -- models the real source's queue between {@code attachBufferStream}/{@code
         * pumpBuffers} and {@code removeProcessedBuffers}. */
        private final java.util.Deque<ByteBuffer> queued = new java.util.ArrayDeque<>();

        FakeChannel(PcmChunkPipe pipe, int bufferSize) {
            this.pipe = pipe;
            this.bufferSize = bufferSize;
        }

        /** Models {@code Channel.attachBufferStream}, which calls {@code pumpBuffers(4)} exactly once,
         * at attach time, regardless of whether the decode thread has produced anything yet. */
        void attach() {
            pump(4);
        }

        /**
         * Models one {@code Channel.updateStream()} tick: the caller says how many previously-queued
         * buffers OpenAL has actually finished playing ({@code removeProcessedBuffers()}'s return value)
         * by retiring that many from the front of the queue, then pumps exactly that many replacement
         * attempts -- never more, never "catching up" on slots an earlier pump missed. This is the exact
         * mechanic the review cites: {@code updateStream} calls {@code
         * pumpBuffers(this.removeProcessedBuffers())}, not {@code pumpBuffers(numberOfMissingSlots)}.
         */
        void tick(int processedCount) {
            for (int i = 0; i < processedCount && !queued.isEmpty(); i++) {
                queued.poll();
            }
            pump(processedCount);
        }

        /** Models {@code Channel.pumpBuffers}: exactly {@code attempts} calls to {@code read()} (here,
         * {@link PcmChunkPipe#drain}); each {@code null} result just silently skips that slot forever,
         * exactly as real {@code pumpBuffers} does -- it is never retried by a later pump. */
        private void pump(int attempts) {
            for (int i = 0; i < attempts; i++) {
                ByteBuffer buffer = pipe.drain(bufferSize);
                if (buffer != null) {
                    queued.add(buffer);
                }
            }
        }

        /** Models the OpenAL source reaching {@code AL_STOPPED} because nothing is queued to play --
         * {@code ChannelAccess.scheduleTick} permanently releases/removes the channel in exactly this
         * state, after which nothing ever calls {@code updateStream} (and therefore {@code drain}) again. */
        boolean isStopped() {
            return queued.isEmpty();
        }
    }

    @Test
    void attachNeverLeavesTheChannelStoppedEvenWhenTheDecodeThreadHasProducedNothingYet() {
        // The exact worst-case scenario the review describes: open() has returned and attachBufferStream
        // is called before the background decode thread has enqueued even its first chunk. All 4 of the
        // initial pumpBuffers(4) attempts must still get *something* queued, or the channel is
        // AL_STOPPED (and permanently gone) before the decode thread ever gets a chance to produce audio.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(64); // empty: decode thread hasn't produced anything
        PcmChunkPipe pipe = new PcmChunkPipe(queue);
        FakeChannel channel = new FakeChannel(pipe, 4);

        channel.attach();

        assertFalse(channel.isStopped(),
                "attachBufferStream's initial pumpBuffers(4) must never leave the channel with zero "
                        + "queued buffers, even if the decode thread has produced nothing yet -- a null "
                        + "read() here is never retried, so losing a slot here is permanent "
                        + "(MINECRAFT-394 PR #168 third review round finding #1)");

        // Real data now arrives; later ticks must be able to thread it in without the channel ever having
        // gone through a zero-queued (AL_STOPPED) state in between.
        queue.add(new byte[] {1, 2, 3, 4});
        queue.add(new byte[] {5, 6, 7, 8});

        channel.tick(2);

        assertFalse(channel.isStopped());
    }

    @Test
    void aTemporaryStallAfterAHealthyStartNeverStopsTheChannelAndRealAudioResumesAfterward() {
        // Plenty of real data queued at attach time, then the decoder stalls for a bit (e.g. a brief
        // network hiccup), then resumes -- the channel must bridge the stall with silence and never go
        // to zero queued buffers, and real audio must resume once it's available again.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(64);
        for (int i = 0; i < 4; i++) {
            queue.add(new byte[] {1, 2, 3, 4});
        }
        PcmChunkPipe pipe = new PcmChunkPipe(queue);
        FakeChannel channel = new FakeChannel(pipe, 4);

        channel.attach();
        assertFalse(channel.isStopped());

        // Decoder stalls: two buffers finish playing, queue is empty, tick(2) has nothing real to offer.
        channel.tick(2);
        assertFalse(channel.isStopped(), "a stall well after a healthy start must be bridged with "
                + "silence, never dropping the channel to zero queued buffers");

        // Another tick still stalled.
        channel.tick(2);
        assertFalse(channel.isStopped());

        // Decoder resumes.
        queue.add(new byte[] {9, 9, 9, 9});
        queue.add(new byte[] {8, 8, 8, 8});
        channel.tick(2);
        assertFalse(channel.isStopped());
    }

    @Test
    void genuineEndOfStreamEventuallyAndCorrectlyStopsTheChannel() {
        // Once the decode thread has genuinely finished and all real (and already-queued silence) content
        // is exhausted, drain() eventually returns null and the simulated channel correctly becomes
        // "stopped" -- this is the one case where that outcome is actually correct/expected.
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(64);
        queue.add(new byte[] {1, 2, 3, 4});
        queue.add(new byte[] {5, 6, 7, 8});
        PcmChunkPipe pipe = new PcmChunkPipe(queue);
        pipe.markEndOfStream(); // decode thread already genuinely finished

        FakeChannel channel = new FakeChannel(pipe, 4);
        channel.attach(); // queues the 2 real chunks; the other 2 pump attempts get null (genuine EOF, empty) and are skipped

        assertFalse(channel.isStopped(), "real data queued at attach time must still play out");

        // Those 2 real buffers finish playing; nothing left to replace them with (genuine end of stream).
        channel.tick(2);

        assertTrue(channel.isStopped(),
                "once genuinely ended and fully drained, the channel going to zero queued buffers "
                        + "(and therefore being released) is correct, expected behavior");
    }
}
