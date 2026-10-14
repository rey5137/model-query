package com.rey.modelquery.tck.arch.fixture.jpa;

import com.rey.modelquery.tck.arch.fixture.portability.GoodPortability;

/** Violates: no module depends on portability. */
public class BadJpaPortability {
    GoodPortability portability;
}
