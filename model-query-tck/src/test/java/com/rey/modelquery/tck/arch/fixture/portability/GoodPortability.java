package com.rey.modelquery.tck.arch.fixture.portability;

import com.rey.modelquery.tck.arch.fixture.jpa.GoodJpa;
import com.rey.modelquery.tck.arch.fixture.jpa.spi.DatabaseVendor;
import com.rey.modelquery.tck.arch.fixture.jpa.vendor.ChildStatements;
import com.rey.modelquery.tck.arch.fixture.jpa.vendor.InlineCountFallback;
import com.rey.modelquery.tck.arch.fixture.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.tck.arch.fixture.jpa.vendor.VendorResolver;

/** Allowed: portability depends on jpa and names a DatabaseVendor, a target's vendor. */
public class GoodPortability {
    GoodJpa jpa;
    DatabaseVendor target;

    void use(ResolvedVendor resolved) {
        InlineCountFallback.observe(() -> {});
        ChildStatements.run(() -> {});
        VendorResolver.productNameOf();
        VendorResolver.resolve();
        VendorResolver.withSupplied(() -> {});
        resolved.profile();
    }
}
