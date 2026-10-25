package com.alexspec.zx.core;

/** Source of the tape input signal (port FE bit 6), sampled by emulated CPU time. */
public interface TapeInput {
    /** Logic level of the tape signal at the given emulated CPU cycle. */
    boolean level(long cycle);
}
