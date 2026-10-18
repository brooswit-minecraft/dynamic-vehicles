package io.github.brooswitminecraft.dynamicvehicles;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFormat;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import net.minecraft.client.sounds.AudioStream;

/**
 * Decodes one internet radio MP3 stream into raw PCM on demand, for {@link RadioSoundInstance} to feed to
 * vanilla's {@code Channel} via {@code AudioStream} (MINECRAFT-394). Connects directly to the station URL
 * from this client -- the Minecraft server is never involved in the audio itself.
 *
 * <p>A background thread does the actual network reading and MP3 decoding (javazoom JLayer), downmixing
 * any stereo frames to mono (OpenAL only spatializes mono buffers -- see {@link
 * PcmChunkPipe#downmixToMono}) and pushing fixed-size PCM chunks into a {@link PcmChunkPipe}. {@link #read}
 * just delegates to {@link PcmChunkPipe#drain}, which never blocks: vanilla's {@code
 * ChannelAccess.scheduleTick} pumps {@code updateStream} (which calls this) for every active streamed
 * channel on one shared executor, so a station that stalls over the network must never hold up that
 * thread -- only this class's own decode thread ever waits on the socket or on queue space. All of the
 * actual buffering/non-blocking/downmix logic lives in {@link PcmChunkPipe} rather than here specifically
 * so it can be unit tested without the Minecraft client classes {@link AudioStream} pulls in.
 */
final class RadioAudioStream implements AudioStream {
    /** Shared pool for connecting to stations and decoding them; virtual threads because this work is
     * almost entirely blocked on network I/O, not CPU, and multiple cars may each have one in flight. Note
     * that this executor only ever runs the initial connect-and-first-frame work kicked off by {@link
     * RadioSoundInstance#getStream} -- the per-tick {@link #read} calls happen on vanilla's own shared
     * sound executor, which is exactly why {@link #read} must never block. */
    static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    /** How many decoded PCM chunks the background decode thread may buffer ahead of {@link #read} before
     * blocking itself (never the caller of {@link #read}) -- generous enough that normal network jitter
     * never starves playback, bounded so a decode thread that outpaces a stalled {@link #read} caller
     * cannot grow memory use forever. Each chunk is one MP3 frame's worth of mono 16-bit PCM, roughly
     * 1-2 KB, so this is on the order of a few seconds of buffered audio. */
    private static final int QUEUE_CAPACITY_CHUNKS = 64;

    private final AudioFormat format;
    private final PcmChunkPipe pipe;
    private final Thread decodeThread;
    private final URLConnectionCloser closer;

    private RadioAudioStream(AudioFormat format, PcmChunkPipe pipe, Thread decodeThread, URLConnectionCloser closer) {
        this.format = format;
        this.pipe = pipe;
        this.decodeThread = decodeThread;
        this.closer = closer;
    }

