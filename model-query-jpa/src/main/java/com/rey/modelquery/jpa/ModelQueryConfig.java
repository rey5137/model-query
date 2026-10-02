package com.rey.modelquery.jpa;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PersistenceContextMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The settings of a {@code ModelQueryExecutor}, each the plain-JPA equivalent of a Spring property (R-SPR-08).
 * Immutable: each setter returns a new configuration. One explicit vendor applies to every
 * {@code EntityManagerFactory} the configuration is used with (D-34).
 *
 * @implSpec R-QRY-10, R-VND-04, R-PAG-07, R-EXE-11, R-PRF-07, R-PAG-05, R-QRY-15, R-VND-03, R-SPR-08, R-WRT-15,
 *     R-WRT-17, R-WRT-19
 */
@Incubating
public final class ModelQueryConfig {

    /** No batch size set: step 2 reads the whole page, within the profile's clamp (D-32). */
    private static final int WHOLE_PAGE = 0;

    private static final int DEFAULT_EXPORT_PAGE_SIZE = 1_000;

    private static final int DEFAULT_STREAM_FETCH_SIZE = 500;

    private static final int DEFAULT_BULK_WRITE_CHUNK_SIZE = 1_000;

    private static final ModelQueryConfig DEFAULTS = new ModelQueryConfig(null, WHOLE_PAGE, null,
            MysqlStreamingMode.ROW_BY_ROW, KeysetNullKeys.FAIL, DEFAULT_EXPORT_PAGE_SIZE, DEFAULT_STREAM_FETCH_SIZE,
            List.of(), PersistenceContextMode.CLEAR, DEFAULT_BULK_WRITE_CHUNK_SIZE, null);

    private final DatabaseVendor vendor;
    private final int primaryKeyFirstBatchSize;
    private final Duration queryTimeout;
    private final MysqlStreamingMode mysqlStreamingMode;
    private final KeysetNullKeys keysetNullKeys;
    private final int exportPageSize;
    private final int streamFetchSize;
    /** The supplied profiles, at most one per vendor, in the order given. */
    private final List<VendorProfile> vendorProfiles;
    /** What a bulk write does to the persistence context when the write sets no mode of its own (D-62). */
    private final PersistenceContextMode persistenceContextMode;
    private final int bulkWriteChunkSize;
    /** The callback running each chunk of a {@code commitEachChunk()} write, or {@code null} for none (R-WRT-19). */
    private final ChunkTransactions chunkTransactions;

    private ModelQueryConfig(DatabaseVendor vendor, int primaryKeyFirstBatchSize, Duration queryTimeout,
            MysqlStreamingMode mysqlStreamingMode, KeysetNullKeys keysetNullKeys, int exportPageSize,
            int streamFetchSize, List<VendorProfile> vendorProfiles, PersistenceContextMode persistenceContextMode,
            int bulkWriteChunkSize, ChunkTransactions chunkTransactions) {
        this.vendor = vendor;
        this.primaryKeyFirstBatchSize = primaryKeyFirstBatchSize;
        this.queryTimeout = queryTimeout;
        this.mysqlStreamingMode = mysqlStreamingMode;
        this.keysetNullKeys = keysetNullKeys;
        this.exportPageSize = exportPageSize;
        this.streamFetchSize = streamFetchSize;
        this.vendorProfiles = vendorProfiles;
        this.persistenceContextMode = persistenceContextMode;
        this.bulkWriteChunkSize = bulkWriteChunkSize;
        this.chunkTransactions = chunkTransactions;
    }

    /**
     * The configuration with every setting at its default: the vendor is detected, step 2 reads the whole page, no
     * query timeout, MySQL streams row by row, a NULL keyset key without explicit precedence fails, an export reads
     * pages of 1000 rows, a stream fetches 500 rows at a time, no profile is supplied, a bulk write clears the
     * persistence context, a chunked write selects 1000 keys per chunk, and no {@code ChunkTransactions} is set.
     */
    public static ModelQueryConfig defaults() {
        return DEFAULTS;
    }

