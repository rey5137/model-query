package com.rey.modelquery.tck.arch.fixture.portability;

import com.rey.modelquery.tck.arch.fixture.jpa.GoodJpa;
import com.rey.modelquery.tck.arch.fixture.jpa.spi.DatabaseVendor;

/** Allowed: portability depends on jpa and names a DatabaseVendor, a target's vendor. */
public class GoodPortability {
    GoodJpa jpa;
    DatabaseVendor target;
}
