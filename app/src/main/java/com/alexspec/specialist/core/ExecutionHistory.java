package com.alexspec.specialist.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;

/** Emulator-thread-only checkpoints and exact instruction-boundary keyboard input log. */
final class ExecutionHistory {
    private static final long WINDOW = 10L * Specialist.CPU_HZ;
    private final Specialist machine;
    private final ArrayDeque<Snapshot> snapshots = new ArrayDeque<>();
    private final ArrayDeque<Input> inputs = new ArrayDeque<>();
    private long nextSnapshot;
    private boolean replaying;
    private ArrayList<Input> replayInputs;
    private int replayPos;

    private static final class Input {
        final long instruction;
        final int[] keys;
        final boolean shift;

        Input(Specialist m) {
            instruction = m.instructions;
            keys = m.sampledKeys.clone();
            shift = m.sampledShift;
        }

        void apply(Specialist m) {
            System.arraycopy(keys, 0, m.sampledKeys, 0, keys.length);
            m.sampledShift = shift;
        }
    }

    private static final class Snapshot {
        final byte[] state;
        final long cycle, instruction;
        final Input input;

        Snapshot(Specialist m) {
            state = m.saveState();
            cycle = m.getCycleCount();
            instruction = m.instructions;
            input = new Input(m);
        }
    }

    ExecutionHistory(Specialist machine) { this.machine = machine; }

    void beforeInstruction() {
        if (replaying) {
            while (replayPos < replayInputs.size()
                    && replayInputs.get(replayPos).instruction <= machine.instructions) {
                replayInputs.get(replayPos++).apply(machine);
            }
            return;
        }
        boolean changed = machine.sampleInputs();
        if (!machine.canRewind()) {
            clear();
            return;
        }
        if (changed) inputs.addLast(new Input(machine));
        long now = machine.getCycleCount();
        if (snapshots.isEmpty() || now >= nextSnapshot) {
            snapshots.addLast(new Snapshot(machine));
            nextSnapshot = now + Specialist.CYCLES_PER_FRAME;
            // Keep the checkpoint immediately before the ten-second cutoff too.
            while (snapshots.size() > 1) {
                Snapshot first = snapshots.removeFirst();
                if (snapshots.peekFirst().cycle > now - WINDOW) {
                    snapshots.addFirst(first);
                    break;
                }
            }
            long oldest = snapshots.peekFirst().instruction;
            while (!inputs.isEmpty() && inputs.peekFirst().instruction < oldest) inputs.removeFirst();
        }
    }

    boolean back(long amount, boolean byTime) {
        if (amount < 0) throw new IllegalArgumentException("negative rewind");
        if (!machine.canRewind()) {
            clear();
            return false;
        }
        if (amount == 0) return true;
        long current = byTime ? machine.getCycleCount() : machine.instructions;
        long target = current - amount;
        Snapshot best = null;
        for (Snapshot s : snapshots) {
            long value = byTime ? s.cycle : s.instruction;
            if (value <= target) best = s;
            else break;
        }
        boolean reached = best != null;
        if (best == null) best = snapshots.peekFirst();
        if (best == null) return false;
        replayInputs = new ArrayList<>();
        for (Input input : inputs) {
            if (input.instruction >= best.instruction) replayInputs.add(input);
        }
        replayPos = 0;
        replaying = true;
        try {
            machine.restoreState(best.state);
            best.input.apply(machine);
            if (reached) {
                while ((byTime ? machine.getCycleCount() : machine.instructions) < target) {
                    machine.stepInstruction();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("corrupt internal checkpoint", e);
        } finally {
            replaying = false;
            replayInputs = null;
            machine.useLiveInputsNext();
        }
        // Rewinding creates a new timeline; never reuse abandoned future input/state.
        while (!snapshots.isEmpty() && snapshots.peekLast().instruction > machine.instructions)
            snapshots.removeLast();
        while (!inputs.isEmpty() && inputs.peekLast().instruction >= machine.instructions)
            inputs.removeLast();
        nextSnapshot = snapshots.isEmpty() ? 0
                : snapshots.peekLast().cycle + Specialist.CYCLES_PER_FRAME;
        machine.trace.clear();
        return reached;
    }

    float seconds() {
        if (!machine.canRewind() || snapshots.isEmpty()) return 0;
        return (machine.getCycleCount() - snapshots.peekFirst().cycle) / (float) Specialist.CPU_HZ;
    }

    void clear() {
        snapshots.clear();
        inputs.clear();
        nextSnapshot = 0;
    }
}
