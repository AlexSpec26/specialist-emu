package com.alexspec.atari;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.alexspec.atari.core.AtariMachine;
import com.alexspec.atari.core.MemTrace;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs the emulated Atari in real time slices of one frame (1/49.86 s), at any speed from x4 down
 * to 1 cycle per second; produces the picture, the memory map and the sound. Same structure as
 * EmuThread of the ZX app. GPL-2.0-or-later, see atari/COPYING.
 */
final class EmuThread extends Thread {
    interface FrameListener {
        void onFrame();
    }

    static final double FPS = AtariMachine.CPU_HZ / (double) AtariMachine.T_FRAME; // 49.86
    static final int OUT_PER_FRAME = (int) Math.round(AtariMachine.SAMPLE_RATE / FPS); // 884

    final AtariMachine machine;
    /** Last published picture; guarded by itself. */
    final int[] frame = new int[AtariMachine.WIDTH * AtariMachine.HEIGHT];

    /** Live memory map (MemTrace layout); guarded by itself. */
    final int[] mapFrame = new int[MemTrace.MAP_W * MemTrace.MAP_H];
    private final int[] mapBack = new int[mapFrame.length];
    private int frameCount;
    /** layout of the picture currently in mapFrame (guarded by mapFrame) */
    int mapLayout;
    /** bumped every time mapFrame is refreshed */
    volatile int mapVersion;

    private final short[] frameAudio = new short[4096];
    private final short[] outBuf = new short[2048];
    /** emulated sound waiting to be played (slow speeds produce a whole frame at a time) */
    private final short[] fifo = new short[16384];
    private int fifoHead, fifoCount;
    private double fifoOwed;

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private final FrameListener listener;

    private volatile boolean running = true;
    private volatile boolean paused = true;
    private volatile boolean userPaused;
    /** a posted task may have changed the machine: redraw the picture while paused */
    private volatile boolean dirty;
    /** Emulation speed relative to the real machine: 4 (x4) down to 1 cycle per second. */
    volatile double speed = 1;
    /** cycles owed to the emulation at slow speeds (fractions carry over between slices) */
    private double owed;
    volatile boolean sound = true;
    /** single-step mode (paused and stepping): only the last instruction lights up on the map */
    volatile boolean stepping;

    EmuThread(AtariMachine machine, FrameListener listener) {
        super("Atari");
        this.machine = machine;
        this.listener = listener;
        setPriority(Thread.MAX_PRIORITY - 1);
    }

    /** like {@link #post} but keeps the picture as the task leaves it (single-stepping) */
    void postKeepScreen(Runnable r) {
        queue.add(r);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Runs {@code r} on the emulation thread between slices. */
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
                android.util.Log.e("Atari", "task failed", e);
            }
        }
    }

    private AudioTrack createTrack() {
        int min = AudioTrack.getMinBufferSize(AtariMachine.SAMPLE_RATE,
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
                            .setSampleRate(AtariMachine.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            android.util.Log.e("Atari", "no audio", e);
            return null;
        }
    }

    private void fifoPut(short[] s, int n) {
        for (int i = 0; i < n; i++) {
            if (fifoCount == fifo.length) { // overflow: drop the oldest
                fifoHead = (fifoHead + 1) % fifo.length;
                fifoCount--;
            }
            fifo[(fifoHead + fifoCount) % fifo.length] = s[i];
            fifoCount++;
        }
    }

    /**
     * Fills one real slice of output (OUT_PER_FRAME samples) from {@code take} emulated samples,
     * squeezed or stretched: the pitch follows the speed, as in the ZX app.
     */
    private void fifoOut(double take) {
        fifoOwed += take;
        int n = (int) Math.min(fifoCount, Math.floor(fifoOwed));
        fifoOwed -= n;
        if (fifoOwed > OUT_PER_FRAME * 4) fifoOwed = 0;
        if (n <= 0) {
            java.util.Arrays.fill(outBuf, 0, OUT_PER_FRAME, (short) 0);
            return;
        }
        for (int i = 0; i < OUT_PER_FRAME; i++) {
            int a = (int) ((long) i * n / OUT_PER_FRAME);
            int b = Math.max(a + 1, (int) ((long) (i + 1) * n / OUT_PER_FRAME));
            if (a >= n) a = n - 1;
            if (b > n) b = n;
            int sum = 0;
            for (int k = a; k < b; k++) sum += fifo[(fifoHead + k) % fifo.length];
            outBuf[i] = (short) (sum / (b - a));
        }
        fifoHead = (fifoHead + n) % fifo.length;
        fifoCount -= n;
    }

    @Override
    public void run() {
        AudioTrack track = createTrack();
        boolean playing = false;
        long nextFrame = System.nanoTime();
        final long frameNs = (long) (1_000_000_000L / FPS);

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
                if (dirty) {
                    machine.refresh();
                    dirty = false;
                }
                renderMap();
                publish();
                continue;
            }

            double sp = Math.max(1.0 / AtariMachine.CPU_HZ, Math.min(4, speed));
            if (sp > 1) {
                // x2 / x4: several frames per real slice
                int n = (int) Math.round(sp);
                for (int k = 0; k < n; k++) fifoPut(frameAudio, machine.runFrame(frameAudio));
            } else if (sp >= 1) {
                fifoPut(frameAudio, machine.runFrame(frameAudio));
            } else {
                // slow: run the cycles owed; a frame's sound arrives when the frame completes
                owed += AtariMachine.T_FRAME * sp;
                if (owed >= 1) {
                    long t0 = machine.time();
                    fifoPut(frameAudio, machine.run(frameAudio, (long) owed, false));
                    owed -= machine.time() - t0; // whole instructions: may go below zero, then we wait
                }
            }
            if (machine.trace.enabled && (++frameCount & 1) == 0) renderMap(); // 25 Hz is plenty
            publish();

            boolean useAudio = sound && track != null && sp >= 1.0 / 64;
            if (useAudio) {
                if (!playing) {
                    track.play();
                    playing = true;
                }
                fifoOut(OUT_PER_FRAME * sp);
                track.write(outBuf, 0, OUT_PER_FRAME); // blocking: paces emulation
                nextFrame = System.nanoTime();
            } else {
                fifoHead = fifoCount = 0;
                fifoOwed = 0;
                if (track != null && playing) {
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
        MemTrace tr = machine.trace;
        if (!tr.enabled) return;
        machine.syncTrace();
        int layout = tr.layout;
        tr.now = machine.time();
        // a flash lasts the same in real time at any speed, but at least one instruction
        double sp = Math.max(1.0 / AtariMachine.CPU_HZ, Math.min(4, speed));
        long w = (long) (tr.flashFrames / 50.0 * sp * AtariMachine.CPU_HZ);
        long last = machine.lastInstrStart();
        tr.window = Math.max(w, last < 0 ? 1 : tr.now - last + 1);
        tr.stepFrom = stepping && last >= 0 ? last : -1;
        tr.render(machine, mapBack, layout);
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
