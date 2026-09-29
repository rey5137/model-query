package com.rey.modelquery.tck.arch.fixture.annotations;

import com.rey.modelquery.tck.arch.fixture.core.GoodCore;

/** Violates: annotations depends on no other module (dependencies flow one way). */
public class BadAnnotations {
    GoodCore core;
}
