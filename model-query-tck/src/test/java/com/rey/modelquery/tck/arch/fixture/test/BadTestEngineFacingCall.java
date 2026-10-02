package com.rey.modelquery.tck.arch.fixture.test;

import com.rey.modelquery.tck.arch.fixture.core.EngineOnly;

/** Violates: test calls no member annotated {@code @EngineFacing}. */
public class BadTestEngineFacingCall {
    void call() {
        EngineOnly.seam();
    }
}
