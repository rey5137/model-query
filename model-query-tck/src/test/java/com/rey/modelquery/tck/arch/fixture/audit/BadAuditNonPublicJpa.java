package com.rey.modelquery.tck.arch.fixture.audit;

import com.rey.modelquery.tck.arch.fixture.jpa.JpaWithProtectedPart;

/** Violates: audit depends on public types of jpa only (except HandOffObserver). */
public class BadAuditNonPublicJpa extends JpaWithProtectedPart {
    Part nonPublic;
}
