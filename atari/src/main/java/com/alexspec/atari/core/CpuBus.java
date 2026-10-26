package com.alexspec.atari.core;

/** Byte-wide NMOS 6502 bus. Addresses and values passed to it are already masked. */
public interface CpuBus {
    int read(int address);
    void write(int address, int value);
}
