package com.rey.modelquery.tck.harness;

import java.util.List;

/** The Tier-1 database vendors (spec vendor/41 §1). */
public enum TckVendor {
    H2("H2", "", "VARCHAR_IGNORECASE(150)", "VARBINARY(64)", "TIMESTAMP(6)", List.of()),
    POSTGRESQL("PostgreSQL", "COLLATE \"C\"", "VARCHAR(150) COLLATE tck_case_insensitive", "BYTEA", "TIMESTAMP(6)",
            List.of("CREATE COLLATION tck_case_insensitive (provider = icu, locale = 'und-u-ks-level2', "
                    + "deterministic = false)")),
    MYSQL("MySQL", "COLLATE utf8mb4_bin", "VARCHAR(150) COLLATE utf8mb4_general_ci", "VARBINARY(64)",
            "DATETIME(6)", List.of());

    private final String displayName;
    private final String textCollation;
    private final String caseInsensitiveText;
    private final String binaryType;
    private final String microTimestamp;
    private final List<String> setup;

    TckVendor(String displayName, String textCollation, String caseInsensitiveText, String binaryType,
            String microTimestamp, List<String> setup) {
        this.displayName = displayName;
        this.textCollation = textCollation;
        this.caseInsensitiveText = caseInsensitiveText;
        this.binaryType = binaryType;
        this.microTimestamp = microTimestamp;
        this.setup = setup;
    }

    public String displayName() {
        return displayName;
    }

    /** Binary collation clause so string ordering is identical on every vendor. */
    String textCollation() {
        return textCollation;
    }

    /** A {@code VARCHAR(150)} that compares case-insensitively, for a key Java tells apart and SQL does not. */
    String caseInsensitiveText() {
        return caseInsensitiveText;
    }

    /** A {@code byte[]} column's type: PostgreSQL has no {@code VARBINARY} (TCK AC-PAG-18). */
    String binaryType() {
        return binaryType;
    }

    /** A timestamp column keeping microseconds, so a cursor's nanosecond value round-trips (TCK AC-PAG-18). */
    String microTimestamp() {
        return microTimestamp;
    }

    /** Statements the schema needs first, such as PostgreSQL's case-insensitive collation. */
    List<String> setup() {
        return setup;
    }
}
