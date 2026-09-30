package com.rey.modelquery.jpa.vendor;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * Resolves the {@link VendorProfile} and the {@link ProviderSupport} of an {@code EntityManagerFactory}, once per
 * factory, and logs the result once (R-VND-02, R-VND-07). Both are found with {@code ServiceLoader} on the thread's
 * context class loader.
 *
 * @implSpec R-VND-02, R-VND-03, R-VND-04, R-VND-05, R-VND-06, R-VND-07
 */
@Incubating
public final class VendorResolver {

    /** The factory property holding the non-JTA {@code DataSource}, read for {@code DatabaseMetaData}. */
    static final String NON_JTA_DATA_SOURCE = "jakarta.persistence.nonJtaDataSource";

    private static final System.Logger LOG = System.getLogger(VendorResolver.class.getName());

    /**
     * By factory, then by configured vendor, so an explicit vendor always wins over an earlier detection (R-VND-04).
     * Weak, so a closed factory is not kept reachable; a value never references its factory.
     */
    private static final Map<EntityManagerFactory, Map<Optional<DatabaseVendor>, ResolvedVendor>> RESOLVED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VendorResolver() {
    }

    /**
     * The resolution of {@code emf}, computed on the first call for a factory and reused after. With a
     * {@code configured} vendor, detection is skipped (R-VND-04 step 1); otherwise the provider support detects the
     * vendor without a connection, else {@code DatabaseMetaData} is read once through the factory's
     * {@code jakarta.persistence.nonJtaDataSource}, else the vendor is {@code OTHER}. The first resolution of a factory
     * with the same {@code configured} is reused.
     *
     * @param configured the explicitly configured vendor, or empty to detect it
     * @throws ModelQueryConfigurationException {@code MQ4002} when two discovered profiles serve one vendor
     */
    public static ResolvedVendor resolve(EntityManagerFactory emf, Optional<DatabaseVendor> configured) {
        Objects.requireNonNull(emf, "emf");
        Objects.requireNonNull(configured, "configured");
        Map<Optional<DatabaseVendor>, ResolvedVendor> byConfig =
                RESOLVED.computeIfAbsent(emf, factory -> new ConcurrentHashMap<>());
        ResolvedVendor known = byConfig.get(configured);
        if (known != null) {
            return known;
        }
        // Detected outside the lock, so a slow DatabaseMetaData read blocks no other factory; a racing thread's
        // result is dropped and only the winner logs.
        ResolvedVendor fresh = resolve(emf, configured, ServiceLoader.load(ProviderSupport.class),
                ServiceLoader.load(VendorProfile.class));
        ResolvedVendor winner = byConfig.putIfAbsent(configured, fresh);
        if (winner != null) {
            return winner;
        }
        log(fresh);
        return fresh;
    }

    private static ResolvedVendor resolve(EntityManagerFactory emf, Optional<DatabaseVendor> configured,
            Iterable<ProviderSupport> providers, Iterable<VendorProfile> discovered) {
        ProviderSupport provider = null;
        for (ProviderSupport candidate : providers) {
            if (candidate.supports(emf)) {
                provider = candidate;
                break;
            }
        }
        Map<DatabaseVendor, VendorProfile> profiles = byVendor(discovered);
        if (configured.isPresent()) {
            return resolved(profiles, provider, configured.get(), ResolvedVendor.Source.CONFIG, "");
        }
        Optional<DatabaseVendor> detected = provider == null ? Optional.empty() : provider.detectVendor(emf);
        if (detected.isPresent()) {
            return resolved(profiles, provider, detected.get(), ResolvedVendor.Source.PROVIDER, "");
        }
        if (!(emf.getProperties().get(NON_JTA_DATA_SOURCE) instanceof DataSource dataSource)) {
            return resolved(profiles, provider, DatabaseVendor.OTHER, ResolvedVendor.Source.NONE, "no provider "
                    + "support detected the database and the factory has no " + NON_JTA_DATA_SOURCE);
        }
        String productName;
        try (Connection connection = dataSource.getConnection()) {
            productName = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            return resolved(profiles, provider, DatabaseVendor.OTHER, ResolvedVendor.Source.NONE,
                    "reading DatabaseMetaData failed: " + e);
        }
        return resolved(profiles, provider, vendorOf(productName), ResolvedVendor.Source.METADATA,
                "product name " + productName);
    }

