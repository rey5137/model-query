package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.core.Incubating;

/**
 * The databases a {@link VendorProfile} can serve. Only profiles and vendor detection name a constant (INV-6).
 *
 * @implSpec R-VND-01
 */
@Incubating
public enum DatabaseVendor {
    H2,
    POSTGRESQL,
    MYSQL,
    MARIADB,
    ORACLE,
    SQLSERVER,
    /** Any other database, served by the conservative {@code OTHER} profile (R-VND-06). */
    OTHER
}
