package com.rey.modelquery.tck.arch.fixture.audit;

import com.rey.modelquery.tck.arch.fixture.hibernate.BadSpringImport;

/** Violates: audit depends on jpa only, never a provider, AssertJ or the hibernate module. */
public class BadAuditImports {
    org.hibernate.Session session;
    org.assertj.core.api.Assertions assertions;
    BadSpringImport hibernateModule;
}