    /** The vendor a {@code DatabaseMetaData} product name names; {@code OTHER} for any other name (R-VND-06). */
    static DatabaseVendor vendorOf(String productName) {
        if (productName == null) {
            return DatabaseVendor.OTHER;
        }
        return switch (productName) {
            case "H2" -> DatabaseVendor.H2;
            case "PostgreSQL" -> DatabaseVendor.POSTGRESQL;
            case "MySQL" -> DatabaseVendor.MYSQL;
            case "MariaDB" -> DatabaseVendor.MARIADB;
            default -> DatabaseVendor.OTHER;
        };
    }

    /**
     * The discovered profiles by vendor.
     *
     * @throws ModelQueryConfigurationException {@code MQ4002} when two serve one vendor (R-VND-03)
     */
    static Map<DatabaseVendor, VendorProfile> byVendor(Iterable<VendorProfile> discovered) {
        Map<DatabaseVendor, VendorProfile> profiles = new EnumMap<>(DatabaseVendor.class);
        for (VendorProfile profile : discovered) {
            VendorProfile other = profiles.putIfAbsent(profile.vendor(), profile);
            if (other != null) {
                throw new ModelQueryConfigurationException(MqCode.MQ4002, "VendorProfile for " + profile.vendor()
                        + ": both " + other.getClass().getName() + " and " + profile.getClass().getName()
                        + " are registered with ServiceLoader; keep one on the class path");
            }
        }
        return profiles;
    }

    /**
     * The profile of {@code vendor}: a discovered one over the built-in one, else {@code OTHER}'s, since a vendor
     * without a profile is served conservatively (R-VND-03, R-VND-06).
     */
    static VendorProfile profileFor(DatabaseVendor vendor, Map<DatabaseVendor, VendorProfile> discovered) {
        VendorProfile profile = discovered.get(vendor);
        if (profile != null) {
            return profile;
        }
        return BuiltInProfile.of(vendor)
                .orElseGet(() -> discovered.getOrDefault(DatabaseVendor.OTHER, BuiltInProfile.OTHER));
    }

    private static ResolvedVendor resolved(Map<DatabaseVendor, VendorProfile> profiles, ProviderSupport provider,
            DatabaseVendor vendor, ResolvedVendor.Source source, String detail) {
        return new ResolvedVendor(profileFor(vendor, profiles), provider, vendor, source, detail);
    }

    private static void log(ResolvedVendor resolved) {
        VendorProfile profile = resolved.profile();
        String provider = resolved.providerSupport().map(p -> p.getClass().getName()).orElse("none");
        if (resolved.source() == ResolvedVendor.Source.NONE) {
            LOG.log(System.Logger.Level.WARNING, "model-query uses the conservative OTHER profile {0}, because {1}; "
                    + "set ModelQueryConfig.vendor(...) to use the database limits (R-VND-04, R-VND-06)",
                    describe(profile), resolved.detail());
            return;
        }
        String detail = resolved.detail().isEmpty() ? "" : " (" + resolved.detail() + ")";
        String fallback = profile.vendor() == resolved.detectedVendor() ? ""
                : "; no profile serves " + resolved.detectedVendor() + ", so the conservative OTHER one does";
        LOG.log(System.Logger.Level.INFO, "model-query resolved vendor {0} from {1}{2}: profile {3}, provider support "
                + "{4}{5} (R-VND-07)", resolved.detectedVendor(), resolved.source(), detail,
                describe(profile), provider, fallback);
    }

    /** A built-in profile by its name, since MySQL's is an anonymous class; any other by its class. */
    private static String describe(VendorProfile profile) {
        return profile instanceof BuiltInProfile builtIn ? "built-in " + builtIn.name() : profile.getClass().getName();
    }
}
