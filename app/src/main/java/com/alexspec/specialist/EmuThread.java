package com.alexspec.specialist;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.alexspec.specialist.core.Specialist;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Runs the emulated machine at 50 frames per second, produces video and sound. */
final class EmuThread extends Thread {
    interface FrameListener {
        void onFrame();
    }

    final Specialist machine = new Specialist();
    /** Last rendered frame; guarded by itself. */
    final int[] frame = new int[Specialist.WIDTH * Specialist.HEIGHT];

    private final int[] back = new int[Specialist.WIDTH * Specialist.HEIGHT];
    /** Live memory map (MemTrace layout); guarded by itself. */
    final int[] mapFrame = new int[com.alexspec.specialist.core.MemTrace.MAP_W * com.alexspec.specialist.core.MemTrace.MAP_H];
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
    /** Emulation speed relative to the real machine, 0.01 (1/100) .. 1. */
    volatile float speed = 1f;
    volatile boolean sound = true;
    volatile int colorMode = Specialist.COLOR_WHITE;

    EmuThread(FrameListener listener) {
        super("Specialist");
        this.listener = listener;
        setPriority(Thread.MAX_PRIORITY - 1);
    }

    /** Runs {@code r} on the emulation thread between frames. */
    void post(Runnable r) {
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

    boolean isUserPaused() {
        return userPaused;
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
                android.util.Log.e("Specialist", "task failed", e);
            }
        }
    }

    private AudioTrack createTrack() {
        int min = AudioTrack.getMinBufferSize(Specialist.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, 882 * 2 * 3);
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Specialist.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            android.util.Log.e("Specialist", "no audio", e);
            return null;
        }
    }

    @Override
    public void run() {
        AudioTrack track = createTrack();
        boolean playing = false;
        long nextFrame = System.nanoTime();
        final long frameNs = 1_000_000_000L / Specialist.FPS;

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
                if (machine.isRomLoaded()) {
                    machine.render(back, colorMode);
                    renderMap();
                    publish();
                }
                continue;
            }

            if (!machine.isRomLoaded()) {
                java.util.Arrays.fill(back, 0xFF000000);
                publish();
                sleepMs(50);
                nextFrame = System.nanoTime();
                continue;
            }

            float sp = Math.max(0.01f, Math.min(1f, speed));
            int cycles = Math.round(Specialist.CYCLES_PER_FRAME * sp);
            int samples = machine.run(audioBuf, cycles);
            machine.render(back, colorMode);
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
                int outN = Specialist.SAMPLE_RATE / Specialist.FPS;
                if (samples > 0) {
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
        int n = com.alexspec.specialist.core.MemTrace.width(layout) * com.alexspec.specialist.core.MemTrace.height(layout);
        synchronized (mapFrame) {
            System.arraycopy(mapBack, 0, mapFrame, 0, n);
            mapLayout = layout;
        }
        mapVersion++;
    }

    private void publish() {
        synchronized (frame) {
            System.arraycopy(back, 0, frame, 0, back.length);
        }
        listener.onFrame();
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void sleepNs(long ns) {
        try {
            Thread.sleep(ns / 1_000_000L, (int) (ns % 1_000_000L));
        } catch (InterruptedException ignored) {
        }
    }
}
