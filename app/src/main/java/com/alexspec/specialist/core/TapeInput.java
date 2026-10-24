package com.alexspec.specialist.core;

/** Source of the tape input signal (port B bit 0), sampled by emulated CPU time. */
public interface TapeInput {
    /** Logic level of the tape signal at the given emulated CPU cycle. */
    boolean level(long cycle);
}
