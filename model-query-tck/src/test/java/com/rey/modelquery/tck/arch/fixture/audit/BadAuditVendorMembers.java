package com.rey.modelquery.tck.arch.fixture.audit;

import com.rey.modelquery.tck.arch.fixture.jpa.vendor.VendorResolver;

/** Violates: audit uses jpa.vendor types other than HandOffObserver. */
public class BadAuditVendorMembers {
    VendorResolver resolver;
}
