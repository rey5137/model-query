package com.rey.modelquery.tck.arch.fixture.test;

import com.rey.modelquery.tck.arch.fixture.core.EngineOnly;

/** Violates: test uses no type annotated {@code @EngineFacing}. */
public class BadTestEngineFacingType {
    EngineOnly.Internal internal;
}
