package io.github.brooswitminecraft.dynamicvehicles;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
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
 * <p>A background thread does the actual network reading and MP3 decoding (javazoom JLayer), writing
 * decoded PCM bytes into a bounded pipe; {@link #read} just drains that pipe, blocking like any ordinary
 * {@link InputStream} read -- which is safe here because {@link RadioSoundInstance#getStream} and every
 * call the sound engine makes into this class run on its own background executor, never the render
 * thread. The pipe's bound (a few seconds of audio) keeps a station the decoder is outpacing from piling
 * up unbounded PCM in memory; the socket's own read timeout keeps a stalled connection from blocking
 * either thread forever, so a dead/unreachable station reads as a clean end of stream rather than a hang.
 */
final class RadioAudioStream implements AudioStream {
    /** Shared pool for connecting to stations and decoding them; virtual threads because this work is
     * almost entirely blocked on network I/O, not CPU, and multiple cars may each have one in flight. */
    static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    /** Pipe capacity in PCM bytes: a few seconds of 44.1kHz 16-bit stereo, generous enough that normal
     * network jitter never starves {@link #read}, bounded so a stalled consumer cannot grow this forever. */
    private static final int PIPE_CAPACITY_BYTES = 1 << 18;

    private final AudioFormat format;
    private final PipedInputStream pcm;
    private final Thread decodeThread;
    private final URLConnectionCloser closer;

    private RadioAudioStream(AudioFormat format, PipedInputStream pcm, Thread decodeThread, URLConnectionCloser closer) {
        this.format = format;
        this.pcm = pcm;
        this.decodeThread = decodeThread;
        this.closer = closer;
    }

    /**
     * Connects to {@code url}, decodes just enough of the first MP3 frame to learn the real sample rate
     * and channel count (never guessed/hardcoded), and starts a background thread that keeps decoding the
     * rest of the stream into this object's pipe. Blocks the calling thread on the network connect and
     * that first frame -- callers must run this off the render thread (see this class's own javadoc).
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
                throw new IOException("Radio station stream has no audio frames: " + url);
            }
            firstFrame = (SampleBuffer) decoder.decodeFrame(firstHeader, bitstream);
        } catch (BitstreamException | javazoom.jl.decoder.DecoderException e) {
            closeQuietly(in);
            throw new IOException("Radio station stream is not valid MP3: " + url, e);
        }
        bitstream.closeFrame();
        AudioFormat format = new AudioFormat((float) firstFrame.getSampleFrequency(), 16, firstFrame.getChannelCount(), true, false);

        PipedOutputStream pcmOut = new PipedOutputStream();
        PipedInputStream pcmIn = new PipedInputStream(pcmOut, PIPE_CAPACITY_BYTES);
        Thread thread = new Thread(() -> decodeLoop(url, bitstream, decoder, firstFrame, in, pcmOut),
                "dynamicvehicles-radio-decode");
        thread.setDaemon(true);
        thread.start();
        return new RadioAudioStream(format, pcmIn, thread, () -> closeQuietly(in));
    }

    /** Runs entirely on the background decode thread started by {@link #open}; never touches OpenAL. */
    private static void decodeLoop(String url, Bitstream bitstream, Decoder decoder, SampleBuffer firstFrame,
            InputStream in, PipedOutputStream pcmOut) {
        try {
            writeSamples(firstFrame, pcmOut);
            while (!Thread.currentThread().isInterrupted()) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }
                SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                writeSamples(frame, pcmOut);
                bitstream.closeFrame();
            }
        } catch (IOException | BitstreamException | javazoom.jl.decoder.DecoderException | RuntimeException e) {
            // Connection dropped, timed out, or the stream stopped parsing as MP3 mid-way (a transcoder
            // hiccup, an Icecast metadata block we don't expect, the station going offline). Logged once
            // here and the pipe is simply closed below -- read() then reports a clean end of stream to
            // whatever is consuming this AudioStream, instead of this thread retrying forever.
            DynamicVehiclesMod.LOGGER.info("Radio stream {} ended: {}", url, e.toString());
        } finally {
            closeQuietly(pcmOut);
            closeQuietly(in);
        }
    }

    private static void writeSamples(SampleBuffer frame, PipedOutputStream out) throws IOException {
        short[] samples = frame.getBuffer();
        int length = frame.getBufferLength();
        byte[] bytes = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            short sample = samples[i];
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        out.write(bytes);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        byte[] buffer = new byte[size];
        int read = pcm.read(buffer, 0, size);
        return read <= 0 ? null : ByteBuffer.wrap(buffer, 0, read);
    }

    @Override
    public void close() throws IOException {
        decodeThread.interrupt();
        closeQuietly(pcm);
        closer.close();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort cleanup; nothing left to do about a socket/pipe that won't close cleanly.
        }
    }

    @FunctionalInterface
    private interface URLConnectionCloser {
        void close();
    }
}
