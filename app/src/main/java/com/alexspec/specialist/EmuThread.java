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
    private final short[] audioBuf = new short[2048];
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private final FrameListener listener;

    private volatile boolean running = true;
    private volatile boolean paused = true;
    volatile boolean turbo;
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
                if (paused && running) {
                    if (track != null && playing) {
                        track.pause();
                        track.flush();
                        playing = false;
                    }
                    while (paused && running && queue.isEmpty()) {
                        try {
                            lock.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            drainQueue();
            if (!running) break;
            if (paused) continue;

            if (!machine.isRomLoaded()) {
                java.util.Arrays.fill(back, 0xFF000000);
                publish();
                sleepMs(50);
                nextFrame = System.nanoTime();
                continue;
            }

            boolean fast = turbo;
            int frames = fast ? 5 : 1;
            int samples = 0;
            for (int i = 0; i < frames; i++) samples = machine.runFrame(audioBuf);
            machine.render(back, colorMode);
            publish();

            boolean useAudio = sound && !fast && track != null;
            if (useAudio) {
                if (!playing) {
                    track.play();
                    playing = true;
                }
                track.write(audioBuf, 0, samples); // blocking: paces emulation
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
