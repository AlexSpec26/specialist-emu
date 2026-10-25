package com.alexspec.specialist;

import com.alexspec.specialist.core.Specialist;
import com.alexspec.specialist.core.SpecKeys;
import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class TimeControlsTest {
    private static Specialist machine(int... program) {
        Specialist m = new Specialist();
        byte[] rom = new byte[Specialist.ROM_SIZE];
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        m.loadRom(rom);
        m.hardReset();
        return m;
    }

    @Test public void oneInstructionAndExactUndo() {
        Specialist m = machine(0x3e, 0x42, 0x32, 0x00, 0x90, 0x76); // MVI; STA video; HLT
        byte[] initial = m.saveState();
        m.stepInstruction();
        assertEquals(0xc002, m.cpu.pc);
        assertEquals(0x42, m.cpu.a);
        assertEquals(7, m.getCycleCount());
        byte[] first = m.saveState();
        m.stepInstruction();
        assertEquals(0x42, m.peek(0x9000));
        assertTrue(m.backInstructions(1));
        assertArrayEquals(first, m.saveState());
        assertEquals(0, m.peek(0x9000));
        assertTrue(m.backInstructions(1));
        assertArrayEquals(initial, m.saveState());
        assertFalse(m.backInstructions(1));
    }

    @Test public void replayRestoresEveryRegisterPpiColourAndKeyboardChanges() {
        Specialist m = machine(
                0x31, 0x00, 0x80,       // LXI SP
                0x3e, 0x82, 0x32, 0x03, 0xf8, // mode 0: A/C output, B input
                0x3e, 0xfe, 0x32, 0x00, 0xf8, // select column 0
                0x21, 0x00, 0x90,       // LXI H video
                // loop at C011: read keyboard + Shift, store, change colour, stack, EI/DI
                0x3a, 0x01, 0xf8, 0x77, 0x23, 0x32, 0x02, 0xf8,
                0xf5, 0xc1, 0xfb, 0x00, 0xf3, 0xc3, 0x11, 0xc0);
        byte[][] states = new byte[1700][];
        int[] expectedScreen = new int[Specialist.WIDTH * Specialist.HEIGHT];
        for (int i = 0; i < states.length; i++) {
            if (i == 137) m.keyDown(0);
            if (i == 255) m.keyDown(SpecKeys.SHIFT);
            if (i == 513) m.keyUp(0);
            if (i == 790) m.keyUp(SpecKeys.SHIFT);
            if (i == 1101) m.keyDown(1);
            states[i] = m.saveState();
            for (int k = 0; k < 4; k++) m.stepInstruction();
        }
        m.releaseAllKeys(); // replay must use the input log, not today's keys
        for (int i = states.length - 1; i >= 0; i -= 1) {
            assertTrue("rewind " + i, m.backInstructions(4));
            assertArrayEquals("state at instruction " + i, states[i], m.saveState());
        }
        // Continue on a new timeline: no abandoned historical key may remain held.
        m.forward(20000);
        assertEquals(0xff, m.peek(0x9000));
        m.render(expectedScreen, Specialist.COLOR_8);
        byte[] state = m.saveState();
        m.forward(1000);
        try { m.loadState(state); } catch (IOException e) { throw new AssertionError(e); }
        int[] actualScreen = new int[expectedScreen.length];
        m.render(actualScreen, Specialist.COLOR_8);
        assertArrayEquals(expectedScreen, actualScreen);
    }

    @Test public void delayedEiAndHaltSurviveSaveAndUndo() throws Exception {
        Specialist m = machine(0xfb, 0x00, 0x76); // EI; NOP; HLT
        m.stepInstruction();
        byte[] ei = m.saveState();
        m.stepInstruction();
        assertFalse(m.cpu.inte);
        m.stepInstruction();
        assertTrue(m.cpu.inte);
        assertTrue(m.cpu.halted);
        byte[] halted = m.saveState();
        m.stepInstruction();
        assertTrue(m.backInstructions(1));
        assertArrayEquals(halted, m.saveState());
        m.loadState(ei);
        m.stepInstruction();
        assertFalse(m.cpu.inte);
        m.stepInstruction();
        assertTrue(m.cpu.inte);
        assertTrue(m.cpu.halted);
    }

    @Test public void secondsRetentionAndOldestBoundary() {
        Specialist m = machine(0x76); // HLT idles in four-cycle intervals
        m.forward(Specialist.CPU_HZ);
        long first = m.getCycleCount();
        byte[] state = m.saveState();
        m.forward(Specialist.CPU_HZ);
        assertEquals(Specialist.CPU_HZ, m.getCycleCount() - first);
        assertTrue(m.backTime(Specialist.CPU_HZ));
        assertArrayEquals(state, m.saveState());
        m.forward(11L * Specialist.CPU_HZ);
        assertTrue(m.historySeconds() >= 9.99f);
        assertTrue(m.historySeconds() <= 10.05f);
        assertFalse(m.backTime(20L * Specialist.CPU_HZ));
        assertEquals(0, m.historySeconds(), 0);
        byte[] oldest = m.saveState();
        assertFalse(m.backInstructions(1));
        assertArrayEquals(oldest, m.saveState());
    }

    @Test public void externalTapeCannotReplayOrEmitDuplicateEdges() throws Exception {
        Specialist m = machine(0xc3, 0x00, 0xc0);
        m.forward(1000);
        assertTrue(m.historySeconds() > 0);
        m.tapeInput = cycle -> (cycle & 16) != 0;
        byte[] state = m.saveState();
        assertFalse(m.backInstructions(1));
        assertArrayEquals(state, m.saveState());
        m.stepInstruction();
        m.tapeInput = null;
        m.stepInstruction();
        assertTrue(m.backInstructions(1));
        assertFalse(m.backInstructions(1));
        int[] edges = {0};
        m.tapeOutListener = (cycle, level) -> edges[0]++;
        m.write(0xf803, 0x80);
        byte[] outputLow = m.saveState();
        m.write(0xf802, 0x80);
        int before = edges[0];
        m.loadState(outputLow);
        assertEquals(before, edges[0]);
        assertFalse(m.canRewind());
        assertFalse(m.backTime(1));
    }

    @Test public void loadsVersionOneAndRejectsTruncationWithoutMutation() throws Exception {
        Specialist m = machine(0x3e, 0x55, 0x76);
        m.stepInstruction();
        byte[] v2 = m.saveState();
        byte[] v1 = Arrays.copyOf(v2, v2.length - 60);
        v1[7] = 1;
        m.stepInstruction();
        m.loadState(v1);
        assertEquals(0x55, m.cpu.a);
        assertEquals(0xc002, m.cpu.pc);
        assertEquals(0, m.getCycleCount());
        assertFalse(m.cpu.halted);
        byte[] before = m.saveState();
        try {
            m.loadState(Arrays.copyOf(v2, v2.length - 1));
            fail("truncated save accepted");
        } catch (IOException expected) {
            assertArrayEquals(before, m.saveState());
        }
        assertEquals(0, m.historySeconds(), 0);
    }

    @Test public void resetRomLoadAndExternalWritesStartNewHistory() throws Exception {
        Specialist m = machine(0xc3, 0x00, 0xc0);
        m.forward(1000);
        m.write(0x100, 42);
        assertFalse(m.backInstructions(1));
        assertEquals(42, m.peek(0x100));
        m.forward(1000);
        m.reset();
        assertFalse(m.backInstructions(1));
        m.forward(1000);
        m.loadRom(new byte[] {0x76});
        assertFalse(m.backInstructions(1));
        m.forward(1000);
        m.loadRks(new byte[] {0, 1, 0, 1, 0x42}, false);
        assertFalse(m.backInstructions(1));
        assertEquals(0x42, m.peek(0x100));
    }

    @Test public void speedsHalveAndEndAtOne8080ClockPerSecond() {
        assertEquals(4, Speed.factor(0), 0);
        assertEquals(1, Speed.factor(Speed.REAL), 0);
        for (int i = 1; i < Speed.STEPS - 1; i++)
            assertEquals(Speed.factor(i - 1) / 2, Speed.factor(i), 0);
        assertEquals(1, Speed.tps(Speed.STEPS - 1), 0);
        assertEquals("×4", Speed.badge(0));
        assertEquals("1 т/с", Speed.badge(Speed.STEPS - 1));
        assertTrue(Speed.describe(Speed.REAL).contains("2 млн"));
    }

    @Test public void restoredAudioContinuesBitForBitAcrossCheckpoints() throws Exception {
        Specialist m = machine(0x3e, 0x80, 0x32, 3, 0xf8, // PPI output
                0x3e, 0x20, 0x32, 2, 0xf8, 0x3e, 0, 0x32, 2, 0xf8,
                0xc3, 5, 0xc0);
        m.forward(100_000);
        byte[] before = m.saveState();
        long instruction = m.getInstructionCount();
        short[] audio = new short[2000];
        int count = m.run(audio, Specialist.CYCLES_PER_FRAME);
        byte[] after = m.saveState();
        assertTrue(m.backInstructions(m.getInstructionCount() - instruction));
        assertArrayEquals(before, m.saveState());
        short[] replay = new short[audio.length];
        assertEquals(count, m.run(replay, Specialist.CYCLES_PER_FRAME));
        assertArrayEquals(after, m.saveState());
        assertArrayEquals(audio, replay);
    }
}
