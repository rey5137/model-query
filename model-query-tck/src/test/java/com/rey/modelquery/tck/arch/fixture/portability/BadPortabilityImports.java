package com.rey.modelquery.tck.arch.fixture.portability;

import com.rey.modelquery.tck.arch.fixture.hibernate.BadSpringImport;

/** Violates: portability depends on jpa only, never a provider, JUnit, AssertJ or the hibernate module. */
public class BadPortabilityImports {
    org.hibernate.Session session;
    org.junit.jupiter.api.Test test;
    org.assertj.core.api.Assertions assertions;
    BadSpringImport hibernateModule;
}
