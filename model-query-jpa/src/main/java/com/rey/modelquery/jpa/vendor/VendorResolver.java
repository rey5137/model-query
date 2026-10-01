package com.rey.modelquery.jpa.vendor;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * Resolves the {@link VendorProfile} and the {@link ProviderSupport} of an {@code EntityManagerFactory}, once per
 * factory, and logs the result once (R-VND-02, R-VND-07). Both are found with {@code ServiceLoader} on the thread's
 * context class loader.
 *
 * @implSpec R-VND-02, R-VND-03, R-VND-04, R-VND-05, R-VND-06, R-VND-07, R-PRF-07
 */
@Incubating
public final class VendorResolver {

    /** The factory property holding the non-JTA {@code DataSource}, read for {@code DatabaseMetaData}. */
    static final String NON_JTA_DATA_SOURCE = "jakarta.persistence.nonJtaDataSource";

    /** The one provider setting read by name: where it is set, no {@code ProviderSupport} is there to ask (Q-9). */
    static final String HIBERNATE_DEFAULT_NULL_ORDERING = "hibernate.order_by.default_null_ordering";

    private static final System.Logger LOG = System.getLogger(VendorResolver.class.getName());

    /**
     * By factory, then by configured vendor, so an explicit vendor always wins over an earlier detection (R-VND-04).
     * Weak, so a closed factory is not kept reachable; a value never references its factory.
     */
    private static final Map<EntityManagerFactory, Map<Settings, ResolvedVendor>> RESOLVED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * By factory, the supplied profile classes already logged as replacing a resolved profile, so each is logged once
     * per factory however many executors are built (R-VND-07, D-53). Weak, like {@link #RESOLVED}.
     */
    private static final Map<EntityManagerFactory, Set<Class<?>>> LOGGED_SUPPLIED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** What a resolution depends on besides the factory: the profile is fixed by both (R-PRF-07, D-34). */
    private record Settings(Optional<DatabaseVendor> configured, MysqlStreamingMode mysqlStreamingMode) {
    }

    private VendorResolver() {
    }

    /**
     * The resolution of {@code emf}, computed on the first call for a factory and reused after. With a
     * {@code configured} vendor, detection is skipped (R-VND-04 step 1); otherwise the provider support detects the
     * vendor without a connection, else {@code DatabaseMetaData} is read once through the factory's
     * {@code jakarta.persistence.nonJtaDataSource}, else the vendor is {@code OTHER}. The first resolution of a factory
     * with the same {@code configured} and {@code mysqlStreamingMode} is reused: each mode is resolved and cached
     * separately, so two configurations on one factory get their own profile (R-PRF-07). A discovered profile decides
     * its own streaming and ignores the mode.
     *
     * @param configured the explicitly configured vendor, or empty to detect it
     * @param mysqlStreamingMode the streaming mode the built-in MySQL profile is built for
     * @throws ModelQueryConfigurationException {@code MQ4002} when two discovered profiles serve one vendor
     */
    public static ResolvedVendor resolve(EntityManagerFactory emf, Optional<DatabaseVendor> configured,
            MysqlStreamingMode mysqlStreamingMode) {
        Objects.requireNonNull(emf, "emf");
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(mysqlStreamingMode, "mysqlStreamingMode");
        if (mysqlStreamingMode != MysqlStreamingMode.ROW_BY_ROW) {
            // The mode picks only between MySQL profiles, so the factory is detected and logged once, by the default
            // resolution, whatever the mode (R-VND-05, R-VND-07); a MySQL one only swaps the profile.
            ResolvedVendor base = resolve(emf, configured, MysqlStreamingMode.ROW_BY_ROW);
            if (base.detectedVendor() != DatabaseVendor.MYSQL) {
                return base;
            }
            return RESOLVED.get(emf).computeIfAbsent(new Settings(configured, mysqlStreamingMode),
                    settings -> new ResolvedVendor(
                            profileFor(DatabaseVendor.MYSQL, mysqlStreamingMode,
                                    byVendor(ServiceLoader.load(VendorProfile.class))),
                            base.providerSupport().orElse(null), base.detectedVendor(), base.source(),
                            base.detail()));
        }
        Settings settings = new Settings(configured, mysqlStreamingMode);
        Map<Settings, ResolvedVendor> byConfig = RESOLVED.computeIfAbsent(emf, factory -> new ConcurrentHashMap<>());
        ResolvedVendor known = byConfig.get(settings);
        if (known != null) {
            return known;
        }
        // Detected outside the lock, so a slow DatabaseMetaData read blocks no other factory; a racing thread's
        // result is dropped and only the winner logs.
        ResolvedVendor fresh = detect(emf, configured, ServiceLoader.load(ProviderSupport.class),
                ServiceLoader.load(VendorProfile.class));
        ResolvedVendor winner = byConfig.putIfAbsent(settings, fresh);
        if (winner != null) {
            return winner;
        }
        log(fresh);
        warnOfUnreportedNullOrdering(emf, fresh);
        return fresh;
    }

    /** The resolution under the default streaming mode, which every other mode is derived from. */
    private static ResolvedVendor detect(EntityManagerFactory emf, Optional<DatabaseVendor> configured,
            Iterable<ProviderSupport> providers, Iterable<VendorProfile> discovered) {
        MysqlStreamingMode mode = MysqlStreamingMode.ROW_BY_ROW;
        ProviderSupport provider = null;
        for (ProviderSupport candidate : providers) {
            if (candidate.supports(emf)) {
                provider = candidate;
                break;
            }
        }
        Map<DatabaseVendor, VendorProfile> profiles = byVendor(discovered);
        if (configured.isPresent()) {
            return resolved(profiles, mode, provider, configured.get(), ResolvedVendor.Source.CONFIG, "");
        }
        Optional<DatabaseVendor> detected = provider == null ? Optional.empty() : provider.detectVendor(emf);
        if (detected.isPresent()) {
            return resolved(profiles, mode, provider, detected.get(), ResolvedVendor.Source.PROVIDER, "");
        }
        if (!(emf.getProperties().get(NON_JTA_DATA_SOURCE) instanceof DataSource dataSource)) {
            return resolved(profiles, mode, provider, DatabaseVendor.OTHER, ResolvedVendor.Source.NONE, "no provider "
                    + "support detected the database and the factory has no " + NON_JTA_DATA_SOURCE);
        }
        String productName;
        try (Connection connection = dataSource.getConnection()) {
            productName = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            return resolved(profiles, mode, provider, DatabaseVendor.OTHER, ResolvedVendor.Source.NONE,
                    "reading DatabaseMetaData failed: " + e);
        }
        return resolved(profiles, mode, provider, vendorOf(productName), ResolvedVendor.Source.METADATA,
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
    static VendorProfile profileFor(DatabaseVendor vendor, MysqlStreamingMode mode,
            Map<DatabaseVendor, VendorProfile> discovered) {
        VendorProfile profile = discovered.get(vendor);
        if (profile != null) {
            return profile;
        }
        return BuiltInProfile.of(vendor, mode)
                .orElseGet(() -> discovered.getOrDefault(DatabaseVendor.OTHER, BuiltInProfile.OTHER));
    }

    /**
     * {@code resolved} with the profile supplied on {@code ModelQueryConfig.vendorProfiles(...)} for its vendor, which
     * wins over a discovered and a built-in one; when none serves the vendor and it fell back to {@code OTHER}'s
     * profile, a supplied {@code OTHER} one serves it instead. Applied after the cached resolution and not part of its
     * key, so the factory is detected and logged once whatever is supplied; a supplied profile that replaces the
     * resolved one is logged at {@code INFO} once per factory and profile class, naming both (R-VND-03, R-VND-06,
     * R-VND-07, D-53).
     *
     * @param supplied at most one profile per vendor, as {@code ModelQueryConfig} holds them
     */
    public static ResolvedVendor withSupplied(EntityManagerFactory emf, ResolvedVendor resolved,
            List<VendorProfile> supplied) {
        Objects.requireNonNull(emf, "emf");
        ResolvedVendor chosen = withSupplied(resolved, supplied);
        if (chosen != resolved && LOGGED_SUPPLIED.computeIfAbsent(emf, factory -> ConcurrentHashMap.newKeySet())
                .add(chosen.profile().getClass())) {
            LOG.log(System.Logger.Level.INFO, "model-query uses the supplied profile {0} for vendor {1}, in place of "
                    + "the resolved profile {2} (R-VND-03, R-VND-07)", describe(chosen.profile()),
                    resolved.detectedVendor(), describe(resolved.profile()));
        }
        return chosen;
    }

    /**
     * {@link #withSupplied(EntityManagerFactory, ResolvedVendor, List)} without its log line.
     *
     * @param supplied at most one profile per vendor, as {@code ModelQueryConfig} holds them
     */
    static ResolvedVendor withSupplied(ResolvedVendor resolved, List<VendorProfile> supplied) {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(supplied, "supplied");
        DatabaseVendor vendor = resolved.detectedVendor();
        boolean fellBack = resolved.profile().vendor() != vendor;
        VendorProfile chosen = null;
        for (VendorProfile profile : supplied) {
            if (profile.vendor() == vendor) {
                chosen = profile;
                break;
            }
            if (fellBack && profile.vendor() == DatabaseVendor.OTHER) {
                chosen = profile;
            }
        }
        return chosen == null ? resolved : new ResolvedVendor(chosen, resolved.providerSupport().orElse(null), vendor,
                resolved.source(), resolved.detail());
    }

    private static ResolvedVendor resolved(Map<DatabaseVendor, VendorProfile> profiles, MysqlStreamingMode mode,
            ProviderSupport provider, DatabaseVendor vendor, ResolvedVendor.Source source, String detail) {
        return new ResolvedVendor(profileFor(vendor, mode, profiles), provider, vendor, source, detail);
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

    /**
     * Warns when nothing serves the factory's provider and its properties carry Hibernate's default null ordering:
     * a bare order then sorts its NULLs by a setting this library is not told of (R-VND-07, D-36).
     */
    private static void warnOfUnreportedNullOrdering(EntityManagerFactory emf, ResolvedVendor resolved) {
        if (resolved.providerSupport().isPresent()) {
            return;
        }
        Object ordering;
        try {
            ordering = emf.getProperties().get(HIBERNATE_DEFAULT_NULL_ORDERING);
        } catch (RuntimeException unreadable) {
            return;
        }
        if (ordering != null && !"none".equalsIgnoreCase(ordering.toString())) {
            LOG.log(System.Logger.Level.WARNING, "model-query does not honour {0}={1} without model-query-hibernate: "
                    + "keyset paging over a nullable column ordered without nullsFirst() or nullsLast() assumes the "
                    + "database default; add model-query-hibernate (R-PAG-05, D-36)",
                    HIBERNATE_DEFAULT_NULL_ORDERING, ordering);
        }
    }

    /** A built-in profile by its name, since a constant with a body is an anonymous class; any other by its class. */
    private static String describe(VendorProfile profile) {
        return profile instanceof BuiltInProfile builtIn ? "built-in " + builtIn.name() : profile.getClass().getName();
    }
}
