package com.alexspec.zx;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Log;

import com.alexspec.zx.core.Spectrum48;
import com.alexspec.zx.core.TapeDecoder;

/**
 * Microphone as the Spectrum tape input (EAR). Records 44.1 kHz mono with as little system
 * processing as the phone allows (the phone's own AGC / noise suppression mangle the square
 * tape signal), and pushes the samples into a {@link TapeDecoder}.
 */
final class MicTape {
    static final int RATE = 44100;

    final TapeDecoder decoder = new TapeDecoder(RATE, Spectrum48.CPU_HZ);
    private AudioRecord rec;
    private Thread thread;
    private volatile boolean running;
    /** which audio source was actually opened (for the help/diagnostics) */
    String sourceName = "";

    /** Starts recording; returns false if the microphone could not be opened. */
    synchronized boolean start() {
        if (running) return true;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        int size = Math.max(min, RATE / 5 * 2); // ~100 ms
        // UNPROCESSED is ideal (no AGC, no filters); VOICE_RECOGNITION is the usual "raw-ish" fallback
        int[] sources = {MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC};
        String[] names = {"без обработки", "распознавание речи", "микрофон"};
        for (int i = 0; i < sources.length && rec == null; i++) {
            try {
                AudioRecord r = new AudioRecord(sources[i], RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, size);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = r;
                    sourceName = names[i];
                } else {
                    r.release();
                }
            } catch (SecurityException | IllegalArgumentException e) {
                Log.w("ZX", "mic source " + names[i], e);
            }
        }
        if (rec == null) return false;
        disableEffects(rec.getAudioSessionId());
        try {
            rec.startRecording();
        } catch (IllegalStateException e) {
            rec.release();
            rec = null;
            return false;
        }
        decoder.resync();
        running = true;
        thread = new Thread(this::loop, "ZXMic");
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
        return true;
    }

    private static void disableEffects(int session) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl agc = AutomaticGainControl.create(session);
                if (agc != null) agc.setEnabled(false);
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(false);
            }
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(false);
            }
        } catch (RuntimeException e) {
            Log.w("ZX", "audio effects", e);
        }
    }

    private void loop() {
        short[] buf = new short[RATE / 50]; // 20 ms
        while (running) {
            AudioRecord r = rec;
            if (r == null) break;
            int n = r.read(buf, 0, buf.length);
            if (n > 0) decoder.feed(buf, n);
            else if (n < 0) break;
        }
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(300);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        if (rec != null) {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            rec.release();
            rec = null;
        }
    }

    boolean isRunning() {
        return running;
    }
}
