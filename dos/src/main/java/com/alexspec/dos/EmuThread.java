package com.alexspec.dos;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs the DOS machine: one frame at a time, paced by the sound card (a blocking AudioTrack
 * write), or by the clock when there is no sound. Slow motion (½, ⅓, ¼, ⅛) runs a frame per 2,
 * 3, 4, 8 frame times and stretches its sound to match (it sounds lower, like a slowed tape).
 * The native machine is touched only from this thread: everything else is posted.
 */
final class EmuThread extends Thread {
    interface Listener {
        /** started (or failed to start); on the emulation thread */
        void onOpened(boolean ok);

        /** a new picture is in {@link #picture}; on the emulation thread */
        void onFrame();
    }

    static final int MAX_PIXELS = 1280 * 1024;

    private final String systemDir, saveDir, content;
    private final Listener listener;

    /** the latest picture, guarded by itself */
    final int[] picture = new int[MAX_PIXELS];
    int picW, picH;
    double aspect = 4.0 / 3.0;

    private final short[] audio = new short[16384];
    private short[] stretched = new short[16384 * 8];
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();

    private volatile boolean running = true;
    private volatile boolean paused;
    /** 1 = real speed, 2, 3, 4, 8 = that many times slower */
    volatile int slow = 1;
    volatile boolean sound = true;

    EmuThread(String systemDir, String saveDir, String content, Listener listener) {
        super("DOS");
        this.systemDir = systemDir;
        this.saveDir = saveDir;
        this.content = content;
        this.listener = listener;
        setPriority(Thread.MAX_PRIORITY - 1);
    }

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

    boolean isPaused() {
        return paused;
    }

    void quit() {
        synchronized (lock) {
            running = false;
            lock.notifyAll();
        }
    }

    private void drain() {
        Runnable r;
        while ((r = queue.poll()) != null) {
            try {
                r.run();
            } catch (RuntimeException e) {
                android.util.Log.e("DOS", "task failed", e);
            }
        }
    }

    /** runs one frame without sound, to show the picture of a restored point during the pause */
    void refreshPicture() {
        NativeDos.frame(audio);
        publish();
    }

    private void publish() {
        synchronized (picture) {
            int n = NativeDos.picture(picture);
            if (n > 0) {
                picW = NativeDos.width();
                picH = NativeDos.height();
                aspect = NativeDos.aspect();
            }
        }
        listener.onFrame();
    }

    private AudioTrack createTrack(int rate, double fps) {
        int perFrame = (int) Math.ceil(rate / fps) * 2 * 2; // stereo, 16 bit
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, perFrame * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            android.util.Log.e("DOS", "no audio", e);
            return null;
        }
    }

    @Override
    public void run() {
        boolean ok = NativeDos.open(systemDir, saveDir, content);
        listener.onOpened(ok);
        if (!ok) return;
        AudioTrack track = createTrack(NativeDos.sampleRate(), NativeDos.fps());
        boolean playing = false;
        long next = System.nanoTime();

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
            drain();
            if (!running) break;
            if (paused) continue;

            int n = NativeDos.frame(audio);
            publish();
            int k = Math.max(1, slow);
            long frameNs = (long) (1e9 / NativeDos.fps()) * k;
            boolean useAudio = sound && track != null && n > 0 && k <= 8;
            if (useAudio) {
                if (!playing) {
                    track.play();
                    playing = true;
                }
                if (k == 1) {
                    track.write(audio, 0, n); // blocking: paces the emulation
                } else {
                    int m = n * k;
                    if (stretched.length < m) stretched = new short[m];
                    for (int i = 0, o = 0; i + 1 < n; i += 2) {
                        for (int r = 0; r < k; r++) {
                            stretched[o++] = audio[i];
                            stretched[o++] = audio[i + 1];
                        }
                    }
                    track.write(stretched, 0, m);
                }
                next = System.nanoTime();
            } else {
                if (track != null && playing) {
                    track.pause();
                    track.flush();
                    playing = false;
                }
                next += frameNs;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    } catch (InterruptedException ignored) {
                    }
                } else if (wait < -frameNs * 4) {
                    next = System.nanoTime(); // far behind: do not race to catch up
                }
            }
        }
        if (track != null) track.release();
    }
}
