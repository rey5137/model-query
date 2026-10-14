package com.rey.modelquery.portability;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A database the portability dry run boots a factory for: a vendor and, optionally, its full version. With no version
 * the persistence provider's minimum version for the vendor's dialect applies, so the answer then depends on the
 * provider line; name the version deployed instead.
 *
 * @implSpec R-INS-10
 */
@Incubating
public final class Target {

    /** A major version, then an optional minor and further parts, then an optional vendor suffix. */
    private static final Pattern VERSION = Pattern.compile("(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.\\d+)*(?:[-+_].*)?");

    private final DatabaseVendor vendor;
    private final String productName;
    private final String version;
    private final int major;
    private final int minor;

    private Target(DatabaseVendor vendor, String productName, String version, int major, int minor) {
        this.vendor = vendor;
        this.productName = productName;
        this.version = version;
        this.major = major;
        this.minor = minor;
    }

    /**
     * {@code vendor} at the provider's minimum dialect version.
     *
     * @throws ModelQueryConfigurationException {@code MQ4013} for a vendor with no database product name to boot by
     */
    public static Target of(DatabaseVendor vendor) {
        return new Target(vendor, productName(vendor), null, -1, -1);
    }

    /**
     * {@code vendor} at {@code version}, its full version string as the database reports it, such as
     * {@code "2.3.232"} or {@code "15.4"}; it sets the product version, major and minor the factory boots with.
     *
     * @throws ModelQueryConfigurationException {@code MQ4013} for a vendor with no database product name to boot by, or
     *     a version that does not start with a major number
     */
    public static Target of(DatabaseVendor vendor, String version) {
        String name = productName(vendor);
        Objects.requireNonNull(version, "version");
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) {
            throw new ModelQueryConfigurationException(MqCode.MQ4013, "Target " + vendor + " version '" + version
                    + "': not a version, expected one like 15.4 or 2.3.232");
        }
        int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        return new Target(vendor, name, version, Integer.parseInt(matcher.group(1)), minor);
    }

    private static String productName(DatabaseVendor vendor) {
        Objects.requireNonNull(vendor, "vendor");
        return VendorResolver.productNameOf(vendor).orElseThrow(() -> new ModelQueryConfigurationException(
                MqCode.MQ4013, "Target " + vendor + ": no database product name to boot a factory by; the dry run "
                        + "boots H2, POSTGRESQL, MYSQL and MARIADB"));
    }

    /** The vendor. */
    public DatabaseVendor vendor() {
        return vendor;
    }

    /** The full version, or empty for the provider's minimum dialect version. */
    public Optional<String> version() {
        return Optional.ofNullable(version);
    }

    /** The database product name the factory boots with, as the vendor's driver reports it. */
    public String productName() {
        return productName;
    }

    /** The major version, or empty with no version. */
    public Optional<Integer> majorVersion() {
        return version == null ? Optional.empty() : Optional.of(major);
    }

    /** The minor version, {@code 0} for a version with a major alone, or empty with no version. */
    public Optional<Integer> minorVersion() {
        return version == null ? Optional.empty() : Optional.of(minor);
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
