package com.rey.modelquery.tck.harness;

/** The Tier-1 database vendors (spec vendor/41 §1). */
public enum TckVendor {
    H2("H2", ""),
    POSTGRESQL("PostgreSQL", "COLLATE \"C\""),
    MYSQL("MySQL", "COLLATE utf8mb4_bin");

    private final String displayName;
    private final String textCollation;

    TckVendor(String displayName, String textCollation) {
        this.displayName = displayName;
        this.textCollation = textCollation;
    }

    public String displayName() {
        return displayName;
    }

    /** Binary collation clause so string ordering is identical on every vendor. */
    String textCollation() {
        return textCollation;
    }
}
