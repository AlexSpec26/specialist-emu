package com.alexspec.specialist.core;

/** Memory / IO bus seen by the CPU. Addresses are 0..0xFFFF, values 0..0xFF. */
public interface Bus {
    int read(int addr);

    void write(int addr, int value);

    int in(int port);

    void out(int port, int value);
}
