package com.rey.modelquery.tck.arch.fixture.jpa;

import com.rey.modelquery.tck.arch.fixture.audit.GoodAudit;

/** Violates: no module depends on audit. */
public class BadJpaAudit {
    GoodAudit audit;
}
