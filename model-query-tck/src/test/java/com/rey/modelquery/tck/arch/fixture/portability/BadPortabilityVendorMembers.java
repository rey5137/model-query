package com.rey.modelquery.tck.arch.fixture.portability;

import com.rey.modelquery.tck.arch.fixture.jpa.vendor.ChildStatements;
import com.rey.modelquery.tck.arch.fixture.jpa.vendor.InlineCountFallback;
import com.rey.modelquery.tck.arch.fixture.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.tck.arch.fixture.jpa.vendor.VendorResolver;

/** Violates: D-139 permits only the named members, not their owners' entire API. */
public class BadPortabilityVendorMembers {
    void use(ResolvedVendor resolved, ChildStatements.Runner runner) {
        InlineCountFallback.decided(true);
        new InlineCountFallback();
        VendorResolver.other();
        resolved.other();
        runner.runChildLoad();
    }
}
