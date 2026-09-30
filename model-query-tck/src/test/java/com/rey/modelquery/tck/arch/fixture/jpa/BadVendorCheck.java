package com.rey.modelquery.tck.arch.fixture.jpa;

import com.rey.modelquery.tck.arch.fixture.jpa.spi.DatabaseVendor;

/** Violates: only profiles and vendor detection name a DatabaseVendor (INV-6). */
public class BadVendorCheck {
    boolean mysql(DatabaseVendor vendor) {
        return vendor == DatabaseVendor.MYSQL;
    }
}