    /** This configuration with the database vendor set explicitly, which skips detection entirely (R-VND-04). */
    public ModelQueryConfig vendor(DatabaseVendor vendor) {
        return new ModelQueryConfig(Objects.requireNonNull(vendor, "vendor"), primaryKeyFirstBatchSize, queryTimeout,
                mysqlStreamingMode, keysetNullKeys, exportPageSize, streamFetchSize, vendorProfiles,
                persistenceContextMode, bulkWriteChunkSize, chunkTransactions);
    }

    /**
     * This configuration with the vendor named by {@code name}, matched to a vendor's name ignoring case, {@code -} and
     * {@code _}, so {@code sql-server}, {@code SQL_SERVER} and {@code sqlserver} are the same vendor (D-57). For
     * settings read as text, such as a Spring property, which must not name the vendor type (INV-6).
     *
     * @throws ModelQueryConfigurationException {@code MQ4001} when {@code name} matches no vendor
     */
    public ModelQueryConfig vendor(String name) {
        String wanted = normalisedVendorName(Objects.requireNonNull(name, "name"));
        for (DatabaseVendor candidate : DatabaseVendor.values()) {
            if (normalisedVendorName(candidate.name()).equals(wanted)) {
                return vendor(candidate);
            }
        }
        throw new ModelQueryConfigurationException(MqCode.MQ4001,
                "vendor '" + name + "' is not one of " + List.of(DatabaseVendor.values()));
    }

    private static String normalisedVendorName(String name) {
        return name.replace("-", "").replace("_", "").toUpperCase(Locale.ROOT);
    }

    /** The explicitly configured vendor, or empty when it is detected per {@code EntityManagerFactory}. */
    public Optional<DatabaseVendor> vendor() {
        return Optional.ofNullable(vendor);
    }

