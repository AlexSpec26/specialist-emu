package com.alexspec.specialist.core;

/**
 * Turns a recorded tape signal (e.g. from the microphone) into the clean logic level the
 * Specialist expects on port B bit 0, and serves it to the emulator by CPU time.
 *
 * Chain: 2-pole high-pass (~400 Hz, kills DC and mains hum) -> 2-pole low-pass (~5 kHz,
 * kills hiss) -> peak envelope follower
 * (automatic gain) -> noise gate -> Schmitt trigger with thresholds relative to the envelope.
 * The audio side (any thread) calls {@link #feed}; the emulator side calls {@link #level}.
 */
public final class TapeDecoder implements TapeInput {
    private final int sampleRate;
    private final long cpuHz;

    // filters / AGC state
    private float hpX1, hpY1, hpX2, hpY2, lp1, lp2, env;
    private final float hpA, lpA, envDecay, envA;
    /** gate: below this envelope (full scale = 1) the output is held, so hiss does not toggle it */
    public volatile float gate = 0.004f;
    /** Schmitt hysteresis as a fraction of the envelope */
    public volatile float hysteresis = 0.35f;
    private boolean out;

    // decoded levels ring buffer (1 entry per sample)
    private static final int CAP = 1 << 17; // ~3 s at 44.1 kHz
    private final boolean[] ring = new boolean[CAP];
    private volatile long produced; // total samples written
    private long lastCycle = -1;
    private double readPos;
    /** maximum lag allowed between emulator and live audio before skipping ahead (samples) */
    private final long maxLag;

    // meters (for the UI)
    private volatile float peak;
    private volatile int edgesPerSec;
    private int edgeCount, meterSamples;
    private float peakAcc;

    public TapeDecoder(int sampleRate, long cpuHz) {
        this(sampleRate, cpuHz, HP_HZ, LP_HZ);
    }

    static final double HP_HZ = 150, LP_HZ = 6000;

    public TapeDecoder(int sampleRate, long cpuHz, double hpHz, double lpHz) {
        this.sampleRate = sampleRate;
        this.cpuHz = cpuHz;
        hpA = (float) Math.exp(-2 * Math.PI * hpHz / sampleRate);
        lpA = (float) (1 - Math.exp(-2 * Math.PI * lpHz / sampleRate));
        envDecay = (float) Math.exp(-1.0 / (0.05 * sampleRate));
        envA = (float) (1 - Math.exp(-1.0 / (0.02 * sampleRate)));
        maxLag = sampleRate / 2;
    }

    /** Feeds 16-bit PCM samples. */
    public void feed(short[] buf, int n) {
        long p = produced;
        for (int i = 0; i < n; i++) {
            float x = buf[i] / 32768f;
            // two one-pole high-passes: DC, rumble and mains hum out
            float y = x - hpX1 + hpA * hpY1;
            hpX1 = x;
            hpY1 = y;
            float y2 = y - hpX2 + hpA * hpY2;
            hpX2 = y;
            hpY2 = y2;
            // two one-pole low-passes: hiss out
            lp1 += lpA * (y2 - lp1);
            lp2 += lpA * (lp1 - lp2);
            float v = lp2;
            float a = Math.abs(v);
            // envelope = running mean of |v| (~20 ms): follows the signal level, not noise spikes
            env += envA * (a - env);
            if (env > gate) {
                float h = hysteresis * env;
                boolean nv = out;
                if (v > h) nv = true;
                else if (v < -h) nv = false;
                if (nv != out) edgeCount++;
                out = nv;
            }
            ring[(int) (p & (CAP - 1))] = out;
            p++;
            peakAcc = Math.max(peakAcc, a);
            if (++meterSamples >= sampleRate / 10) {
                peak = peakAcc;
                edgesPerSec = edgeCount * 10;
                peakAcc = 0;
                edgeCount = 0;
                meterSamples = 0;
            }
        }
        produced = p;
    }

    /**
     * Emulator side: level at the given CPU cycle, following live audio with bounded lag.
     * The emulator's clock and the sound card's clock never agree exactly, so the read position
     * runs up to 2% faster or slower to keep about 50 ms behind the newest sample (a loader
     * tolerates that easily); only a big jump (emulation stalled) is fixed by skipping.
     */
    @Override
    public synchronized boolean level(long cycle) {
        long have = produced;
        if (have == 0) return out;
        double target = sampleRate / 20.0;
        if (lastCycle < 0 || cycle < lastCycle) {
            lastCycle = cycle;
            readPos = Math.max(0, have - target);
        }
        double lag = have - readPos;
        double rate = 1 + Math.max(-0.02, Math.min(0.02, (lag - target) / target * 0.02));
        readPos += (cycle - lastCycle) * (double) sampleRate / cpuHz * rate;
        lastCycle = cycle;
        if (readPos >= have) {
            readPos = have - 1; // emulator is ahead of the microphone: wait on the newest sample
        } else if (have - readPos > maxLag || have - readPos >= CAP) {
            readPos = have - target; // fell far behind: skip ahead to stay live
        }
        return ring[(int) (((long) readPos) & (CAP - 1))];
    }

    /** Restart time alignment (call when (re)connecting to the emulator). */
    public synchronized void resync() {
        lastCycle = -1;
    }

    /** Peak level over the last 0.1 s, 0..1 (after DC removal). */
    public float peak() {
        return peak;
    }

    /** Logic transitions per second (activity of a real tape signal is in the thousands). */
    public int edgesPerSecond() {
        return edgesPerSec;
    }
}
