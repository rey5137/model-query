package com.rey.modelquery.tck.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * One database and version the TCK runs against. The default set is what every PR gates on (R-QA-08); with
 * {@code -Dtck.full-matrix=true} it is every Tier-1 version (R-QA-09, spec vendor/41 §1).
 */
public record TckTarget(TckVendor vendor, String version) {

    public static final String FULL_MATRIX_PROPERTY = "tck.full-matrix";

    /** H2 has no container, so its version is the bundled one and not part of the identity. */
    public static TckTarget h2() {
        return new TckTarget(TckVendor.H2, "embedded");
    }

    public static TckTarget postgresql(String version) {
        return new TckTarget(TckVendor.POSTGRESQL, version);
    }

    public static TckTarget mysql(String version) {
        return new TckTarget(TckVendor.MYSQL, version);
    }

    public static boolean fullMatrix() {
        return Boolean.getBoolean(FULL_MATRIX_PROPERTY);
    }

    public static List<TckTarget> selected() {
        return fullMatrix() ? fullMatrixTargets() : defaultTargets();
    }

    public static List<TckTarget> defaultTargets() {
        return List.of(h2(), postgresql("17"), mysql("8.4"));
    }

    public static List<TckTarget> fullMatrixTargets() {
        List<TckTarget> all = new ArrayList<>();
        all.add(h2());
        for (String v : List.of("14", "15", "16", "17")) {
            all.add(postgresql(v));
        }
        for (String v : List.of("8.0", "8.4")) {
            all.add(mysql(v));
        }
        return List.copyOf(all);
    }

    public String displayName() {
        return vendor == TckVendor.H2 ? vendor.displayName() : vendor.displayName() + " " + version;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
