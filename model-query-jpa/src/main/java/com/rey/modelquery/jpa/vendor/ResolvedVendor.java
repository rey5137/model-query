package com.rey.modelquery.jpa.vendor;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.VendorProfile;
import java.util.Optional;

/**
 * What {@link VendorResolver} resolved for one {@code EntityManagerFactory}: the profile, the provider support and
 * how the vendor was found. Holds no reference to the factory, so the factory stays collectable. Immutable.
 *
 * @implSpec R-VND-02, R-VND-04, R-VND-07
 */
@Incubating
public final class ResolvedVendor {

    /** Where the vendor came from, in resolution order (R-VND-04). */
    public enum Source {
        /** {@code ModelQueryConfig.vendor(...)}. */
        CONFIG,
        /** The persistence provider, such as the Hibernate dialect. */
        PROVIDER,
        /** {@code DatabaseMetaData#getDatabaseProductName()}. */
        METADATA,
        /** Nothing answered, so the vendor is {@code OTHER}. */
        NONE
    }

    private final VendorProfile profile;
    private final ProviderSupport providerSupport;
    private final DatabaseVendor detectedVendor;
    private final Source source;
    /** For the resolution log only: the product name, or why nothing answered. */
    private final String detail;

    ResolvedVendor(VendorProfile profile, ProviderSupport providerSupport, DatabaseVendor detectedVendor,
            Source source, String detail) {
        this.profile = profile;
        this.providerSupport = providerSupport;
        this.detectedVendor = detectedVendor;
        this.source = source;
        this.detail = detail;
    }

    /** The profile the factory's queries run with. */
    public VendorProfile profile() {
        return profile;
    }

    /** The provider support serving the factory, or empty when none does. */
    public Optional<ProviderSupport> providerSupport() {
        return Optional.ofNullable(providerSupport);
    }

    /** The vendor as configured or detected; the profile's is {@code OTHER} when this vendor has none (R-VND-06). */
    public DatabaseVendor detectedVendor() {
        return detectedVendor;
    }

    /** How {@link #detectedVendor()} was found. */
    public Source source() {
        return source;
    }

    String detail() {
        return detail;
    }
}
