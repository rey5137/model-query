package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.portability.Target;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Boots a factory per unit and target with no database: the unit's properties, beneath the persistence provider's
 * {@linkplain ProviderSupport#offlineProperties offline properties}, beneath the dry run's own overrides
 * ({@code RESOURCE_LOCAL}, the {@linkplain StubDataSource stub data source}, shared cache {@code NONE}, no schema
 * generation or scripts, the target's product name and version). A named unit boots through
 * {@code Persistence.createEntityManagerFactory}, a class list through the provider's container call. A boot that
 * reaches JDBC, as its {@linkplain JdbcRecording recording} shows, is {@code MQ4013}: a provider may swallow the
 * stub's failure. Factories are cached per unit, provider, target and class loader, booted once each through a
 * memoised task, and closed when the JVM exits.
 *
 * @implSpec R-INS-10, R-VND-20
 */
@Incubating
public final class OfflineFactory {

    static final String TRANSACTION_TYPE = "jakarta.persistence.transactionType";
    static final String NON_JTA_DATA_SOURCE = "jakarta.persistence.nonJtaDataSource";
    static final String SHARED_CACHE_MODE = "jakarta.persistence.sharedCache.mode";
    static final String DATABASE_ACTION = "jakarta.persistence.schema-generation.database.action";
    static final String SCRIPTS_ACTION = "jakarta.persistence.schema-generation.scripts.action";
    static final String PRODUCT_NAME = "jakarta.persistence.database-product-name";
    static final String PRODUCT_VERSION = "jakarta.persistence.database-product-version";
    static final String MAJOR_VERSION = "jakarta.persistence.database-major-version";
    static final String MINOR_VERSION = "jakarta.persistence.database-minor-version";
    static final String PROVIDER = "jakarta.persistence.provider";

    /** What a factory is booted from; equal keys share one factory (R-INS-10's factory cache). */
    private record Key(OfflineUnit unit, String providerSupport, Target target, ClassLoader loader) {
    }

    private static final Map<Key, FutureTask<EntityManagerFactory>> FACTORIES = new ConcurrentHashMap<>();

    /** How many unloadable {@code ProviderSupport} entries are skipped before the listing gives up. */
    private static final int MAX_SKIPPED = 16;

    /** Distinguishes the names of class-list units, which the provider may register by name. */
    private static final AtomicInteger UNITS = new AtomicInteger();

    /** Registers the shutdown hook on first use. */
    private static final class CloseOnExit {
        static {
            Runtime.getRuntime().addShutdownHook(new Thread(OfflineFactory::closeAll, "model-query-dry-run-close"));
        }

        static void register() {
            // Loading the class registers the hook once.
        }
    }

    private OfflineFactory() {
    }

    /**
     * The factory of {@code unit} on {@code target}, booted on first use with the {@code ProviderSupport} found with
     * {@code ServiceLoader} on the thread's context class loader.
     *
     * @throws ModelQueryConfigurationException {@code MQ4013} when no provider support has offline properties for the
     *     target's vendor, the one used does not serve the booted factory, or the boot reached JDBC
     */
    public static EntityManagerFactory boot(OfflineUnit unit, Target target) {
        return boot(unit, target, loadable(ServiceLoader.load(ProviderSupport.class, loader())));
    }

    /**
     * The factory of {@code unit} on {@code target}, booted on first use with the first of {@code supports} that has
     * offline properties for the target's vendor.
     *
     * @throws ModelQueryConfigurationException {@code MQ4013} as {@link #boot(OfflineUnit, Target)}
     */
    public static EntityManagerFactory boot(OfflineUnit unit, Target target, List<ProviderSupport> supports) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(supports, "supports");
        ProviderSupport support = null;
        Map<String, Object> offline = null;
        for (ProviderSupport candidate : supports) {
            Optional<Map<String, Object>> properties = candidate.offlineProperties(target.vendor());
            if (properties.isPresent()) {
                support = candidate;
                offline = properties.get();
                break;
            }
        }
        if (support == null) {
            throw new ModelQueryConfigurationException(MqCode.MQ4013, "No ProviderSupport has offline properties for "
                    + target.vendor() + " (found " + supports.stream().map(s -> s.getClass().getName()).toList()
                    + "), so " + unit + " cannot boot without a database on " + target);
        }
        ClassLoader loader = loader();
        Key key = new Key(unit, support.getClass().getName(), target, loader);
        FutureTask<EntityManagerFactory> task = FACTORIES.get(key);
        if (task == null) {
            ProviderSupport chosen = support;
            Map<String, Object> settings = offline;
            FutureTask<EntityManagerFactory> fresh = new FutureTask<>(
                    () -> bootChecked(unit, target, chosen, settings, loader));
            task = FACTORIES.putIfAbsent(key, fresh);
            if (task == null) {
                CloseOnExit.register();
                task = fresh;
                // Booted on this thread, outside the map's lock: the boot's recording is this thread's.
                fresh.run();
            }
        }
        return await(key, task);
    }

    private static EntityManagerFactory await(Key key, FutureTask<EntityManagerFactory> task) {
        try {
            return task.get();
        } catch (ExecutionException failed) {
            // A failed boot is not memoised, so a corrected classpath or unit boots again.
            FACTORIES.remove(key, task);
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            throw new ModelQueryConfigurationException(MqCode.MQ4013, "Booting " + key.unit() + " on "
                    + key.target() + " failed", failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelQueryConfigurationException(MqCode.MQ4013, "Interrupted waiting for " + key.unit()
                    + " to boot on " + key.target(), interrupted);
        }
    }

    private static EntityManagerFactory bootChecked(OfflineUnit unit, Target target, ProviderSupport support,
            Map<String, Object> offline, ClassLoader loader) {
        DataSource dataSource = StubDataSource.of(target);
        Map<String, Object> overrides = overrides(unit, target, offline, dataSource);
        EntityManagerFactory emf;
        try (JdbcRecording recording = JdbcRecording.boot()) {
            try {
                if (unit.name().isPresent()) {
                    emf = Persistence.createEntityManagerFactory(unit.name().get(), overrides);
                } else {
                    PersistenceProvider provider = provider(unit, target);
                    emf = provider.createContainerEntityManagerFactory(
                            unitInfo(unit, target, provider.getClass().getName(), dataSource, loader), overrides);
                }
            } catch (RuntimeException failed) {
                if (recording.accesses().isEmpty()) {
                    throw failed;
                }
                throw reachedJdbc(unit, target, support, recording, failed);
            }
            if (!recording.accesses().isEmpty()) {
                closeQuietly(emf);
                throw reachedJdbc(unit, target, support, recording, null);
            }
        }
        if (!support.supports(emf)) {
            closeQuietly(emf);
            throw new ModelQueryConfigurationException(MqCode.MQ4013, support.getClass().getName() + " supplied the "
                    + "offline properties of " + target + " but does not serve the factory " + unit + " booted");
        }
        return emf;
    }

    private static ModelQueryConfigurationException reachedJdbc(OfflineUnit unit, Target target,
            ProviderSupport support, JdbcRecording recording, RuntimeException cause) {
        String detail = "Booting " + unit + " on " + target + " with " + support.getClass().getName()
                + "'s offline properties reached JDBC: " + recording.accesses();
        return cause == null
                ? new ModelQueryConfigurationException(MqCode.MQ4013, detail)
                : new ModelQueryConfigurationException(MqCode.MQ4013, detail, cause);
    }

    /** The unit's properties, then the provider's offline properties, then the dry run's own: the last one wins. */
    static Map<String, Object> overrides(OfflineUnit unit, Target target, Map<String, Object> offline,
            DataSource dataSource) {
        Map<String, Object> overrides = new LinkedHashMap<>(unit.properties());
        overrides.putAll(offline);
        overrides.put(TRANSACTION_TYPE, PersistenceUnitTransactionType.RESOURCE_LOCAL.name());
        overrides.put(NON_JTA_DATA_SOURCE, dataSource);
        overrides.put(SHARED_CACHE_MODE, SharedCacheMode.NONE.name());
        overrides.put(DATABASE_ACTION, "none");
        overrides.put(SCRIPTS_ACTION, "none");
        overrides.put(PRODUCT_NAME, TargetFacts.productName(target));
        target.version().ifPresent(version -> {
            overrides.put(PRODUCT_VERSION, version);
            overrides.put(MAJOR_VERSION, String.valueOf(TargetFacts.majorVersion(target).orElseThrow()));
            overrides.put(MINOR_VERSION, String.valueOf(TargetFacts.minorVersion(target).orElseThrow()));
        });
        return overrides;
    }

    /** The unit's {@code jakarta.persistence.provider}, else the one provider on the class path. */
    private static PersistenceProvider provider(OfflineUnit unit, Target target) {
        List<PersistenceProvider> providers = PersistenceProviderResolverHolder.getPersistenceProviderResolver()
                .getPersistenceProviders();
        Object named = unit.properties().get(PROVIDER);
        List<PersistenceProvider> matching = providers.stream()
                .filter(p -> named == null || p.getClass().getName().equals(String.valueOf(named)))
                .toList();
        if (matching.size() != 1) {
            throw new ModelQueryConfigurationException(MqCode.MQ4013, "Booting " + unit + " on " + target
                    + " needs one persistence provider, found " + providers.stream().map(p -> p.getClass().getName())
                    .toList() + (named == null ? "; set " + PROVIDER : " for " + PROVIDER + "=" + named));
        }
        return matching.get(0);
    }

    /**
     * A container unit of a class list. A proxy, since the interface gains methods between Jakarta Persistence 3.1
     * and 3.2 and the dry run runs on both.
     */
    private static PersistenceUnitInfo unitInfo(OfflineUnit unit, Target target, String provider,
            DataSource dataSource, ClassLoader loader) {
        String name = "model-query-dry-run-" + UNITS.incrementAndGet() + " " + target;
        return (PersistenceUnitInfo) Proxy.newProxyInstance(OfflineFactory.class.getClassLoader(),
                new Class<?>[] {PersistenceUnitInfo.class}, (self, method, args) -> switch (method.getName()) {
                    case "getPersistenceUnitName" -> name;
                    case "getPersistenceProviderClassName" -> provider;
                    case "getTransactionType" -> PersistenceUnitTransactionType.RESOURCE_LOCAL;
                    case "getManagedClassNames" -> unit.classNames();
                    case "getMappingFileNames" -> unit.mappingFiles();
                    case "getJarFileUrls", "getQualifierAnnotationNames" -> List.of();
                    case "excludeUnlistedClasses" -> true;
                    case "getSharedCacheMode" -> SharedCacheMode.NONE;
                    case "getValidationMode" -> ValidationMode.NONE;
                    case "getProperties" -> new Properties();
                    case "getPersistenceXMLSchemaVersion" -> "3.0";
                    case "getClassLoader" -> loader;
                    case "getNonJtaDataSource" -> dataSource;
                    case "getJtaDataSource", "getPersistenceUnitRootUrl", "getScopeAnnotationName",
                            "getNewTempClassLoader", "addTransformer" -> null;
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> name;
                    default -> throw new ModelQueryConfigurationException(MqCode.MQ4013, "The dry run's unit "
                            + name + " does not answer PersistenceUnitInfo." + method.getName());
                });
    }

    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : OfflineFactory.class.getClassLoader();
    }

    /** The supports of {@code discovered} that load; one whose provider library is absent is skipped. */
    private static List<ProviderSupport> loadable(Iterable<ProviderSupport> discovered) {
        List<ProviderSupport> loaded = new ArrayList<>();
        Iterator<ProviderSupport> it = discovered.iterator();
        for (int skipped = 0; skipped < MAX_SKIPPED; ) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                loaded.add(it.next());
            } catch (ServiceConfigurationError | LinkageError notLoadable) {
                skipped++;
            }
        }
        return loaded;
    }

    private static void closeAll() {
        for (FutureTask<EntityManagerFactory> task : FACTORIES.values()) {
            if (task.isDone()) {
                try {
                    closeQuietly(task.get());
                } catch (ExecutionException | InterruptedException | RuntimeException notBooted) {
                    // Nothing to close.
                }
            }
        }
    }

    private static void closeQuietly(EntityManagerFactory emf) {
        try {
            emf.close();
        } catch (RuntimeException ignored) {
            // The stub denies any JDBC a close attempts; the factory is discarded either way.
        }
    }
}
