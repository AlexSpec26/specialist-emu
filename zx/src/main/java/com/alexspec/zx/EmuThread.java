package com.alexspec.zx;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.alexspec.zx.core.MemTrace;
import com.alexspec.zx.core.Spectrum48;
import com.alexspec.zx.core.Tape;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Runs the emulated Spectrum at 50 frames per second, produces video and sound. */
final class EmuThread extends Thread {
    interface FrameListener {
        void onFrame();
    }

    static final int FPS = 50;
    static final int OUT_PER_FRAME = Spectrum48.SAMPLE_RATE / FPS; // 882

    final Spectrum48 machine;
    /** Last rendered frame; guarded by itself. */
    final int[] frame = new int[Spectrum48.WIDTH * Spectrum48.HEIGHT];

    /** Live memory map (MemTrace layout); guarded by itself. */
    final int[] mapFrame = new int[MemTrace.MAP_W * MemTrace.MAP_H];
    private final int[] mapBack = new int[mapFrame.length];
    private int frameCount;
    /** layout of the picture currently in mapFrame (guarded by mapFrame) */
    int mapLayout;
    /** bumped every time mapFrame is refreshed */
    volatile int mapVersion;
    private final short[] audioBuf = new short[8192];
    private final short[] outBuf = new short[2048];
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private final FrameListener listener;

    private volatile boolean running = true;
    private volatile boolean paused = true;
    private volatile boolean userPaused;
    /** a posted task may have changed memory: redraw the picture while paused */
    private volatile boolean dirty;
    /** Emulation speed relative to the real machine, 0.01 (1/100) .. 1. */
    volatile float speed = 1f;
    volatile boolean sound = true;
    /** run the emulation fast while a real-time (non-instant) tape load is going on */
    volatile boolean turboLoad = true;
    /** true while the turbo is actually running (for the UI) */
    volatile boolean turboActive;

    EmuThread(Spectrum48 machine, FrameListener listener) {
        super("ZX");
        this.machine = machine;
        this.listener = listener;
        setPriority(Thread.MAX_PRIORITY - 1);
    }

    /** Runs {@code r} on the emulation thread between frames. */
    void post(Runnable r) {
        dirty = true;
        queue.add(r);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    void setPaused(boolean p) {
        synchronized (lock) {
            paused = p;
            lock.notifyAll();
        }
    }

    /** Pause requested by the user (menu open, screen tap); independent of activity lifecycle. */
    void setUserPaused(boolean p) {
        synchronized (lock) {
            userPaused = p;
            lock.notifyAll();
        }
    }

    private boolean isPaused() {
        return paused || userPaused;
    }

    void quit() {
        synchronized (lock) {
            running = false;
            lock.notifyAll();
        }
    }

    private void drainQueue() {
        Runnable r;
        while ((r = queue.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                android.util.Log.e("ZX", "task failed", e);
            }
        }
    }

    private AudioTrack createTrack() {
        int min = AudioTrack.getMinBufferSize(Spectrum48.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, OUT_PER_FRAME * 2 * 3);
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Spectrum48.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            android.util.Log.e("ZX", "no audio", e);
            return null;
        }
    }

    /** a real-time tape load is in progress: the ROM loader waits for a tape file that plays */
    private boolean tapeLoading() {
        Tape t = machine.tape;
        return t != null && t.playing && machine.loading && machine.tapeInput == null;
    }

    @Override
    public void run() {
        AudioTrack track = createTrack();
        boolean playing = false;
        long nextFrame = System.nanoTime();
        final long frameNs = 1_000_000_000L / FPS;

        while (running) {
            synchronized (lock) {
                if (isPaused() && running) {
                    if (track != null && playing) {
                        track.pause();
                        track.flush();
                        playing = false;
                    }
                    while (isPaused() && running && queue.isEmpty()) {
                        try {
                            lock.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            drainQueue();
            if (!running) break;
            if (isPaused()) {
                // a queued task (e.g. state load) may have changed the screen
                if (dirty) {
                    machine.renderStatic();
                    dirty = false;
                }
                renderMap();
                publish();
                continue;
            }

            float sp = Math.max(0.01f, Math.min(1f, speed));
            boolean turbo = turboLoad && sp >= 1f && tapeLoading();
            turboActive = turbo;
            int samples;
            if (turbo) {
                // ~10x: skip rendering of intermediate frames, keep the border stripes of the last
                machine.runFast(9);
                samples = machine.runFrame(audioBuf);
            } else if (sp >= 1f) {
                samples = machine.runFrame(audioBuf);
            } else {
                samples = machine.run(audioBuf, Math.round(Spectrum48.T_FRAME * sp), true, false);
            }
            // the trace clock follows real time, so flashes last the same at any emulation speed
            if (machine.trace.enabled) {
                machine.trace.tick();
                if ((++frameCount & 1) == 0) renderMap(); // 25 Hz is plenty for the map
            }
            publish();

            boolean useAudio = sound && track != null;
            if (useAudio) {
                if (!playing) {
                    track.play();
                    playing = true;
                }
                // squeeze/stretch one emulated slice into one real 20 ms slice (pitch follows speed)
                int outN = OUT_PER_FRAME;
                if (samples > 0 && !turbo) {
                    for (int i = 0; i < outN; i++) {
                        int a = (int) ((long) i * samples / outN);
                        int b = Math.max(a + 1, (int) ((long) (i + 1) * samples / outN));
                        if (a >= samples) a = samples - 1;
                        if (b > samples) b = samples;
                        int sum = 0;
                        for (int k = a; k < b; k++) sum += audioBuf[k];
                        outBuf[i] = (short) (sum / (b - a));
                    }
                } else {
                    java.util.Arrays.fill(outBuf, 0, outN, (short) 0);
                }
                track.write(outBuf, 0, outN); // blocking: paces emulation
                nextFrame = System.nanoTime();
            } else {
                if (playing) {
                    track.pause();
                    track.flush();
                    playing = false;
                }
                nextFrame += frameNs;
                long now = System.nanoTime();
                long wait = nextFrame - now;
                if (wait > 0) sleepNs(wait);
                else if (wait < -5 * frameNs) nextFrame = now;
            }
        }
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }
    }

    private void renderMap() {
        if (!machine.trace.enabled) return;
        int layout = machine.trace.layout;
        machine.trace.render(machine, mapBack, layout);
        int n = MemTrace.width(layout) * MemTrace.height(layout);
        synchronized (mapFrame) {
            System.arraycopy(mapBack, 0, mapFrame, 0, n);
            mapLayout = layout;
        }
        mapVersion++;
    }

    private void publish() {
        synchronized (frame) {
            System.arraycopy(machine.screen, 0, frame, 0, frame.length);
        }
        listener.onFrame();
    }

    private static void sleepNs(long ns) {
        try {
            Thread.sleep(ns / 1_000_000L, (int) (ns % 1_000_000L));
        } catch (InterruptedException ignored) {
        }
    }
}
