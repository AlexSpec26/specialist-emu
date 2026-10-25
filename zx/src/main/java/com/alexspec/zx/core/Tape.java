package com.alexspec.zx.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A cassette: TAP or TZX converted into a sequence of pulses (in Z80 T-states at 3.5 MHz)
 * that drive the EAR input, plus the list of data blocks for instant ("flash") loading
 * through the ROM routine.
 */
public final class Tape {
    // standard ROM timings
    static final int PILOT = 2168, SYNC1 = 667, SYNC2 = 735, ZERO = 855, ONE = 1710;
    static final int PILOT_HEADER = 8063, PILOT_DATA = 3223;
    static final int MS = 3500;

    /** A data block (for flash loading and for the UI). */
    public static final class Block {
        public final byte[] data;
        /** first pulse entry of the block and the entry right after it (incl. pause) */
        public final int startEntry, endEntry;
        public final String name;

        Block(byte[] data, int start, int end, String name) {
            this.data = data;
            this.startEntry = start;
            this.endEntry = end;
            this.name = name;
        }
    }

    // pulse list: dur > 0 = pulse, level toggles at its end; dur < 0 = pause of -dur T (low after 1 ms)
    private int[] dur = new int[1 << 16];
    private int n;
    public final List<Block> blocks = new ArrayList<>();

    // player
    private int pos;
    private long left;
    private boolean level;
    public volatile boolean playing;

    // ------------------------------------------------------------------ building

    private void add(int d) {
        if (n == dur.length) dur = java.util.Arrays.copyOf(dur, n * 2);
        dur[n++] = d;
    }

    private void tone(int len, int count) {
        for (int k = 0; k < count; k++) add(len);
    }

    private void dataPulses(byte[] d, int off, int len, int zero, int one, int lastBits) {
        for (int i = 0; i < len; i++) {
            int bits = i == len - 1 ? lastBits : 8;
            int v = d[off + i] & 0xFF;
            for (int b = 0; b < bits; b++, v <<= 1) {
                int p = (v & 0x80) != 0 ? one : zero;
                add(p);
                add(p);
            }
        }
    }

    private void pause(int ms) {
        if (ms > 0) add(-ms * MS);
    }

    private static String blockName(byte[] d) {
        if (d.length == 19 && (d[0] & 0xFF) == 0) {
            String[] types = {"Program", "Number array", "Character array", "Bytes"};
            int ty = d[1] & 0xFF;
            StringBuilder s = new StringBuilder(ty < 4 ? types[ty] : "?").append(": ");
            for (int i = 2; i < 12; i++) s.append((char) Math.max(32, Math.min(126, d[i] & 0xFF)));
            return s.toString().trim();
        }
        return (d.length > 0 && (d[0] & 0xFF) == 0xFF ? "данные " : "блок ") + d.length + " байт";
    }

    private void standardBlock(byte[] d, int pauseMs) {
        int start = n;
        boolean header = d.length > 0 && (d[0] & 0xFF) < 0x80;
        tone(PILOT, header ? PILOT_HEADER : PILOT_DATA);
        add(SYNC1);
        add(SYNC2);
        dataPulses(d, 0, d.length, ZERO, ONE, 8);
        pause(pauseMs);
        blocks.add(new Block(d, start, n, blockName(d)));
    }

    public static Tape fromTap(byte[] f) throws IOException {
        Tape t = new Tape();
        int p = 0;
        while (p + 2 <= f.length) {
            int len = (f[p] & 0xFF) | ((f[p + 1] & 0xFF) << 8);
            p += 2;
            if (p + len > f.length) throw new IOException("TAP обрезан");
            byte[] d = java.util.Arrays.copyOfRange(f, p, p + len);
            p += len;
            t.standardBlock(d, 1000);
        }
        if (t.blocks.isEmpty()) throw new IOException("пустой TAP");
        return t;
    }

    private static int u16(byte[] f, int p) {
        return (f[p] & 0xFF) | ((f[p + 1] & 0xFF) << 8);
    }

    private static int u24(byte[] f, int p) {
        return u16(f, p) | ((f[p + 2] & 0xFF) << 16);
    }