    /**
     * Connects to {@code url}, decodes just enough of the first MP3 frame to learn the real sample rate
     * and channel count (never guessed/hardcoded), and starts a background thread that keeps decoding the
     * rest of the stream into this object's pipe. Blocks the calling thread on the network connect and
     * that first frame -- callers must run this off the render thread and off vanilla's shared sound
     * executor (see {@link #EXECUTOR}).
     *
     * <p>The {@link #getFormat} this returns always reports exactly one channel: any stereo source is
     * downmixed to mono by the decode thread (see {@link PcmChunkPipe#downmixToMono}) before it ever
     * reaches the pipe, because OpenAL does not apply 3D distance attenuation/panning to multichannel
     * buffers (only {@code AL_FORMAT_MONO16}), which would otherwise silently break this radio's
     * vehicle-positioned audio for any stereo station -- confirmed stereo for at least the OC ReMix
     * stream this feature targets, not merely assumed.
     *
     * @throws IOException if the URL cannot be connected to, times out, or does not parse as MP3 -- the
     *      caller is expected to log this and give up on playing the station, not retry forever.
     */
    static RadioAudioStream open(String url) throws IOException {
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.connect();
        InputStream in = connection.getInputStream();
        Bitstream bitstream = new Bitstream(in);
        Decoder decoder = new Decoder();
        Header firstHeader;
        SampleBuffer firstFrame;
        try {
            firstHeader = bitstream.readFrame();
            if (firstHeader == null) {
                // Must close `in` here too, same as the catch block below -- previously this path threw
                // without closing it (MINECRAFT-394 review finding #4's smaller named bug).
                closeQuietly(in);
                throw new IOException("Radio station stream has no audio frames: " + url);
            }
            firstFrame = (SampleBuffer) decoder.decodeFrame(firstHeader, bitstream);
        } catch (BitstreamException | javazoom.jl.decoder.DecoderException e) {
            closeQuietly(in);
            throw new IOException("Radio station stream is not valid MP3: " + url, e);
        }
        bitstream.closeFrame();
        // Always mono in the format we report -- see this method's javadoc. The source's real channel
        // count only affects how putSamples below mixes it down, never what we tell OpenAL.
        AudioFormat format = new AudioFormat((float) firstFrame.getSampleFrequency(), 16, 1, true, false);

        PcmChunkPipe pipe = new PcmChunkPipe(QUEUE_CAPACITY_CHUNKS);
        try {
            // Small, bounded quality improvement (not required for correctness -- PcmChunkPipe.drain's
            // silence-fill already makes every attachBufferStream pump slot safe either way): firstFrame
            // was already decoded synchronously above on this thread, so enqueue it right here instead of
            // leaving it to race the brand-new decode thread below. This can only shrink how much silence
            // the very first attachBufferStream pump ever has to pad with, never block meaningfully -- the
            // queue is freshly created and empty, so put() cannot block on capacity here.
            putSamples(firstFrame, pipe);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Thread thread = new Thread(() -> decodeLoop(url, bitstream, decoder, in, pipe),
                "dynamicvehicles-radio-decode");
        thread.setDaemon(true);
        thread.start();
        return new RadioAudioStream(format, pipe, thread, () -> closeQuietly(in));
    }

    /** Runs entirely on the background decode thread started by {@link #open}; never touches OpenAL and
     * never runs on vanilla's shared sound executor, so it is free to block on the network or on the
     * pipe filling up. The very first frame is enqueued by {@link #open} itself, before this thread even
     * starts -- this loop only ever handles the second frame onwards. */
    private static void decodeLoop(String url, Bitstream bitstream, Decoder decoder, InputStream in,
            PcmChunkPipe pipe) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }
                SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                putSamples(frame, pipe);
                bitstream.closeFrame();
            }
        } catch (InterruptedException e) {
            // close() interrupted us while we were blocked waiting for queue space -- just stop, same as
            // any other wind-down below.
            Thread.currentThread().interrupt();
        } catch (BitstreamException | javazoom.jl.decoder.DecoderException | RuntimeException e) {
            // Connection dropped, timed out, or the stream stopped parsing as MP3 mid-way (a transcoder
            // hiccup, an Icecast metadata block we don't expect, the station going offline). Logged once
            // here; this thread simply stops producing -- markEndOfStream() below tells the pipe this is
            // genuine, permanent end of stream rather than a momentary stall, so read() starts returning
            // real (not silence-padded) partial data followed by null once the pipe is truly drained (see
            // PcmChunkPipe's javadoc), which is all vanilla's Channel ever needs to eventually let this
            // station's channel stop.
            DynamicVehiclesMod.LOGGER.info("Radio stream {} ended: {}", url, e.toString());
        } finally {
            // Must run for every exit path above (clean EOF, decode error, or close()/interruption) --
            // without this, a merely-stalled-looking drain() would pad with silence forever instead of
            // ever reporting real end of stream (MINECRAFT-394 PR #168 third review round).
            pipe.markEndOfStream();
            closeQuietly(in);
        }
    }

    /** Downmixes a decoded frame to mono (if it isn't already) and enqueues it as little-endian PCM
     * bytes, blocking (this thread only, never a caller of {@link #read}) if the pipe is momentarily full. */
    private static void putSamples(SampleBuffer frame, PcmChunkPipe pipe) throws InterruptedException {
        short[] samples = frame.getBuffer();
        int length = frame.getBufferLength();
        short[] mono = frame.getChannelCount() == 2
                ? PcmChunkPipe.downmixToMono(samples, length)
                : Arrays.copyOf(samples, length);
        pipe.put(PcmChunkPipe.toLittleEndianBytes(mono, mono.length));
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Returns {@code size} bytes as a direct {@link ByteBuffer} -- real decoded PCM where available,
     * silence-padded where the decoder is temporarily short -- or {@code null} only once this stream has
     * genuinely, permanently ended and no real data is left buffered either. See {@link PcmChunkPipe#drain}
     * for the exact non-blocking contract this delegates to wholesale, and its javadoc for why that
     * silence-fill is required against vanilla's real {@code Channel}/{@code ChannelAccess} consumer
     * (MINECRAFT-394 review round 1 findings #1 and #3; round 3 finding #1).
     */
    @Override
    public ByteBuffer read(int size) throws IOException {
        return pipe.drain(size);
    }

    @Override
    public void close() throws IOException {
        decodeThread.interrupt();
        closer.close();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort cleanup; nothing left to do about a socket that won't close cleanly.
        }
    }

    @FunctionalInterface
    private interface URLConnectionCloser {
        void close();
    }
}
