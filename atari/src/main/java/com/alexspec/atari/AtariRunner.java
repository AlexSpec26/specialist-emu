package com.alexspec.atari;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * The emulation thread. It owns the native engine: opens programs, runs frames at the
 * machine's rate, plays the sound and writes/reads save states. The UI talks to it only
 * through {@link #post} (work done on this thread), {@link #pause}, and the input fields
 * {@link #buttons} / {@link #keys}.
 * GPL-2.0-or-later, see atari/COPYING.
 */
final class AtariRunner extends Thread {
    interface Listener {
        /** A new picture is in {@link #screen}; called on the emulation thread. */
        void frame();

        /** A message for the user; called on the UI thread. */
        void message(String text);
    }

    /** A piece of work run on the emulation thread; an exception is shown to the user. */
    interface Work {
        void run() throws Exception;
    }

    static final int SAMPLE_RATE = 44100;
    /** Largest file accepted as a program, disk or cartridge. */
    static final int MAX_CONTENT = 32 * 1024 * 1024;
    private static final int MAX_STATE = 8 * 1024 * 1024;
    private static final String STATE_MAGIC = "ATARI-STATE-1";
    /** Part of every state profile: a state is only valid for this engine and machine. */
    private static final String ENGINE_ID = "Atari800-4e7fbc7-PAL-64K-";

    /** Joypad bits held now (see {@link PlayView}). */
    final AtomicInteger buttons = new AtomicInteger();
    /** Keys held now, indexed by libretro RETROK_* code. */
    final AtomicIntegerArray keys = new AtomicIntegerArray(NativeAtari.KEYS);

    /** Last finished picture; guarded by itself. */
    final int[] screen = new int[NativeAtari.MAX_PIXELS];
    /** Size of the picture in {@link #screen}; guarded by screen. */
    int width = 336, height = 240;

    volatile boolean sound = true;
    private volatile boolean running = true, paused = true;

    private final int[] back = new int[NativeAtari.MAX_PIXELS];
    private final int[] geometry = new int[2];
    private final short[] audio = new short[8192];
    private final byte[] held = new byte[NativeAtari.KEYS];
    private final ConcurrentLinkedQueue<Work> queue = new ConcurrentLinkedQueue<>();
    private final Object wake = new Object();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File root;
    private final Listener listener;

    // Emulation-thread state.
    private boolean opened;
    /** Identifies program + firmware + BASIC setting; save states are stored per profile. */
    private String profile = "";
    private double fps = 49.86076;

    AtariRunner(File root, Listener listener) {
        super("Atari emulation");
        this.root = root;
        this.listener = listener;
    }

    // ---- called from the UI thread ----

    void post(Work work) {
        queue.add(work);
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    void pause(boolean value) {
        paused = value;
        clearInput();
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    void quit() {
        running = false;
        clearInput();
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    private void clearInput() {
        buttons.set(0);
        for (int i = 0; i < NativeAtari.KEYS; i++) keys.set(i, 0);
    }

    private void message(String text) {
        ui.post(() -> listener.message(text));
    }

    // ---- called on the emulation thread (from posted work) ----

    /**
     * Starts the machine with a program ("" = Atari BASIC). The previous machine is
     * auto-saved first; with {@code auto} the new one resumes from its auto-save.
     */
    void open(String path, boolean basic, boolean auto) throws Exception {
        if (opened) save("auto");
        String nextProfile = profile(path, basic);
        if (!NativeAtari.open(root.getAbsolutePath(), path, basic)) {
            opened = false;
            throw new IOException("Не удалось открыть программу. Выберите другую или Atari BASIC в меню.");
        }
        opened = true;
        profile = nextProfile;
        fps = NativeAtari.fps();
        clearInput();
        if (auto && stateFile("auto").exists()) {
            try {
                load("auto");
            } catch (IOException e) {
                message("Автосохранение пропущено: " + e.getMessage());
            }
        }
    }

    void reset() {
        if (!opened) return;
        NativeAtari.reset();
        clearInput();
    }

    void save(String slot) throws Exception {
        if (!opened) throw new IOException("Машина ещё не запущена");
        byte[] data = NativeAtari.save();
        if (data == null) throw new IOException("Ядро не создало снимок");
        AtomicFile file = new AtomicFile(stateFile(slot));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            DataOutputStream d = new DataOutputStream(out);
            d.writeUTF(STATE_MAGIC);
            d.writeUTF(profile);
            d.writeUTF(digest(data));
            d.writeInt(data.length);
            d.write(data);
            d.flush();
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }

    void load(String slot) throws Exception {
        if (!opened) throw new IOException("Машина ещё не запущена");
        try (DataInputStream in = new DataInputStream(new FileInputStream(stateFile(slot)))) {
            if (!STATE_MAGIC.equals(in.readUTF()) || !profile.equals(in.readUTF())) {
                throw new IOException("Снимок от другой программы/ПЗУ");
            }
            String hash = in.readUTF();
            int size = in.readInt();
            if (size < 16 || size > MAX_STATE) throw new IOException("Повреждённый снимок");
            byte[] data = new byte[size];
            in.readFully(data);
            if (in.read() != -1 || !hash.equals(digest(data))) throw new IOException("Повреждённый снимок");
            if (!NativeAtari.load(data)) throw new IOException("Ядро отклонило снимок");
            clearInput();
        }
    }

    private File stateFile(String slot) {
        File dir = new File(root, "states");
        dir.mkdirs();
        return new File(dir, profile + "-" + slot + ".state");
    }

    private String profile(String path, boolean basic) throws Exception {
        StringBuilder id = new StringBuilder(ENGINE_ID).append(basic);
        if (!path.isEmpty()) {
            File file = new File(path);
            // Library files are already named by the SHA-256 of their original content.
            // Games may write to their disk image, so hashing it again would change the profile.
            boolean inLibrary = file.getParentFile() != null && file.getParentFile().getName().equals("games");
            id.append(inLibrary ? file.getName() : digest(read(file, MAX_CONTENT)));
        }
        for (String name : new String[] {"ATARIXL.ROM", "ATARIBAS.ROM"}) {
            File rom = new File(root, name);
            id.append(rom.exists() ? digest(read(rom, 16384)) : "Altirra");
        }
        return digest(id.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String digest(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder s = new StringBuilder();
        for (byte b : hash) s.append(String.format(Locale.US, "%02x", b & 255));
        return s.toString();
    }

    static byte[] read(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IOException("Слишком большой файл");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ---- the loop ----

    @Override
    public void run() {
        AudioTrack track = null;
        boolean playing = false;
        try {
            // If the activity was recreated, the old runner may still be alive: take the
            // engine over and stop it. Its loop exits once it sees it is no longer the owner.
            synchronized (NativeAtari.class) {
                if (NativeAtari.owner instanceof AtariRunner && NativeAtari.owner != this) {
                    ((AtariRunner) NativeAtari.owner).quit();
                }
                NativeAtari.owner = this;
            }
            track = openAudio();
            long next = System.nanoTime();
            while (running || !queue.isEmpty()) {
                synchronized (NativeAtari.class) {
                    if (NativeAtari.owner != null && NativeAtari.owner != this) break;
                    NativeAtari.owner = this;
                    Work work;
                    while ((work = queue.poll()) != null) {
                        try {
                            work.run();
                        } catch (Exception e) {
                            message(e.getMessage() == null ? e.toString() : e.getMessage());
                        }
                    }
                }
                if (!running) break;

                if (paused || !opened) {
                    if (track != null && playing) {
                        track.pause();
                        track.flush();
                        playing = false;
                    }
                    synchronized (wake) {
                        if (running && queue.isEmpty()) {
                            try {
                                wake.wait();
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                    next = System.nanoTime();
                    continue;
                }

                int samples;
                synchronized (NativeAtari.class) {
                    if (NativeAtari.owner != this) break;
                    for (int i = 0; i < NativeAtari.KEYS; i++) held[i] = (byte) keys.get(i);
                    samples = NativeAtari.frame(back, audio, geometry, buttons.get(), held);
                }
                if (samples < 0) {
                    paused = true;
                    message("Ошибка выполнения ядра");
                    continue;
                }
                synchronized (screen) {
                    width = geometry[0];
                    height = geometry[1];
                    System.arraycopy(back, 0, screen, 0, width * height);
                }
                listener.frame();

                if (sound && track != null && samples > 0) {
                    // A blocking write into the audio buffer paces the emulation.
                    if (!playing) {
                        track.play();
                        playing = true;
                    }
                    track.write(audio, 0, samples);
                    next = System.nanoTime();
                } else {
                    // No sound: pace by the clock.
                    if (track != null && playing) {
                        track.pause();
                        track.flush();
                        playing = false;
                    }
                    next += (long) (1e9 / fps);
                    long wait = next - System.nanoTime();
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1000000, (int) (wait % 1000000));
                        } catch (InterruptedException ignored) {
                        }
                    } else if (wait < -100000000) {
                        next = System.nanoTime(); // more than 0.1 s behind: don't try to catch up
                    }
                }
            }
        } catch (Throwable e) {
            message("Ошибка эмуляции: " + e.getMessage());
        } finally {
            if (track != null) {
                if (playing) track.pause();
                track.release();
            }
            synchronized (NativeAtari.class) {
                if (NativeAtari.owner == this) {
                    NativeAtari.close();
                    NativeAtari.owner = null;
                }
            }
        }
    }

    private AudioTrack openAudio() {
        try {
            int size = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(16384, size), AudioTrack.MODE_STREAM);
            if (track.getState() == AudioTrack.STATE_INITIALIZED) return track;
            track.release();
        } catch (RuntimeException ignored) {
        }
        message("Звук недоступен: эмуляция продолжится без него");
        return null;
    }
}
