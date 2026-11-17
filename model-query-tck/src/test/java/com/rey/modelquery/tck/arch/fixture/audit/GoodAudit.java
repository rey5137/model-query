package com.rey.modelquery.tck.arch.fixture.audit;

import com.rey.modelquery.tck.arch.fixture.jpa.GoodJpa;
import com.rey.modelquery.tck.arch.fixture.jpa.vendor.HandOffObserver;

/** Allowed: audit depends on jpa and implements HandOffObserver. */
public class GoodAudit implements HandOffObserver {
    GoodJpa jpa;
}
