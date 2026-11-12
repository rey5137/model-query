package com.rey.modelquery.tck.arch.fixture.jpa.vendor;

/** Fixture for D-139's named vendor resolver members. */
public final class VendorResolver {
    public static String productNameOf() {
        return "fixture";
    }

    public static ResolvedVendor resolve() {
        return null;
    }

    public static void withSupplied(Runnable call) {
        call.run();
    }

    public static void other() {}
}
