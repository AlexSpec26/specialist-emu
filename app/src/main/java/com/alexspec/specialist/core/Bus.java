package com.alexspec.specialist.core;

/** Memory / IO bus seen by the CPU. Addresses are 0..0xFFFF, values 0..0xFF. */
public interface Bus {
    int read(int addr);

    /** Instruction (opcode or operand) fetch; same as read unless the bus wants to tell them apart. */
    default int fetch(int addr) {
        return read(addr);
    }

    void write(int addr, int value);

    int in(int port);

    void out(int port, int value);
}