    public static Tape fromTzx(byte[] f) throws IOException {
        if (f.length < 10 || !new String(f, 0, 7, java.nio.charset.StandardCharsets.ISO_8859_1).equals("ZXTape!"))
            throw new IOException("это не TZX");
        Tape t = new Tape();
        int p = 10;
        int loopStart = -1, loopCount = 0;
        while (p < f.length) {
            int id = f[p++] & 0xFF;
            switch (id) {
                case 0x10: {
                    int pauseMs = u16(f, p), len = u16(f, p + 2);
                    byte[] d = java.util.Arrays.copyOfRange(f, p + 4, p + 4 + len);
                    t.standardBlock(d, pauseMs);
                    p += 4 + len;
                    break;
                }
                case 0x11: {
                    int pilot = u16(f, p), s1 = u16(f, p + 2), s2 = u16(f, p + 4), zero = u16(f, p + 6), one = u16(f, p + 8);
                    int pilotLen = u16(f, p + 10), lastBits = f[p + 12] & 0xFF, pauseMs = u16(f, p + 13), len = u24(f, p + 15);
                    byte[] d = java.util.Arrays.copyOfRange(f, p + 18, p + 18 + len);
                    int start = t.n;
                    t.tone(pilot, pilotLen);
                    t.add(s1);
                    t.add(s2);
                    t.dataPulses(d, 0, len, zero, one, lastBits == 0 ? 8 : lastBits);
                    t.pause(pauseMs);
                    t.blocks.add(new Block(d, start, t.n, "turbo " + blockName(d)));
                    p += 18 + len;
                    break;
                }
                case 0x12:
                    t.tone(u16(f, p), u16(f, p + 2));
                    p += 4;
                    break;
                case 0x13: {
                    int cnt = f[p] & 0xFF;
                    for (int k = 0; k < cnt; k++) t.add(u16(f, p + 1 + 2 * k));
                    p += 1 + 2 * cnt;
                    break;
                }
                case 0x14: {
                    int zero = u16(f, p), one = u16(f, p + 2), lastBits = f[p + 4] & 0xFF, pauseMs = u16(f, p + 5), len = u24(f, p + 7);
                    byte[] d = java.util.Arrays.copyOfRange(f, p + 10, p + 10 + len);
                    int start = t.n;
                    t.dataPulses(d, 0, len, zero, one, lastBits == 0 ? 8 : lastBits);
                    t.pause(pauseMs);
                    t.blocks.add(new Block(d, start, t.n, "pure " + blockName(d)));
                    p += 10 + len;
                    break;
                }
                case 0x15: { // direct recording: one bit per sample
                    int tps = u16(f, p), pauseMs = u16(f, p + 2), lastBits = f[p + 4] & 0xFF, len = u24(f, p + 5);
                    boolean cur = false;
                    int run = 0;
                    for (int i = 0; i < len; i++) {
                        int bits = i == len - 1 && lastBits != 0 ? lastBits : 8;
                        int v = f[p + 8 + i] & 0xFF;
                        for (int b = 0; b < bits; b++, v <<= 1) {
                            boolean lv = (v & 0x80) != 0;
                            if (lv != cur && run > 0) {
                                t.add(run);
                                run = 0;
                            }
                            cur = lv;
                            run += tps;
                        }
                    }
                    if (run > 0) t.add(run);
                    t.pause(pauseMs);
                    p += 8 + len;
                    break;
                }
                case 0x20:
                    t.pause(Math.max(1, u16(f, p))); // 0 = "stop the tape": treated as a short pause
                    p += 2;
                    break;
                case 0x21: p += 1 + (f[p] & 0xFF); break;
                case 0x22: break;
                case 0x23: p += 2; break;
                case 0x24: loopCount = u16(f, p); p += 2; loopStart = p; break;
                case 0x25:
                    if (loopStart >= 0 && --loopCount > 0) p = loopStart;
                    break;
                case 0x26: p += 2 + 2 * u16(f, p); break;
                case 0x27: break;
                case 0x28: p += 2 + u16(f, p); break;
                case 0x2A: p += 4; break;
                case 0x2B: p += 5; break;
                case 0x30: p += 1 + (f[p] & 0xFF); break;
                case 0x31: p += 2 + (f[p + 1] & 0xFF); break;
                case 0x32: p += 2 + u16(f, p); break;
                case 0x33: p += 1 + 3 * (f[p] & 0xFF); break;
                case 0x35: p += 20 + (u16(f, p + 16) | (u16(f, p + 18) << 16)); break;
                case 0x5A: p += 9; break;
                default:
                    // unknown block with the standard "length dword" convention
                    if (p + 4 <= f.length) p += 4 + (u16(f, p) | (u16(f, p + 2) << 16));
                    else p = f.length;
                    break;
            }
        }
        if (t.n == 0) throw new IOException("в TZX нет звука");
        return t;
    }

    // ------------------------------------------------------------------ player

    public void rewind() {
        pos = 0;
        left = n > 0 ? Math.abs(dur[0]) : 0;
        level = false;
    }

    public boolean finished() {
        return pos >= n;
    }

    /** Advances the tape by dt T-states (only while playing). */
    public void advance(long dt) {
        if (!playing) return;
        while (dt > 0 && pos < n) {
            if (dt < left) {
                left -= dt;
                return;
            }
            dt -= left;
            if (dur[pos] > 0) level = !level;
            else level = false; // a pause ends low
            pos++;
            if (pos < n) left = Math.abs(dur[pos]);
        }
        if (pos >= n) playing = false;
    }

    /**
     * Current level. A pause keeps the last level for 1 ms and then goes low (TZX rule), so the
     * edge that ends the last pulse of a block is not lost.
     */
    public boolean level() {
        if (level && pos < n && dur[pos] < 0 && -dur[pos] - left >= MS) return false;
        return level;
    }

    /** index of the next data block at/after the current position, or -1 */
    public int nextBlock() {
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).endEntry > pos) return i;
        return -1;
    }

    /** Skips the player to just after the given block. */
    public void skipPast(int blockIndex) {
        pos = blocks.get(blockIndex).endEntry;
        left = pos < n ? Math.abs(dur[pos]) : 0;
        level = false;
        if (pos >= n) playing = false;
    }

    /** progress 0..1 for the UI */
    public float progress() {
        return n == 0 ? 0 : (float) pos / n;
    }
}
