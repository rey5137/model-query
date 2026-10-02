package com.rey.modelquery.tck.arch.fixture.core;

import com.rey.modelquery.core.EngineFacing;

/** A stand-in for an engine seam: one member and one whole type carry {@code @EngineFacing}. */
public class EngineOnly {

    @EngineFacing
    public static void seam() {}

    @EngineFacing
    public static class Internal {}
}
