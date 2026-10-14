package com.rey.modelquery.portability.internal;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.portability.Target;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the offline boot and the stub driver report for a {@link Target}; not API. */
public final class TargetFacts {

    /** A major version, then an optional minor and further parts, then an optional vendor suffix. */
    private static final Pattern VERSION = Pattern.compile("(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.\\d+)*(?:[-+_].*)?");

    private TargetFacts() {}

    /**
     * The database product name {@code vendor}'s driver reports.
     *
     * @throws ModelQueryConfigurationException {@code MQ4013} for a vendor with no database product name to boot by
     */
    public static String productName(DatabaseVendor vendor) {
        Objects.requireNonNull(vendor, "vendor");
        return VendorResolver.productNameOf(vendor).orElseThrow(() -> new ModelQueryConfigurationException(
                MqCode.MQ4013, "Target " + vendor + ": no database product name to boot a factory by; the dry run "
                        + "boots H2, POSTGRESQL, MYSQL and MARIADB"));
    }

    /**
     * The major and minor of {@code version}, the minor {@code 0} for a major alone.
     *
     * @throws ModelQueryConfigurationException {@code MQ4013} for a version that does not start with a major number
     */
    public static int[] versionParts(DatabaseVendor vendor, String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) {
            throw new ModelQueryConfigurationException(MqCode.MQ4013, "Target " + vendor + " version '" + version
                    + "': not a version, expected one like 15.4 or 2.3.232");
        }
        return new int[] {Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2))};
    }

    /** The database product name {@code target}'s vendor reports. */
    public static String productName(Target target) {
        return productName(target.vendor());
    }

    /** The major version, or empty with no version. */
    public static Optional<Integer> majorVersion(Target target) {
        return target.version().map(version -> versionParts(target.vendor(), version)[0]);
    }

    /** The minor version, {@code 0} for a major alone, or empty with no version. */
    public static Optional<Integer> minorVersion(Target target) {
        return target.version().map(version -> versionParts(target.vendor(), version)[1]);
    }
}
