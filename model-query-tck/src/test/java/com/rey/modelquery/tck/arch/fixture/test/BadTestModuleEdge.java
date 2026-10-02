package com.rey.modelquery.tck.arch.fixture.test;

import com.rey.modelquery.tck.arch.fixture.jpa.GoodJpa;

/** Violates: test depends only on core, annotations and AssertJ. */
public class BadTestModuleEdge {
    GoodJpa jpa;
}
