package com.rey.modelquery.portability;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.internal.TargetFacts;
import java.util.Objects;
import java.util.Optional;

/**
 * A database the portability dry run boots a factory for: a vendor and, optionally, its full version. With no version
 * the persistence provider's minimum version for the vendor's dialect applies, so the answer then depends on the
 * provider line; name the version deployed instead.
 *
 * @implSpec R-INS-10
 */
@Incubating
public final class Target {

    private final DatabaseVendor vendor;
    private final String version;

    private Target(DatabaseVendor vendor, String version) {
        this.vendor = vendor;
        this.version = version;
    }

    /**
     * {@code vendor} at the provider's minimum dialect version.
     *
     * @throws ModelQueryConfigurationException {@code MQ4013} for a vendor with no database product name to boot by
     */
    public static Target of(DatabaseVendor vendor) {
        TargetFacts.productName(vendor);
        return new Target(vendor, null);
    }

    /**
     * {@code vendor} at {@code version}, its full version string as the database reports it, such as
     * {@code "2.3.232"} or {@code "15.4"}; it sets the product version, major and minor the factory boots with.
     *
     * @throws ModelQueryConfigurationException {@code MQ4013} for a vendor with no database product name to boot by, or
     *     a version that does not start with a major number
     */
    public static Target of(DatabaseVendor vendor, String version) {
        TargetFacts.productName(vendor);
        Objects.requireNonNull(version, "version");
        TargetFacts.versionParts(vendor, version);
        return new Target(vendor, version);
    }

    /** The vendor. */
    public DatabaseVendor vendor() {
        return vendor;
    }

    /** The full version, or empty for the provider's minimum dialect version. */
    public Optional<String> version() {
        return Optional.ofNullable(version);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Target that && vendor == that.vendor && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vendor, version);
    }

    @Override
    public String toString() {
        return vendor + " " + (version == null ? "(dialect minimum)" : version);
    }
}
