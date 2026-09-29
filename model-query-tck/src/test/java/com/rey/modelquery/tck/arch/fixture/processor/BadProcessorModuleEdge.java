package com.rey.modelquery.tck.arch.fixture.processor;

import com.rey.modelquery.tck.arch.fixture.jpa.GoodJpa;

/** Violates: processor depends only on annotations and JavaPoet. */
public class BadProcessorModuleEdge {
    GoodJpa jpa;
}
