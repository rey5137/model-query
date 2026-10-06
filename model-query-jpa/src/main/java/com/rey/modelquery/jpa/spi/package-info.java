/**
 * The vendor and provider SPI: {@link com.rey.modelquery.jpa.spi.VendorProfile} for what differs by database and
 * {@link com.rey.modelquery.jpa.spi.ProviderSupport} for what differs by persistence provider, bulk inserts included
 * through its {@link com.rey.modelquery.jpa.spi.InsertSupport}. Both are found with {@code ServiceLoader}, so a module
 * can improve on a portable fallback without {@code model-query-jpa} depending on it (INV-7).
 */
package com.rey.modelquery.jpa.spi;