    /**
     * This configuration with at most {@code batchSize} keys per primary-key-first step-2 statement. The profile's
     * IN-list and bind-parameter clamp still applies, so a statement takes the smaller of the two (R-PAG-07, D-32).
     *
     * @throws ModelQueryConfigurationException {@code MQ4003} when {@code batchSize} is below one
     */
    public ModelQueryConfig primaryKeyFirstBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new ModelQueryConfigurationException(MqCode.MQ4003,
                    "primaryKeyFirstBatchSize " + batchSize + " is below one");
        }
        return new ModelQueryConfig(vendor, batchSize, queryTimeout, mysqlStreamingMode, keysetNullKeys,
                exportPageSize, streamFetchSize, vendorProfiles,
                persistenceContextMode, bulkWriteChunkSize, chunkTransactions);
    }

    /** The configured step-2 batch size, or empty when step 2 reads the whole page within the profile's clamp. */
    public OptionalInt primaryKeyFirstBatchSize() {
        return primaryKeyFirstBatchSize == WHOLE_PAGE ? OptionalInt.empty() : OptionalInt.of(primaryKeyFirstBatchSize);
    }

    /**
     * This configuration with a timeout applied to every statement the executor runs, through
     * {@code VendorProfile.applyTimeout}. The JDBC granularity is one second, rounded up (R-EXE-11, R-PRF-05).
     *
     * @throws ModelQueryConfigurationException {@code MQ4003} when {@code timeout} is not positive
     */
    public ModelQueryConfig queryTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new ModelQueryConfigurationException(MqCode.MQ4003, "queryTimeout " + timeout + " is not positive");
        }
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, timeout, mysqlStreamingMode, keysetNullKeys,
                exportPageSize, streamFetchSize, vendorProfiles,
                persistenceContextMode, bulkWriteChunkSize, chunkTransactions);
    }

    /** The configured query timeout, or empty when statements run without one. */
    public Optional<Duration> queryTimeout() {
        return Optional.ofNullable(queryTimeout);
    }

    /**
     * This configuration with the MySQL streaming mode set. {@link MysqlStreamingMode#CURSOR_FETCH} only takes effect
     * when the JDBC URL carries {@code useCursorFetch=true}, which the library cannot set (R-PRF-07).
     */
    public ModelQueryConfig mysqlStreamingMode(MysqlStreamingMode mode) {
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout,
                Objects.requireNonNull(mode, "mode"), keysetNullKeys, exportPageSize, streamFetchSize, vendorProfiles,
                persistenceContextMode, bulkWriteChunkSize, chunkTransactions);
    }

    /** The configured MySQL streaming mode, {@link MysqlStreamingMode#ROW_BY_ROW} unless set. */
    public MysqlStreamingMode mysqlStreamingMode() {
        return mysqlStreamingMode;
    }

    /**
     * This configuration with what keyset paging does with a NULL in a column ordered with {@code DEFAULT} null
     * precedence ({@code modelquery.keyset.null-keys}, R-PAG-05).
     */
    public ModelQueryConfig keysetNullKeys(KeysetNullKeys nullKeys) {
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout, mysqlStreamingMode,
                Objects.requireNonNull(nullKeys, "nullKeys"), exportPageSize, streamFetchSize, vendorProfiles,
                persistenceContextMode, bulkWriteChunkSize, chunkTransactions);
    }

    /** The configured keyset NULL handling, {@link KeysetNullKeys#FAIL} unless set. */
    public KeysetNullKeys keysetNullKeys() {
        return keysetNullKeys;
    }

    /**
     * This configuration with the page size of an {@code export} whose {@code ExportOptions} leave it open, as
     * {@code ExportOptions.defaults()} does ({@code modelquery.export.page-size}, R-QRY-15).
     *
     * @throws ModelQueryConfigurationException {@code MQ4003} when {@code pageSize} is below one
     */
    public ModelQueryConfig exportPageSize(int pageSize) {
        if (pageSize < 1) {
            throw new ModelQueryConfigurationException(MqCode.MQ4003, "exportPageSize " + pageSize + " is below one");
        }
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout, mysqlStreamingMode, keysetNullKeys,
                pageSize, streamFetchSize, vendorProfiles,
                persistenceContextMode, bulkWriteChunkSize, chunkTransactions);
    }

    /** The page size of an export whose options leave it open, 1000 unless set. */
    public int exportPageSize() {
        return exportPageSize;
    }

    /**
     * This configuration with the fetch size {@code stream} hands the profile, which MySQL row by row ignores
     * ({@code modelquery.stream.fetch-size}, R-QRY-15, R-PRF-07).
     *
     * @throws ModelQueryConfigurationException {@code MQ4003} when {@code fetchSize} is below one
     */
    public ModelQueryConfig streamFetchSize(int fetchSize) {
        if (fetchSize < 1) {
            throw new ModelQueryConfigurationException(MqCode.MQ4003, "streamFetchSize " + fetchSize + " is below one");
        }
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout, mysqlStreamingMode, keysetNullKeys,
                exportPageSize, fetchSize, vendorProfiles, persistenceContextMode, bulkWriteChunkSize,
                chunkTransactions);
    }

    /** The fetch size of {@code stream}, 500 unless set. */
    public int streamFetchSize() {
        return streamFetchSize;
    }

    /**
     * This configuration with {@code profiles} in place of any supplied before. A supplied profile serves its vendor
     * ahead of a {@code ServiceLoader} one, which serves it ahead of the built-in one; it decides its own streaming,
     * whatever the MySQL streaming mode (R-VND-03, D-53).
     *
     * @throws ModelQueryConfigurationException {@code MQ4002} when two of {@code profiles} serve one vendor
     */
    public ModelQueryConfig vendorProfiles(Collection<? extends VendorProfile> profiles) {
        List<VendorProfile> supplied = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        Map<DatabaseVendor, VendorProfile> byVendor = new EnumMap<>(DatabaseVendor.class);
        for (VendorProfile profile : supplied) {
            VendorProfile other = byVendor.putIfAbsent(Objects.requireNonNull(profile.vendor(), "vendor"), profile);
            if (other != null) {
                throw new ModelQueryConfigurationException(MqCode.MQ4002, "VendorProfile for " + profile.vendor()
                        + ": both " + other.getClass().getName() + " and " + profile.getClass().getName()
                        + " are supplied on ModelQueryConfig.vendorProfiles(...); supply one");
            }
        }
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout, mysqlStreamingMode, keysetNullKeys,
                exportPageSize, streamFetchSize, supplied, persistenceContextMode, bulkWriteChunkSize,
                chunkTransactions);
    }

    /** The supplied profiles, at most one per vendor, in the order given; empty unless set. */
    public List<VendorProfile> vendorProfiles() {
        return vendorProfiles;
    }

    /**
     * This configuration with what a bulk write does to the persistence context after its last statement, unless the
     * write sets {@code persistenceContext(...)} itself ({@code modelquery.bulk-write.persistence-context}, R-WRT-15,
     * D-62). {@link PersistenceContextMode#CLEAR} detaches every managed entity, not only the written root's, so a
     * later change to any of them is silently not written; {@link PersistenceContextMode#KEEP} leaves the root's
     * entities managed but stale.
     */
    @Incubating
    public ModelQueryConfig persistenceContextMode(PersistenceContextMode mode) {
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout, mysqlStreamingMode, keysetNullKeys,
                exportPageSize, streamFetchSize, vendorProfiles, Objects.requireNonNull(mode, "mode"),
                bulkWriteChunkSize, chunkTransactions);
    }

    /** What a bulk write does to the persistence context, {@link PersistenceContextMode#CLEAR} unless set. */
    @Incubating
    public PersistenceContextMode persistenceContextMode() {
        return persistenceContextMode;
    }

    /**
     * This configuration with the keys per chunk of a write whose {@code ChunkOptions} leave the size open, as
     * {@code ChunkOptions.defaultSize()} does ({@code modelquery.bulk-write.chunk-size}, R-WRT-17, D-62). The
     * profile's IN-list and bind-parameter clamp still applies.
     *
     * @throws ModelQueryConfigurationException {@code MQ4003} when {@code chunkSize} is below one
     */
    @Incubating
    public ModelQueryConfig bulkWriteChunkSize(int chunkSize) {
        if (chunkSize < 1) {
            throw new ModelQueryConfigurationException(MqCode.MQ4003, "bulkWriteChunkSize " + chunkSize
                    + " is below one");
        }
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout, mysqlStreamingMode, keysetNullKeys,
                exportPageSize, streamFetchSize, vendorProfiles, persistenceContextMode, chunkSize, chunkTransactions);
    }

    /** The keys per chunk of a write whose options leave the size open, 1000 unless set. */
    @Incubating
    public int bulkWriteChunkSize() {
        return bulkWriteChunkSize;
    }

    /**
     * This configuration with the callback that runs each chunk of a {@code commitEachChunk()} write in a new
     * transaction (R-WRT-19, D-62). Without one, such a write throws {@code MQ4004} before any statement.
     */
    @Incubating
    public ModelQueryConfig chunkTransactions(ChunkTransactions transactions) {
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout, mysqlStreamingMode, keysetNullKeys,
                exportPageSize, streamFetchSize, vendorProfiles, persistenceContextMode, bulkWriteChunkSize,
                Objects.requireNonNull(transactions, "transactions"));
    }

    /** The callback running each chunk of a {@code commitEachChunk()} write; empty unless set. */
    @Incubating
    public Optional<ChunkTransactions> chunkTransactions() {
        return Optional.ofNullable(chunkTransactions);
    }
}
