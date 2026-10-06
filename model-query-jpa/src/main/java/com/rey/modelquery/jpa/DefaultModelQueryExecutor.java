package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.JoinField;
import com.rey.modelquery.core.JoinPlan;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PersistenceContextMode;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.ScalarField;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.spi.ConflictClause;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.QueryTimeoutException;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The executor {@link ModelQueryExecutor#create} returns.
 *
 * @implSpec R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04, R-EXE-05, R-EXE-06, R-EXE-07, R-EXE-09, R-QRY-09, R-PAG-01,
 *     R-PAG-02, R-PAG-03, R-PAG-04, R-PAG-05, R-PAG-06, R-PAG-07, R-PAG-08, R-PAG-09, R-PAG-10, R-PAG-11, R-PAG-12,
 *     R-PAG-13, R-PAG-14, R-AGG-09, R-EXE-08, R-EXE-11, R-WRT-07, R-WRT-08, R-WRT-14, R-WRT-15, R-WRT-16, R-WRT-17,
 *     R-WRT-18, R-WRT-19, R-WRT-20, R-WRT-23, R-FCH-04, R-FCH-05, R-FCH-06, R-FCH-09, R-FCH-11, R-FCH-12, D-61,
 *     D-62, D-63, D-99, R-WRT-26, R-WRT-33, R-VND-14
 */
final class DefaultModelQueryExecutor<E> implements ModelQueryExecutor<E> {

    private static final System.Logger LOG = System.getLogger(DefaultModelQueryExecutor.class.getName());
    private static final System.Logger.Level DEBUG = System.Logger.Level.DEBUG;
    private static final System.Logger.Level TRACE = System.Logger.Level.TRACE;

    /** SQLState a provider reports for a statement its query timeout cancelled, PostgreSQL's {@code 57014}. */
    static final String CANCELLED_SQL_STATE = "57014";

    /**
     * The queries whose phases and fetch-plan columns were checked, by identity. Static, because the checks are once
     * per {@code ModelQuery} whichever executor runs it first (D-21); weak, so a query built per request does not stay
     * reachable.
     */
    private static final Set<ModelQuery<?, ?, ?>> FIRST_RUN_CHECKED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /**
     * The write definitions whose metamodel checks passed, per factory (D-61): a definition is checked once for each
     * factory it runs on, since another factory may map the root differently. Weak on both levels, so neither a
     * closed factory nor a definition built per request stays reachable.
     */
    private static final Map<EntityManagerFactory, Set<Object>> WRITES_CHECKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The factories {@code stream} warned of setting no fetch size on, so each is warned of once (R-VND-12). */
    private static final Set<EntityManagerFactory> FETCH_SIZE_UNSET =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private final EntityManager em;
    private final Class<E> rootEntity;
    /** What a child load's executor is created with (R-FCH-06). */
    private final ModelQueryConfig config;
    /** The profile and provider support of {@code em}'s factory, resolved once per factory (R-VND-02). */
    private final ResolvedVendor vendor;
    /** The profile's facts as every query build of this executor renders by (D-34). */
    private final RenderOptions renderOptions;
    /** The profile's IN-list and bind-parameter limits, as key-based reads and writes clamp to them (D-63). */
    private final Keys keyLimits;
    /** The configured most keys per step-2 statement, or empty for the whole page within the clamp (R-PAG-07). */
    private final OptionalInt primaryKeyFirstBatchSize;
    /** The configured timeout for every statement, or empty for none (R-EXE-11). */
    private final Optional<Duration> queryTimeout;
    /** What keyset paging does with a NULL key ordered with {@code DEFAULT} precedence (R-PAG-05). */
    private final KeysetNullKeys keysetNullKeys;
    /** The configured page size of an {@code export} whose options leave it open (R-QRY-15). */
    private final int exportPageSize;
    /** The configured fetch size {@code stream} hands the profile (R-QRY-15). */
    private final int streamFetchSize;
    /**
     * Where the provider is configured to sort the NULLs of a bare order in both directions, or empty for the
     * database's default (R-PAG-05, D-36).
     */
    private final Optional<NullPrecedence> providerNulls;
    /** What a bulk write does to the persistence context unless it sets its own mode (R-WRT-15, D-62). */
    private final PersistenceContextMode persistenceContextMode;
    /** The configured keys per chunk of a chunked write whose options leave the size open (R-WRT-17, D-62). */
    private final int bulkWriteChunkSize;
    /** The configured callback for {@code commitEachChunk()}, or {@code null} for none (R-WRT-19, D-62). */
    private final ChunkTransactions chunkTransactions;
    /**
     * Whether a conflict update's {@code where} may read two or more of its assigned columns where the vendor reads
     * the stored row (R-WRT-34, D-117).
     */
    private final boolean conflictUpdateWhereOnAssignedColumns;

    DefaultModelQueryExecutor(EntityManager em, Class<E> rootEntity, ModelQueryConfig config) {
        this.em = Objects.requireNonNull(em, "em");
        this.rootEntity = Objects.requireNonNull(rootEntity, "rootEntity");
        this.config = Objects.requireNonNull(config, "config");
        EntityManagerFactory emf = em.getEntityManagerFactory();
        this.vendor = VendorResolver.withSupplied(emf, VendorResolver.resolve(emf, config.vendor(),
                config.mysqlStreamingMode()), config.vendorProfiles());
        VendorProfile profile = vendor.profile();
        this.providerNulls = vendor.providerSupport()
                .flatMap(support -> support.defaultNullPrecedence(em.getEntityManagerFactory()));
        RenderOptions options = RenderOptions.of(profile.maxInListSize(), profile.maxBindParameters());
        this.renderOptions = vendor.providerSupport()
                .flatMap(ProviderSupport::nullPrecedence)
                .map(options::withNullPrecedenceRenderer)
                .orElse(options);
        this.keyLimits = new Keys(profile.maxInListSize(), profile.maxBindParameters());
        this.primaryKeyFirstBatchSize = config.primaryKeyFirstBatchSize();
        this.queryTimeout = config.queryTimeout();
        this.keysetNullKeys = config.keysetNullKeys();
        this.exportPageSize = config.exportPageSize();
        this.streamFetchSize = config.streamFetchSize();
        this.persistenceContextMode = config.persistenceContextMode();
        this.bulkWriteChunkSize = config.bulkWriteChunkSize();
        this.chunkTransactions = config.chunkTransactions().orElse(null);
        this.conflictUpdateWhereOnAssignedColumns = config.conflictUpdateWhereOnAssignedColumns();
    }

    @Override
    public <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkFirstRun(q);
        LOG.log(DEBUG, () -> "list " + q + ": " + limit);
        if (zeroLimit(limit)) {
            return List.of(); // no statement runs for a zero limit (R-EXE-06)
        }
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        return models(q, mapAll(q, limited(q, built, limit), built));
    }

    @Override
    public <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(body, "body");
        if (q.fetch().filter(plan -> !plan.isSelectionOnly()).isPresent()) {
            // A plan runs once per page, and a stream has none (R-FCH-09, D-96).
            throw new ModelQueryExecutionException(MqCode.MQ2605, q + ": stream(...) cannot run the fetch plan, whose "
                    + "children, join plans and enrichers run once per page, and a stream has no page; use "
                    + "export(...), which runs the plan on each page");
        }
        checkFirstRun(q);
        LOG.log(DEBUG, () -> "stream " + q + ": " + limit);
        if (zeroLimit(limit)) {
            try (Stream<M> none = Stream.empty()) {
                return body.apply(none); // no statement runs for a zero limit (R-EXE-06)
            }
        }
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        TypedQuery<Tuple> query = limited(q, built, limit);
        // Precondition first, so a refusal runs no statement. The profile decides the size from the configured one,
        // and the provider's support opens the stream with it (R-EXE-08, R-QRY-15, R-VND-12).
        vendor.profile().checkStreamingPreconditions(em);
        int fetchSize = vendor.profile().streamingFetchSize(streamFetchSize);
        // Rows are mapped one at a time as body pulls them; closing the mapped stream closes the result stream under
        // it, whether body returns, stops early or throws (R-EXE-07, R-EXE-09).
        try (Stream<Tuple> tuples = resultStream(query, fetchSize); Stream<M> models = tuples.map(built::map)) {
            return body.apply(models);
        }
    }

    /** {@code query}'s rows, streamed by the factory's provider support, or portably with a warning when none. */
    private <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
        Optional<ProviderSupport> support = vendor.providerSupport();
        if (support.isPresent()) {
            return support.get().resultStream(query, fetchSize);
        }
        warnOfUnsetFetchSize();
        return query.getResultStream();
    }

    /** Warns once per factory that, with no provider support to set the fetch size, the driver may buffer. */
    private void warnOfUnsetFetchSize() {
        EntityManagerFactory emf = em.getEntityManagerFactory();
        if (FETCH_SIZE_UNSET.add(emf)) {
            LOG.log(System.Logger.Level.WARNING, "stream sets no fetch size on {0}, because no ProviderSupport serves "
                    + "its persistence provider, so the JDBC driver may read the whole result into memory; add a "
                    + "ProviderSupport for its provider (model-query-hibernate for Hibernate), or use keyset export "
                    + "(R-VND-12, D-108)", emf);
        }
    }

    private static boolean zeroLimit(Limit limit) {
        return limit.maxRows().isPresent() && limit.maxRows().getAsInt() == 0;
    }

    /**
     * A statement of {@code query} for {@code label}, the model or entity a refusal names, within the bind limit and
     * with the configured timeout applied through the profile (R-FLT-09, R-EXE-11).
     */
    private <T> TypedQuery<T> create(Object label, CriteriaQuery<T> query) {
        return create(label, em, query, null, null, null);
    }

    /** A statement of {@code built} on {@code em}, counting the binds its repeated expression nodes add back. */
    private <M> TypedQuery<Tuple> create(Object label, BuiltQuery<M> built) {
        return create(label, em, built.query(), null, null, built.joins());
    }

    /** A statement of {@code query} on {@code on}, a chunk's own {@code EntityManager} or the caller's. */
    private <T> TypedQuery<T> create(Object label, EntityManager on, CriteriaQuery<T> query) {
        return create(label, on, query, null, null, null);
    }

    /**
     * As above, for a keyset statement, {@code cursor} being the one it was built after or {@code null} for the first
     * page or round, and {@code keyset} {@code null} for a statement without one (a run of distinct keys). It is
     * refused up front when its own binds plus the worst cursor {@code keyset} can add pass the limit, on every page,
     * so a run never fails after rows reached a sink or a round committed (D-82). {@code joins} adds back the binds a
     * repeated expression node contributes beyond the one JPA reports (R-COL-19), or {@code null} when unknown.
     */
    private <T> TypedQuery<T> create(Object label, EntityManager on, CriteriaQuery<T> query, Keyset<?> keyset,
            Object[] cursor, JoinContext joins) {
        TypedQuery<T> typed = on.createQuery(query);
        int binds = typed.getParameters().size() + (joins == null ? 0 : joins.repeatedExpressionBinds());
        int cursorBinds = keyset == null || cursor == null ? 0 : keyset.cursorBinds(cursor);
        if (keyset != null) {
            withinCursorBindLimit(label, binds - cursorBinds, keyset);
        }
        withinBindLimit(label, binds, cursorBinds);
        queryTimeout.ifPresent(timeout -> vendor.profile().applyTimeout(typed, timeout));
        LOG.log(TRACE, () -> label + ": statement binds " + binds + " of " + renderOptions.maxBindParameters()
                + (cursorBinds > 0 ? ", " + cursorBinds + " of them the keyset cursor's" : ""));
        return typed;
    }

    /** {@code query}'s rows, with their count and time in the trace log (D-95). */
    private <T> List<T> rows(Object label, TypedQuery<T> query) {
        long start = System.nanoTime();
        List<T> rows;
        try {
            rows = query.getResultList();
        } catch (PersistenceException e) {
            throw timeoutCancellation(e);
        }
        traceTimed(label, rows.size() + (rows.size() == 1 ? " row" : " rows"), start);
        return rows;
    }

    /** {@code query}'s one row, with its time in the trace log (D-95). */
    private <T> T single(Object label, TypedQuery<T> query) {
        long start = System.nanoTime();
        T row;
        try {
            row = query.getSingleResult();
        } catch (PersistenceException e) {
            throw timeoutCancellation(e);
        }
        traceTimed(label, "1 row", start);
        return row;
    }

    /** {@link #timeoutCancellation(PersistenceException, boolean)} for this executor's configured timeout. */
    private PersistenceException timeoutCancellation(PersistenceException e) {
        return timeoutCancellation(e, queryTimeout.isPresent());
    }

    /**
     * {@code e} as JPA's {@link QueryTimeoutException} when {@code timeoutConfigured} and its cause chain holds a
     * {@link SQLException} with SQLState {@code 57014} (a statement the query timeout cancelled), else {@code e}
     * unchanged. Hibernate 6.6 already reports the cancellation as a {@code QueryTimeoutException}; Hibernate 7 on
     * PostgreSQL reports it as a plain {@link PersistenceException}, so the executor translates it for callers on
     * every supported version (R-EXE-11).
     */
    static PersistenceException timeoutCancellation(PersistenceException e, boolean timeoutConfigured) {
        if (!timeoutConfigured || e instanceof QueryTimeoutException || !cancelledByTimeout(e)) {
            return e;
        }
        return new QueryTimeoutException(e.getMessage(), e);
    }

    /** Whether {@code e}'s cause chain holds a {@link SQLException} with SQLState {@code 57014}. */
    private static boolean cancelledByTimeout(Throwable e) {
        // At most 32 causes deep, so a cyclic cause chain cannot loop forever.
        Throwable cause = e;
        for (int depth = 0; cause != null && depth < 32; cause = cause.getCause(), depth++) {
            if (cause instanceof SQLException sql && CANCELLED_SQL_STATE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void traceTimed(Object label, String what, long start) {
        long millis = (System.nanoTime() - start) / 1_000_000;
        LOG.log(TRACE, () -> label + ": " + what + " in " + millis + " ms");
    }

    /** The debug line of a write, with how it runs (R-WRT-08, R-WRT-11, D-95). */
    private void logWrite(String operation, Object write, Optional<ChunkOptions> chunk, boolean keyFirst) {
        // The write's toString names its rows and where conditions, never a key or a value (D-95, R-INS-05).
        LOG.log(DEBUG, () -> operation + " " + write + ": "
                + (chunk.isPresent() ? "chunked" : keyFirst ? "key-first" : "direct"));
    }

    /**
     * {@code statement}, unless the query parameters JPA reports for it pass the profile's bind limit: a query's own
     * statement is never split across statements, so it throws {@code MQ1307} before it runs rather than failing in
     * the database (R-FLT-09, D-80). Library-built key lists are clamped below the limit (D-32, D-63), so only the
     * query's own binds can pass it.
     */
    private <Q extends Query> Q withinBindLimit(Object label, Q statement) {
        withinBindLimit(label, statement.getParameters().size(), 0);
        return statement;
    }

    /**
     * As above, for a statement of {@code binds} values, {@code cursorBinds} of them the keyset cursor's, which the
     * refusal counts (D-82).
     */
    private void withinBindLimit(Object label, int binds, int cursorBinds) {
        int max = renderOptions.maxBindParameters();
        if (binds > max) {
            String why = cursorBinds > 0
                    ? "; " + cursorBinds + " of them are the keyset cursor's values, so narrow the query's own "
                            + "filters by at least " + (binds - max) + " or use fewer keyset columns"
                    : "; narrow its filters, since a query's own statement is never split across statements";
            throw new ModelQueryDefinitionException(MqCode.MQ1307, label + ": a statement binds " + binds
                    + " values, more than the " + max + " bind parameters one statement takes" + why);
        }
    }

    /** Throws {@code MQ1307} when a statement's {@code own} binds and the worst keyset cursor pass the limit (D-82). */
    private void withinCursorBindLimit(Object label, int own, Keyset<?> keyset) {
        int worst = keyset.maxCursorBinds();
        int max = renderOptions.maxBindParameters();
        if (own + worst > max) {
            throw new ModelQueryDefinitionException(MqCode.MQ1307, label + ": a keyset statement binds " + own
                    + " values of its own, and the keyset cursor's values can add up to " + worst + " more, over the "
                    + max + " bind parameters one statement takes; narrow the query's own filters by at least "
                    + (own + worst - max) + " or use fewer keyset columns");
        }
    }

    /** {@code built}'s statement for {@code q}, capped at {@code limit}'s rows if it has any. */
    private <M> TypedQuery<Tuple> limited(ModelQuery<E, ?, M> q, BuiltQuery<M> built, Limit limit) {
        TypedQuery<Tuple> query = create(q, built);
        limit.maxRows().ifPresent(query::setMaxResults);
        return query;
    }

    @Override
    public <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(mode, "mode");
        checkFirstRun(q);
        int offset = page.offset();
        int size = page.pageSize();
        LOG.log(DEBUG, () -> "page " + q + ": offset " + offset + " size " + size + ", " + mode
                + (mode != CountMode.ONLY_COUNT && primaryKeyFirst(q, offset) ? ", primary-key-first" : ""));
        if (mode != CountMode.ONLY_COUNT && primaryKeyFirst(q, offset)) {
            // Before the count too, so a refused page runs no query at all (R-PAG-13).
            refuseToManySelection(q, q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL_BY_KEYS, renderOptions),
                    "primary-key-first paging");
        }
        switch (mode) {
            case ONLY_COUNT: {
                long total = countRows(q);
                return new Slice<>(List.of(), page.pageNumber(), size, offset + (long) size < total,
                        OptionalLong.of(total));
            }
            case COUNT: {
                long total = countRows(q);
                List<M> content = offset >= total ? List.of() : models(q, fetch(q, offset, size));
                return new Slice<>(content, page.pageNumber(), size, offset + (long) content.size() < total,
                        OptionalLong.of(total));
            }
            default: {
                // One row beyond the page tells whether another follows, so an exact multiple of the page size
                // does not report a next page that is empty (R-EXE-02).
                List<Loaded<M>> rows = fetch(q, offset, size == Integer.MAX_VALUE ? size : size + 1);
                boolean hasNext = rows.size() > size;
                // The plan runs after the probe row is dropped, so that row's children are never read (R-FCH-09).
                return new Slice<>(models(q, hasNext ? rows.subList(0, size) : rows), page.pageNumber(), size,
                        hasNext, OptionalLong.empty());
            }
        }
    }

    @Override
    public <M> KeysetSlice<M> page(ModelQuery<E, ?, M> q, KeysetSpec keyset) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(keyset, "keyset");
        if (!q.isKeyset()) {
            // R-PAG-16: a keyset page needs a keyset() query, before any query runs (MQ2207).
            throw new ModelQueryExecutionException(MqCode.MQ2207, q + ": page(query, KeysetSpec) needs a keyset() "
                    + "query; add keyset() or page by PageSpec");
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL, renderOptions);
        // Before any query, in R-PAG-16/R-PAG-23's order: the to-many selection, then the first-run phase check
        // (R-PAG-15) and the fetch plan's own check, so MQ2207 and MQ2204 precede MQ2206 as the design orders them.
        refuseToManySelection(q, built, "keyset paging");
        checkFirstRun(q);
        PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(() -> new ModelQueryExecutionException(MqCode.MQ2203,
                q + ": a keyset page needs a primary key to order and identify its rows"));
        Keyset<M> forward = Keyset.of(q, key, vendor.profile().defaultAscendingNullOrdering(), providerNulls,
                keysetNullKeys);
        forward.unsupportedColumn().ifPresent(column -> {
            // A key type no codec carries is refused before any query runs (R-PAG-17, MQ2210).
            throw new ModelQueryExecutionException(MqCode.MQ2210, q + ": keyset column " + column.path() + " of type "
                    + column.attributeType().getName() + " cannot be carried in a cursor");
        });
        if (keyset.hasCursor() && !Arrays.equals(forward.fingerprint(), keyset.fingerprint())) {
            // Another sort, direction, precedence, entity or deployment: refused before any query runs (R-PAG-19).
            throw new ModelQueryExecutionException(MqCode.MQ2209, q + ": the keyset cursor belongs to another order "
                    + "(sort, direction, null precedence, entity or deployment changed); start again with "
                    + "KeysetSpec.first");
        }
        Object[] cursor = keyset.hasCursor() ? forward.resolve(keyset.values(), q) : null;
        Object cursorKey = cursor == null ? null : forward.primaryKeyOf(cursor);
        boolean before = keyset.direction() == KeysetSpec.Direction.BEFORE;
        // before reads the same rows from the other side, in flipped order, and reverses them below (R-PAG-20).
        Keyset<M> readKeyset = before ? forward.reversed() : forward;
        Keyset.Beyond after = null;
        if (cursor != null) {
            // The cursor predicate joins the query's own restriction.
            after = readKeyset.after(cursor, built.joins(), cb);
            Predicate own = built.query().getRestriction();
            built.query().where(own == null ? after.predicate() : cb.and(own, after.predicate()));
        }
        readKeyset.applyOrder(built, cb);
        int size = keyset.size();
        // One row beyond the page tells whether another follows (R-PAG-21); size + 1 fits an int (KeysetSpec).
        TypedQuery<Tuple> query = create(q, em, built.query(), readKeyset, cursor, built.joins());
        if (after != null) {
            after.bindTo(query);
        }
        query.setMaxResults(size + 1);
        List<Tuple> rows = rows(q, query);
        List<Row> decoded = new ArrayList<>(rows.size());
        for (Tuple tuple : rows) {
            decoded.add(built.selection().row(tuple));
        }
        // The null rule is read on every row, the look-ahead included (R-PAG-05, R-PAG-22).
        for (Row row : decoded) {
            forward.cursor(row);
        }
        boolean lookAhead = decoded.size() > size;
        List<Row> kept = lookAhead ? decoded.subList(0, size) : decoded;
        if (before) {
            // Drop the furthest look-ahead row, then reverse the rest into the query's order (R-PAG-20).
            kept = new ArrayList<>(kept);
            Collections.reverse(kept);
        }
        Set<Object> seen = new HashSet<>();
        List<Loaded<M>> fresh = new ArrayList<>(kept.size());
        for (Row row : kept) {
            Object rowKey = Keys.keyOf(q, key, row);
            if (cursorKey != null && cursorKey.equals(rowKey)) {
                // The cursor did not survive being bound, or the boundary row moved (R-PAG-24, D-110).
                throw new ModelQueryExecutionException(MqCode.MQ2205, q + ": a keyset page holds the cursor's own "
                        + "primary key: a cursor value did not survive being bound, or the boundary row moved");
            }
            // A predicate's to-many join repeats its root within the page, and that repeat is dropped (R-PAG-02).
            if (seen.add(rowKey)) {
                fresh.add(new Loaded<>(built.map(row), row));
            }
        }
        List<M> content = models(q, fresh);
        // The flags need no second statement (R-PAG-21); an empty page has neither cursor (R-PAG-21).
        Optional<String> previous = Optional.empty();
        Optional<String> next = Optional.empty();
        if (!content.isEmpty()) {
            // A flag's cursor is the boundary row's, encoded once: the previous row of a `before` page and of an
            // `after` page, and the next row when a look-ahead row was read (R-PAG-20, R-PAG-21).
            boolean hasPrevious = before ? lookAhead : cursor != null;
            boolean hasNext = before || lookAhead;
            String firstCursor = hasPrevious ? forward.encode(q, fresh.get(0).row()) : null;
            String lastCursor = hasNext ? forward.encode(q, fresh.get(fresh.size() - 1).row()) : null;
            if (before) {
                previous = hasPrevious ? Optional.of(firstCursor) : Optional.empty();
                next = Optional.of(lastCursor);
            } else if (cursor != null) {
                previous = Optional.of(firstCursor);
                next = hasNext ? Optional.of(lastCursor) : Optional.empty();
            } else {
                next = hasNext ? Optional.of(lastCursor) : Optional.empty();
            }
        }
        return KeysetSlice.of(content, size, previous, next);
    }

    @Override
    public long count(ModelQuery<E, ?, ?> q) {
        Objects.requireNonNull(q, "q");
        checkFirstRun(q, false);
        LOG.log(DEBUG, () -> "count " + q);
        return countRows(q);
    }

    @Override
    public <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(pageTransformer, "pageTransformer");
        Objects.requireNonNull(sink, "sink");
        checkFirstRun(q);
        long limit = options.limit().maxRows().isPresent() ? options.limit().maxRows().getAsInt() : Long.MAX_VALUE;
        int pageSize = options.pageSize().orElse(exportPageSize);
        LOG.log(DEBUG, () -> "export " + q + ": " + (q.isGrouped() ? "grouped offset" : q.isKeyset() ? "keyset"
                : "offset") + ", pageSize " + pageSize + ", " + options.limit());
        if (q.isGrouped()) {
            // A group has no row identity, so its group keys order and dedupe the pages in place of a primary key,
            // which a grouped query never has; keyset() and primaryKeyFirst(...) were refused at build (MQ1402), so
            // the export is offset-only (R-PAG-11, R-PAG-12, R-AGG-09). A group-key tuple is unique per result row
            // even through a to-many join, so a grouped export skips the MQ2204 refusal (R-PAG-13).
            BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
            appendStableOrder(q, built);
            return exportByOffset(q, built, null, row -> groupKeyOf(q, row), pageSize, limit, pageTransformer,
                    sink);
        }
        // A keyset query always has a primary key (MQ1201 at build time), so only offset export can fail here.
        PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(() -> new ModelQueryExecutionException(MqCode.MQ2203,
                q + ": offset export needs a primary key to order and dedupe its pages, and primaryKey(...) was "
                        + "not set"));
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        refuseToManySelection(q, built, q.isKeyset() ? "keyset paging" : "offset export");
        if (q.isKeyset()) {
            return exportByKeyset(q, built, key, pageSize, limit, pageTransformer, sink);
        }
        appendStableOrder(q, built);
        return exportByOffset(q, built, key, row -> Keys.keyOf(q, key, row), pageSize, limit, pageTransformer,
                sink);
    }

    @Override
    public long update(ModelUpdate<E, ?> u) {
        Objects.requireNonNull(u, "u");
        checkWriteOnce(u, u::checkMetamodel);
        requireTransaction("update", u.chunkOptions());
        if (u.writesNothing()) {
            return 0;
        }
        long written = updateRows(u);
        if (written == 0 && u.expectedVersion().isPresent()) {
            throw new OptimisticLockException(rootEntity.getSimpleName() + ": no row was written with version "
                    + u.expectedVersion().get() + "; its version moved, or it no longer exists or matches");
        }
        return written;
    }

    private <M> long updateRows(ModelUpdate<E, M> u) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        BiFunction<Class<?>, Object, ?> references = em::getReference;
        int repeated = u.repeatedExpressionBinds(cb, renderOptions, references);
        Supplier<Query> whole = () -> em.createQuery(u.buildWrite(cb, renderOptions, references));
        Function<List<Object>, Query> byKeys = chunk -> em.createQuery(u.buildWrite(cb, renderOptions, references,
                chunk));
        boolean rootTermsOnly = keyFirst(() -> u.entitiesReadInSubquery(cb, renderOptions));
        logWrite("update", u, u.chunkOptions(), rootTermsOnly);
        if (!rootTermsOnly && u.chunkOptions().isEmpty()) {
            return write(u.persistenceContext(), () -> direct(u.distinctKeys(), whole, byKeys, repeated));
        }
        var keyed = new KeysetWrite.Keyed<>(rootEntity.getSimpleName(), u.primaryKey(), u.distinctKeys(),
                run -> run == null ? u.buildKeySelect(cb, renderOptions) : u.buildKeySelect(cb, renderOptions, run),
                (on, keys) -> on.createQuery(u.buildWrite(cb, renderOptions, on::getReference, keys, rootTermsOnly)),
                u.startAfter(), u::modelKey);
        return write(u.persistenceContext(), () -> keyset(keyed, whole, byKeys, u.chunkOptions(), cb, repeated));
    }

    @Override
    public long delete(ModelDelete<E, ?> d) {
        Objects.requireNonNull(d, "d");
        checkWriteOnce(d, d::checkMetamodel);
        requireTransaction("delete", d.chunkOptions());
        if (d.writesNothing()) {
            return 0;
        }
        return deleteRows(d);
    }

    private <M> long deleteRows(ModelDelete<E, M> d) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        int repeated = d.repeatedExpressionBinds(cb, renderOptions);
        Supplier<Query> whole = () -> em.createQuery(d.buildWrite(cb, renderOptions));
        Function<List<Object>, Query> byKeys = chunk -> em.createQuery(d.buildWrite(cb, renderOptions, chunk));
        boolean rootTermsOnly = keyFirst(() -> d.entitiesReadInSubquery(cb, renderOptions));
        logWrite("delete", d, d.chunkOptions(), rootTermsOnly);
        if (!rootTermsOnly && d.chunkOptions().isEmpty()) {
            return write(d.persistenceContext(), () -> direct(d.distinctKeys(), whole, byKeys, repeated));
        }
        var keyed = new KeysetWrite.Keyed<>(rootEntity.getSimpleName(), d.primaryKey(), d.distinctKeys(),
                run -> run == null ? d.buildKeySelect(cb, renderOptions) : d.buildKeySelect(cb, renderOptions, run),
                (on, keys) -> on.createQuery(d.buildWrite(cb, renderOptions, keys, rootTermsOnly)),
                d.startAfter(), d::modelKey);
        return write(d.persistenceContext(), () -> keyset(keyed, whole, byKeys, d.chunkOptions(), cb, repeated));
    }

    @Override
    public long insert(ModelInsert<E, ?> i) {
        Objects.requireNonNull(i, "i");
        InsertSupport support = insertSupport(i);
        checkInsertOnce(i, support);
        requireTransaction("insert", i.chunkOptions());
        if (i.sourceEntity().isPresent()) {
            return insertSelect(i, support);
        }
        return insertValues(i, support, null);
    }

    /**
     * Runs an insert-select: its source select built as the read path builds it, then written as one statement, or
     * with {@code chunked} key-first over the distinct source-root ids, so each source row is read once; the
     * persistence context is flushed before and cleared after, as for any bulk write (R-WRT-27, R-WRT-28, R-WRT-38).
     */
    private long insertSelect(ModelInsert<E, ?> i, InsertSupport support) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        List<String> attributes = i.selectedAttributes(em.getMetamodel());
        Map<String, Object> constants = i.selectParameters();
        BuiltQuery<?> select = i.buildSelect(cb, renderOptions);
        int repeated = select.joins().repeatedExpressionBinds();
        BiFunction<EntityManager, CriteriaQuery<Tuple>, Query> statement = (on, source) ->
                support.insertSelect(on, rootEntity, attributes, source, constants);
        Supplier<Query> whole = () -> statement.apply(em, select.query());
        logWrite("insert", i, i.chunkOptions(), false);
        if (i.chunkOptions().isEmpty()) {
            return write(i.persistenceContext(), () -> execute(whole.get(), repeated));
        }
        PrimaryKey<Object, ?> key = i.sourceKey(em.getMetamodel());
        BiFunction<EntityManager, List<Object>, Query> byKeys = (on, keys) -> statement.apply(on,
                i.buildSelect(cb, renderOptions, key, keys).query());
        // The source keys are attribute values of plain id columns, which is what the exception reports (R-WRT-32).
        var keyed = new KeysetWrite.Keyed<>(rootEntity.getSimpleName(), key, Optional.empty(),
                run -> i.buildKeySelect(cb, renderOptions, key), byKeys, Optional.empty(), sourceKey -> sourceKey);
        return write(i.persistenceContext(), () -> keyset(keyed, whole, keys -> byKeys.apply(em, keys),
                i.chunkOptions(), cb, repeated));
    }

    @Override
    public <K> List<K> insertReturningKeys(ValuesInsert<E, K, ?> i) {
        Objects.requireNonNull(i, "i");
        i.checkKeysReturnable();
        InsertSupport support = insertSupport(i);
        checkInsertOnce(i, support);
        InsertChecks.checkKeysGenerated(i, support.target(em.getEntityManagerFactory(), rootEntity).id());
        requireTransaction("insert", i.chunkOptions());
        var keys = new ArrayList<Object>(i.rowCount());
        insertValues(i, support, keys);
        return keys.stream().map(i.keyType()::cast).toList();
    }

    /**
     * Runs an insert-values: its rows in list order, in statements of the most rows that stay within the bind limit
     * and the profile's {@code VALUES} limit, capped by the chunk size; each in the caller's transaction, or with
     * {@code commitEachChunk()} in its own. A sequence, table or UUID id is drawn from the generator before each
     * statement, written as a value and added to {@code keys} when given. A conflict clause goes in every statement.
     * An empty list runs no SQL, not even the flush; otherwise the persistence context is flushed before and cleared
     * after, as for any bulk write (R-WRT-26, R-WRT-29, R-WRT-32, R-WRT-33, R-WRT-34, R-WRT-38).
     */
    private long insertValues(ModelInsert<E, ?> i, InsertSupport support, List<Object> keys) {
        int rows = i.rowCount();
        if (rows == 0) {
            return 0;
        }
        EntityManagerFactory emf = em.getEntityManagerFactory();
        List<String> attributes = new ArrayList<>(i.valueAttributes(em.getMetamodel()));
        boolean drawn = InsertChecks.drawsKeys(support.target(emf, rootEntity).id());
        if (drawn) {
            attributes.add(idAttribute());
        }
        int providerBinds = support.providerBindsPerRow(emf, rootEntity);
        int perStatement = rowsPerStatement(i, attributes.size() + providerBinds);
        Optional<ConflictClause<E>> conflict = i.conflictKeys().isEmpty() ? Optional.empty()
                : Optional.of(new InsertConflict<>(i, renderOptions));
        ValuesWrite.Statement statement = (on, from, to) -> {
            List<List<Object>> values = i.valueRows(from, to);
            if (drawn) {
                List<Object> drawnKeys = support.generateKeys(on, rootEntity, to - from);
                for (int r = 0; r < values.size(); r++) {
                    values.get(r).add(drawnKeys.get(r));
                }
                if (keys != null) {
                    keys.addAll(drawnKeys);
                }
            }
            return execute(support.insertValues(on, rootEntity, attributes, values, conflict),
                    providerBinds * (to - from) + i.conflictRepeatedBinds());
        };
        boolean perChunk = i.chunkOptions().map(ChunkOptions::commitsEachChunk).orElse(false);
        logWrite("insert", i, i.chunkOptions(), false);
        return write(i.persistenceContext(), () -> new ValuesWrite(rootEntity.getSimpleName(), em,
                perChunk ? chunkTransactions : null).run(rows, perStatement, statement));
    }

    /**
     * The most rows one insert-values statement takes: as many as stay within the bind limit at {@code bindsPerRow}
     * each, the columns, the {@code set} constants, a drawn id and the provider's own binds such as the version seed,
     * once the conflict clause's binds are bound, and within the profile's {@code maxValuesRows()}, capped by the
     * chunk size, the given or else the configured one, when chunked; at least one, which {@code execute} refuses
     * with {@code MQ1307} should it alone pass the bind limit (R-WRT-29, D-80).
     */
    private int rowsPerStatement(ModelInsert<E, ?> i, int bindsPerRow) {
        int free = Math.max(0, renderOptions.maxBindParameters() - i.conflictBinds());
        int rows = Math.min(free / Math.max(1, bindsPerRow), vendor.profile().maxValuesRows());
        if (i.chunkOptions().isPresent()) {
            rows = Math.min(rows, i.chunkOptions().get().size().orElse(bulkWriteChunkSize));
        }
        return Math.max(1, rows);
    }

    /** The root's one id attribute, which a drawn key is written to: a generated id is never composite (R-WRT-26). */
    private String idAttribute() {
        List<String> ids = em.getMetamodel().entity(rootEntity).getSingularAttributes().stream()
                .filter(SingularAttribute::isId).map(Attribute::getName).toList();
        if (ids.size() != 1) {
            throw new IllegalStateException(rootEntity.getSimpleName() + ": a drawn key needs one id attribute, "
                    + "and the metamodel reports " + ids);
        }
        return ids.get(0);
    }

    @Override
    public <K> K persist(ModelPersist<E, K, ?> p) {
        Objects.requireNonNull(p, "p");
        checkWriteOnce(p, p::checkMetamodel);
        throw new UnsupportedOperationException(p + ": persist(...) is built in M10.8");
    }

    /**
     * The {@link InsertSupport} serving this executor's factory, else {@code MQ4009}, before any statement and before
     * the flush (R-VND-14).
     */
    private InsertSupport insertSupport(ModelInsert<E, ?> i) {
        return vendor.providerSupport().flatMap(ProviderSupport::inserts).orElseThrow(() ->
                new ModelQueryConfigurationException(MqCode.MQ4009, i + ": a bulk insert needs the persistence "
                        + "provider's InsertSupport, and no ProviderSupport on the class path supplies one for this "
                        + "EntityManagerFactory; add model-query-hibernate for Hibernate, or use persist(...)"));
    }

    /**
     * Checks {@code i} the first time it runs on this executor's factory, before any statement: against the metamodel,
     * then against the generator and the mappings {@code support} reports for the root (D-61, R-WRT-26); and on each
     * run, a conflict clause's target and its update's {@code where} against this executor's configuration and
     * profile (R-WRT-34, R-WRT-36).
     */
    private void checkInsertOnce(ModelInsert<E, ?> i, InsertSupport support) {
        checkWriteOnce(i, metamodel -> {
            EntityManagerFactory emf = em.getEntityManagerFactory();
            i.checkMetamodel(metamodel);
            InsertChecks.checkTarget(i, support.target(emf, rootEntity), i.sourceEntity().isPresent(),
                    i.writesId(metamodel));
            if (!i.conflictKeys().isEmpty()) {
                InsertChecks.checkConflict(i, i.conflictKeys(), support.uniqueKeys(emf, rootEntity),
                        !i.conflictSkips() || support.doNothingRendered(emf));
            }
            if (i.chunkOptions().isPresent() && i.sourceEntity().isPresent()) {
                checkNoOverlap(i, selected(i, metamodel), metamodel);
            }
        });
        // Each run, not once per factory: the option and the profile are this executor's, and another executor on
        // the same factory may run i too
        if (!i.conflictKeys().isEmpty()) {
            InsertChecks.checkConflictTarget(i, i.conflictKeys(),
                    vendor.profile().conflictTargetHonoured() || i.conflictAnyUniqueKey());
        }
        InsertChecks.checkConflictWhere(i, i.conflictWhereReadsAssigned(em.getMetamodel()),
                conflictUpdateWhereOnAssignedColumns, vendor.profile().conflictWhereSeesEarlierAssignments());
    }

    /**
     * The entity types {@code i}'s source select reads through its FROM: the source root, then each entity a
     * {@code map} or {@code where} column joins, at any depth, in join order. A row a chunk writes into any of them
     * could change a later chunk's rows, a to-many join's repeats or its {@code where} (R-WRT-28).
     */
    private Set<Class<?>> selected(ModelInsert<E, ?> i, Metamodel metamodel) {
        Set<Class<?>> entities = metamodel.getEntities().stream().map(EntityType::getJavaType)
                .collect(Collectors.toSet());
        var selected = new LinkedHashSet<Class<?>>();
        var froms = new ArrayDeque<From<?, ?>>(i.buildSelect(em.getCriteriaBuilder(), renderOptions).query()
                .getRoots());
        while (!froms.isEmpty()) {
            From<?, ?> from = froms.poll();
            Class<?> type = from instanceof Join<?, ?> join ? joinedType(join.getAttribute()) : from.getJavaType();
            if (entities.contains(type)) {
                selected.add(type);
            }
            froms.addAll(from.getJoins());
        }
        return selected;
    }

    /** The type a join over {@code attribute} reaches: a collection's element, else the attribute's own type. */
    private static Class<?> joinedType(Attribute<?, ?> attribute) {
        return attribute instanceof PluralAttribute<?, ?, ?> plural ? plural.getElementType().getJavaType()
                : ((SingularAttribute<?, ?>) attribute).getType().getJavaType();
    }

    /**
     * Checks that a chunked insert-select's target overlaps none of the {@code selected} entities its source select
     * reads, the source root and every entity it joins: rows a chunk writes could match the next chunk's key select,
     * or change the rows its joins return, and the engine only gets a count back. An entity overlaps the target when
     * they are one entity or one hierarchy, or when the tables the provider names for them intersect, ignoring case;
     * an entity or target whose tables the provider does not name fails closed, since rows written with generated ids
     * above the cursor would otherwise be re-read silently (R-WRT-28, R-VND-13, INV-5).
     *
     * @throws ModelQueryDefinitionException {@code MQ1806} when one overlaps or a table set is unknown
     */
    private void checkNoOverlap(ModelInsert<E, ?> i, Set<Class<?>> selected, Metamodel metamodel) {
        String chunked = i + ": a chunked insert-select";
        String unchunked = "; the unchunked statement is allowed, since the database reads the whole select before "
                + "it inserts";
        EntityManagerFactory emf = em.getEntityManagerFactory();
        ProviderSupport support = vendor.providerSupport().orElseThrow();
        Set<String> targetTables = lowerCase(support.tablesOf(emf, rootEntity));
        for (Class<?> source : selected) {
            if (hierarchyRoot(metamodel, source) == hierarchyRoot(metamodel, rootEntity)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1806, chunked + " reads " + source.getSimpleName()
                        + ", of the target's own entity hierarchy, so a chunk's key select could read the rows an "
                        + "earlier chunk wrote" + unchunked);
            }
            Set<String> sourceTables = lowerCase(support.tablesOf(emf, source));
            if (targetTables.isEmpty() || sourceTables.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1806, chunked + " cannot tell whether "
                        + source.getSimpleName() + " and " + rootEntity.getSimpleName() + " share a table, since the "
                        + "provider names no tables for "
                        + (targetTables.isEmpty() ? rootEntity : source).getSimpleName() + unchunked);
            }
            sourceTables.retainAll(targetTables);
            if (!sourceTables.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1806, chunked + " reads " + source.getSimpleName()
                        + ", which shares the table " + String.join(", ", new TreeSet<>(sourceTables))
                        + " with the target, so a chunk's key select could read the rows an earlier chunk wrote"
                        + unchunked);
            }
        }
    }

    /** The topmost entity type above {@code entity}, itself when it has no entity supertype. */
    private static Class<?> hierarchyRoot(Metamodel metamodel, Class<?> entity) {
        Class<?> top = entity;
        for (IdentifiableType<?> type = metamodel.entity(entity).getSupertype(); type != null;
                type = type.getSupertype()) {
            if (type instanceof EntityType<?> supertype) {
                top = supertype.getJavaType();
            }
        }
        return top;
    }

    /**
     * Whether a write runs key-first: the profile says the database cannot read a write's target table in a
     * sub-query, and the write's rendering would, because the root shares its table hierarchy with another entity, or
     * because a sub-query reads an entity of the root's hierarchy or touching one of its tables ({@code subqueryReads})
     * (R-WRT-11, R-VND-11, D-109). The metamodel shows the hierarchy but not its inheritance strategy, so every
     * hierarchy counts: key-first is always correct, only slower.
     */
    private boolean keyFirst(Supplier<Set<Class<?>>> subqueryReads) {
        if (vendor.profile().targetTableInSubquery()) {
            return false;
        }
        if (inHierarchy()) {
            return true;
        }
        Set<Class<?>> read = subqueryReads.get();
        return read.stream().anyMatch(this::sharesRootHierarchy) || readsRootTable(read);
    }

    private boolean sharesRootHierarchy(Class<?> entity) {
        return entity.isAssignableFrom(rootEntity) || rootEntity.isAssignableFrom(entity);
    }

    /**
     * Whether reading one of {@code entities} touches a table reading the root does, by the tables the provider names,
     * ignoring case; false for an entity whose set or the root's is unknown, so two entities on one table then go
     * undetected (R-VND-13, D-109).
     */
    private boolean readsRootTable(Set<Class<?>> entities) {
        Optional<ProviderSupport> support = vendor.providerSupport();
        if (entities.isEmpty() || support.isEmpty()) {
            return false;
        }
        EntityManagerFactory emf = em.getEntityManagerFactory();
        Set<String> rootTables = lowerCase(support.get().tablesOf(emf, rootEntity));
        return !rootTables.isEmpty() && entities.stream()
                .flatMap(entity -> support.get().tablesOf(emf, entity).stream())
                .anyMatch(table -> rootTables.contains(table.toLowerCase(Locale.ROOT)));
    }

    private static Set<String> lowerCase(Set<String> tables) {
        Set<String> lower = new HashSet<>();
        tables.forEach(table -> lower.add(table.toLowerCase(Locale.ROOT)));
        return lower;
    }

    /** Whether the root has an entity supertype or subtype in the metamodel. */
    private boolean inHierarchy() {
        Metamodel metamodel = em.getMetamodel();
        for (IdentifiableType<?> type = metamodel.entity(rootEntity).getSupertype(); type != null;
                type = type.getSupertype()) {
            if (type instanceof EntityType<?>) {
                return true;
            }
        }
        return metamodel.getEntities().stream().map(EntityType::getJavaType)
                .anyMatch(type -> type != rootEntity && rootEntity.isAssignableFrom(type));
    }

    /**
     * Throws {@code MQ2501} naming {@code operation} when {@code em} is not joined to a transaction, before any
     * statement, instead of the provider's {@code TransactionRequiredException} at the end (R-WRT-18). A write whose
     * {@code chunk} options commit each chunk needs no transaction but a {@link ChunkTransactions} that serves the
     * factory, else it throws {@code MQ4004} before any statement, the flush included (R-WRT-19, D-62).
     */
    private void requireTransaction(String operation, Optional<ChunkOptions> chunk) {
        if (chunk.map(ChunkOptions::commitsEachChunk).orElse(false)) {
            requireChunkTransactions(operation);
            return;
        }
        if (!em.isJoinedToTransaction()) {
            throw new ModelQueryExecutionException(MqCode.MQ2501, rootEntity.getSimpleName() + ": a bulk " + operation
                    + " needs an active transaction, and the EntityManager is not joined to one");
        }
    }

    private void requireChunkTransactions(String operation) {
        String write = rootEntity.getSimpleName() + ": a bulk " + operation + " with commitEachChunk()";
        if (chunkTransactions == null) {
            throw new ModelQueryConfigurationException(MqCode.MQ4004, write + " runs each chunk through a "
                    + "ChunkTransactions, and none is set on ModelQueryConfig.chunkTransactions(...)");
        }
        try {
            chunkTransactions.checkServes(em.getEntityManagerFactory());
        } catch (RuntimeException e) {
            if (e instanceof ModelQueryConfigurationException configuration
                    && configuration.code() == MqCode.MQ4004) {
                throw configuration;
            }
            throw new ModelQueryConfigurationException(MqCode.MQ4004, write + ": the configured "
                    + chunkTransactions.getClass().getName() + " cannot serve this EntityManagerFactory: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Runs one write: flushes the pending entity changes first, so they are written and not overwritten afterwards,
     * then {@code statements}, and returns the rows they affected (R-WRT-15). Afterwards, whether or not it threw,
     * clears the persistence context unless the write's own mode, else the configured one, is {@code KEEP}, and evicts
     * the root from the second-level cache (D-62).
     */
    private long write(Optional<PersistenceContextMode> ownMode, LongSupplier statements) {
        if (em.isJoinedToTransaction()) {
            em.flush();
        }
        try {
            return statements.getAsLong();
        } finally {
            if (ownMode.orElse(persistenceContextMode) == PersistenceContextMode.CLEAR) {
                em.clear();
            }
            em.getEntityManagerFactory().getCache().evict(rootEntity);
        }
    }

    /**
     * The {@code whole} statement, or with {@code keys} the {@code byKeys} statements over runs of the keys sized to
     * the profile's limits, counting each statement's own binds, {@code SET} values included, plus {@code repeated},
     * the values the whole statement's memoised expressions repeat (R-WRT-08, R-COL-19, D-63); returns the summed rows
     * affected.
     */
    private long direct(Optional<List<Object>> keys, Supplier<Query> whole, Function<List<Object>, Query> byKeys,
            int repeated) {
        if (keys.isEmpty()) {
            return execute(whole.get(), repeated);
        }
        List<Object> all = keys.get();
        int chunk = all.size() == 1 ? 1 : keyChunkSize(byKeys.apply(all.subList(0, 1)), all.get(0),
                OptionalInt.empty(), repeated);
        long written = 0;
        for (int from = 0; from < all.size(); from += chunk) {
            written += execute(byKeys.apply(all.subList(from, Math.min(all.size(), from + chunk))), repeated);
        }
        return written;
    }

    /**
     * Runs {@code keyed} through the keyset loop, key-first or chunked: in rounds of the {@code chunk} size, else the
     * configured {@code bulkWriteChunkSize} for a chunked write, else the profile's clamp, always within the clamp;
     * with the keys selected under a lock when the options say {@code lockKeys()}, and each round in a new
     * transaction when they say {@code commitEachChunk()} (R-WRT-11, R-WRT-17, R-WRT-19, D-63). The clamp counts
     * the binds of the {@code whole} statement, or of {@code byKeys} over one key, less that key's, plus the whole
     * statement's memoised expression repeats (R-COL-19): the whole tree's binds are at least those of the root terms
     * the write keeps and of the tree the key select renders, so neither passes the limit.
     */
    private <M> long keyset(KeysetWrite.Keyed<M> keyed, Supplier<Query> whole, Function<List<Object>, Query> byKeys,
            Optional<ChunkOptions> chunk, CriteriaBuilder cb, int repeated) {
        OptionalInt size = chunk.isEmpty() ? OptionalInt.empty()
                : OptionalInt.of(chunk.get().size().orElse(bulkWriteChunkSize));
        int n = keyed.distinctKeys()
                .map(all -> all.size() == 1 ? 1
                        : keyChunkSize(byKeys.apply(all.subList(0, 1)), all.get(0), size, repeated))
                .orElseGet(() -> keyLimits.clamp(whole.get().getParameters().size() + repeated,
                        keyed.key().columns().size(), size));
        boolean perChunk = chunk.map(ChunkOptions::commitsEachChunk).orElse(false);
        return new KeysetWrite(cb, (on, built, keyset, cursor) -> create(rootEntity.getSimpleName(), on, built.query(),
                keyset, cursor, built.joins()), statement -> execute(statement, repeated), this::timeoutCancellation,
                em, perChunk ? chunkTransactions : null)
                        .run(keyed, n, chunk.map(ChunkOptions::locksKeys).orElse(false));
    }

    /**
     * The most keys one write statement takes, {@code configured} if any, from {@code oneKey}, the statement over a
     * single key: its binds less the key's own, plus the {@code repeated} the whole statement's memoised expressions
     * add, are the statement's (D-63, R-COL-19).
     */
    private int keyChunkSize(Query oneKey, Object key, OptionalInt configured, int repeated) {
        int keyColumns = key instanceof List<?> components ? components.size() : 1;
        int ownBinds = Math.max(0, oneKey.getParameters().size() + repeated - keyColumns);
        return keyLimits.clamp(ownBinds, keyColumns, configured);
    }

    /**
     * Runs a write statement within the bind limit, with the configured timeout applied through the profile
     * (R-FLT-09, R-EXE-11). {@code repeated} counts the bind values the statement's memoised expressions repeat beyond
     * what JPA reports (R-COL-19, D-80).
     */
    private int execute(Query statement, int repeated) {
        withinBindLimit(rootEntity.getSimpleName(), statement.getParameters().size() + repeated, 0);
        queryTimeout.ifPresent(timeout -> vendor.profile().applyTimeout(statement, timeout));
        LOG.log(TRACE, () -> rootEntity.getSimpleName() + ": statement binds "
                + (statement.getParameters().size() + repeated) + " of " + renderOptions.maxBindParameters());
        long start = System.nanoTime();
        int written;
        try {
            written = statement.executeUpdate();
        } catch (PersistenceException e) {
            throw timeoutCancellation(e);
        }
        traceTimed(rootEntity.getSimpleName(), written + (written == 1 ? " row" : " rows") + " written", start);
        return written;
    }

    /** Runs {@code check} the first time {@code definition} runs on this executor's factory, before any statement. */
    private void checkWriteOnce(Object definition, Consumer<Metamodel> check) {
        Set<Object> checked = WRITES_CHECKED.computeIfAbsent(em.getEntityManagerFactory(),
                factory -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>())));
        if (!checked.contains(definition)) {
            check.accept(em.getMetamodel());
            checked.add(definition); // only once it passed, so a check that threw runs again next time
        }
    }

    /**
     * Runs the R-QRY-09 phase check and the R-FCH-02 fetch-plan check the first time any executor runs {@code q}
     * (D-21).
     */
    private <E2> void checkFirstRun(ModelQuery<E2, ?, ?> q) {
        checkFirstRun(q, true);
    }

    /**
     * As {@link #checkFirstRun(ModelQuery)}; without {@code fetchesModels}, as for {@code count}, which loads no
     * children and runs no enricher, a failed fetch-plan check logs a {@code WARNING} instead of throwing
     * {@code MQ1702}, and is not remembered, so the next call that returns models throws it.
     */
    private <E2> void checkFirstRun(ModelQuery<E2, ?, ?> q, boolean fetchesModels) {
        // An orderedBy copy is covered by the check of its definition, so a per-request sort does not repeat it.
        ModelQuery<E2, ?, ?> definition = q.definition();
        if (!FIRST_RUN_CHECKED.contains(definition)) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            q.checkPhases(cb);
            try {
                q.checkFetch(cb);
            } catch (ModelQueryDefinitionException e) {
                if (fetchesModels) {
                    throw e;
                }
                LOG.log(System.Logger.Level.WARNING, "count runs, but the fetch plan of the query is invalid, and "
                        + "every call that returns models throws: {0}", e.getMessage());
                return;
            }
            FIRST_RUN_CHECKED.add(definition); // only once they passed, so a check that threw runs again next time
        }
    }

    // ---- page content

    /** The rows of a page, mapped, before its fetch plan runs. */
    private <M> List<Loaded<M>> fetch(ModelQuery<E, ?, M> q, int offset, int maxRows) {
        if (primaryKeyFirst(q, offset)) {
            PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(); // primaryKeyFirst(...) needs one (MQ1201)
            // Read the page's keys in the stable order, then their rows: the same rows as the one-step page, a key
            // read twice through a predicate's to-many join included.
            List<Object> keys = readKeys(q, key, keyQuery(q), offset, maxRows);
            // The batch size compiles a statement, so an empty key page skips it.
            return keys.isEmpty() ? List.of() : readByKeys(q, key, keys, keyBatchSize(q, key));
        }
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        appendStableOrder(q, built);
        TypedQuery<Tuple> query = create(q, built);
        query.setFirstResult(offset);
        query.setMaxResults(maxRows);
        return mapAll(q, query, built);
    }

    /**
     * Offset pages of an order with ties overlap or skip rows, so the primary key (the group keys of a grouped query)
     * closes the order (api/11 §5, R-PAG-01, R-PAG-11).
     */
    private <E2, M> void appendStableOrder(ModelQuery<E2, ?, M> q, BuiltQuery<M> built) {
        List<? extends SelectField<M, ?>> tieBreakers = q.isGrouped()
                ? q.groupBy()
                : q.primaryKey().<List<? extends SelectField<M, ?>>>map(key -> key.columns()).orElse(List.of());
        List<Order> orders = null;
        for (SelectField<M, ?> column : tieBreakers) {
            boolean ordered = q.orderBy().stream().map(OrderField::column).anyMatch(column::equals);
            if (!ordered) {
                if (orders == null) {
                    orders = new ArrayList<>(built.query().getOrderList());
                }
                orders.add(em.getCriteriaBuilder().asc(column.expression(built.joins())));
            }
        }
        if (orders != null) {
            built.query().orderBy(orders);
        }
    }

    // ---- primary-key-first

    /** Whether a read at {@code offset} goes primary keys first (R-PAG-07); never without {@code primaryKeyFirst}. */
    private static boolean primaryKeyFirst(ModelQuery<?, ?, ?> q, long offset) {
        return q.primaryKeyFirst().map(pkFirst -> offset > pkFirst.offsetThreshold()).orElse(false);
    }

    /** The {@code PRIMARY_KEY} statement, in the stable order the one-step read uses (R-PAG-01). */
    private <M> BuiltQuery<M> keyQuery(ModelQuery<E, ?, M> q) {
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.PRIMARY_KEY, renderOptions);
        appendStableOrder(q, built);
        return built;
    }

    /** Step 1: the primary keys of {@code maxRows} rows from {@code offset}, in {@code keyQuery}'s order. */
    private <M> List<Object> readKeys(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key, BuiltQuery<M> keyQuery,
            int offset, int maxRows) {
        TypedQuery<Tuple> query = create(q, keyQuery);
        query.setFirstResult(offset);
        query.setMaxResults(maxRows);
        List<Tuple> rows = rows(q, query);
        List<Object> keys = new ArrayList<>(rows.size());
        for (Tuple tuple : rows) {
            keys.add(Keys.keyOf(q, key, keyQuery.selection().row(tuple)));
        }
        return keys;
    }

    /**
     * Step 2: the models of {@code keys}, one per position in {@code keys}, in that order. Each statement takes at
     * most {@code batch} keys, with the query's own predicate and order (R-PAG-07, R-PAG-08). {@code IN}
     * keeps neither the step-1 order nor the order across statements, so rows are placed by their key's position. A
     * key whose row no longer matches between the two steps is skipped.
     */
    private <M> List<Loaded<M>> readByKeys(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key, List<Object> keys,
            int batch) {
        if (keys.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // A predicate's to-many join repeats a key in step 1; its row is read once and placed at every position.
        List<Object> distinct = List.copyOf(new LinkedHashSet<>(keys));
        Map<Object, Found<M>> found = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += batch) {
            List<Object> batchKeys = distinct.subList(from, Math.min(distinct.size(), from + batch));
            BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL_BY_KEYS, renderOptions);
            Predicate byKey = key.in(batchKeys, built.joins(), cb, false);
            Predicate own = built.query().getRestriction();
            built.query().where(own == null ? byKey : cb.and(own, byKey));
            appendStableOrder(q, built);
            for (Tuple tuple : rows(q, create(q, built))) {
                found.putIfAbsent(Keys.keyOf(q, key, built.selection().row(tuple)), new Found<>(built, tuple));
            }
        }
        List<Loaded<M>> models = new ArrayList<>(keys.size());
        for (Object rowKey : keys) {
            Found<M> row = found.get(rowKey);
            if (row != null) {
                // Mapped per position, so afterMap runs once per row.
                models.add(loaded(row.built(), row.tuple()));
            }
        }
        return models;
    }

    /** A step-2 row, with the statement that read it. */
    private record Found<M>(BuiltQuery<M> built, Tuple tuple) {}

    /**
     * The most keys one step-2 statement takes: the configured batch size, if any, within the largest power of two
     * that fits the profile's IN-list limit and its bind-parameter limit once the statement's own binds are bound, at
     * one bind per key column (R-PAG-07, D-32, D-80). At least one, so a query that alone passes the bind limit is
     * refused with {@code MQ1307} as its one-step page would be. It compiles a statement without running it, so a
     * caller computes it once per {@code page} or {@code export} call.
     */
    private <M> int keyBatchSize(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key) {
        // The statement's own binds, the query's values and the customizer's, before any key is added. JPA reports a
        // literal the provider binds as a parameter of the query; one it renders inline takes no bind. A memoised
        // expression used twice reports once, so its repeated renderings are added back (R-COL-19).
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL_BY_KEYS, renderOptions);
        int ownBinds = em.createQuery(built.query()).getParameters().size() + built.joins().repeatedExpressionBinds();
        return keyLimits.clamp(ownBinds, key.columns().size(), primaryKeyFirstBatchSize);
    }

    private <M> List<Loaded<M>> mapAll(Object label, TypedQuery<Tuple> query, BuiltQuery<M> built) {
        List<Tuple> tuples = rows(label, query);
        var models = new ArrayList<Loaded<M>>(tuples.size());
        for (Tuple tuple : tuples) {
            models.add(loaded(built, tuple));
        }
        return models;
    }

    // ---- export

    /**
     * Whether {@code rowKey} is new to the export: a key seen before is a row the stable order already delivered,
     * across the boundary when rows shifted between pages, or within the page when a predicate's to-many join repeats
     * the root. Records it in {@code pageKeys}.
     */
    private static boolean isFresh(Set<Object> pageKeys, Set<Object> previousKeys, Object rowKey) {
        return pageKeys.add(rowKey) && !previousKeys.contains(rowKey);
    }

    /**
     * The export loop of engine/21 §4 in offset mode; {@code built} is already in its stable order, which
     * {@code keyOfRow} identifies a row of: its primary key, or its group key tuple for a grouped query (R-PAG-11),
     * for which {@code key} is {@code null}. A page past the {@code primaryKeyFirst} offset reads its keys first and
     * only the fresh keys' rows (R-PAG-07).
     */
    private <M, S> long exportByOffset(ModelQuery<E, ?, M> q, BuiltQuery<M> built, PrimaryKey<M, ?> key,
            Function<Row, Object> keyOfRow, int pageSize, long limit, Function<List<M>, List<S>> pageTransformer,
            Consumer<S> sink) {
        long passed = 0;
        int offset = 0;
        BuiltQuery<M> keyQuery = null;
        int batch = 0; // computed on the first non-empty key page
        // The only state carried from page to page, so memory stays bounded by one page (R-PAG-02, INV-4).
        Set<Object> previousKeys = Set.of();
        while (passed < limit) {
            Set<Object> keys = new HashSet<>();
            List<Loaded<M>> fresh;
            int read;
            if (primaryKeyFirst(q, offset)) {
                // Only an ungrouped query with a primary key gets here (MQ1201, MQ1402), so key is set.
                if (keyQuery == null) {
                    keyQuery = keyQuery(q);
                }
                List<Object> pageKeys = readKeys(q, key, keyQuery, offset, pageSize);
                List<Object> freshKeys = new ArrayList<>(pageKeys.size());
                for (Object rowKey : pageKeys) {
                    if (isFresh(keys, previousKeys, rowKey)) {
                        freshKeys.add(rowKey);
                    }
                }
                if (batch == 0 && !freshKeys.isEmpty()) {
                    batch = keyBatchSize(q, key);
                }
                fresh = readByKeys(q, key, freshKeys, batch); // only the fresh keys' rows are read (R-PAG-07)
                read = pageKeys.size();
            } else {
                TypedQuery<Tuple> query = create(q, built);
                query.setFirstResult(offset);
                query.setMaxResults(pageSize);
                List<Tuple> rows = rows(q, query);
                fresh = new ArrayList<>(rows.size());
                for (Tuple tuple : rows) {
                    Row row = built.selection().row(tuple);
                    if (isFresh(keys, previousKeys, keyOfRow.apply(row))) {
                        fresh.add(new Loaded<>(built.map(row), row));
                    }
                }
                read = rows.size();
            }
            // After the dedupe, so the plan runs on exactly the rows passed on (R-FCH-09).
            passed = pass(models(q, fresh), passed, limit, pageTransformer, sink);
            if (read < pageSize) {
                break;
            }
            previousKeys = keys;
            if (offset > Integer.MAX_VALUE - read) {
                throw new ModelQueryExecutionException(MqCode.MQ2002, q + ": offset export went past "
                        + Integer.MAX_VALUE + " rows, the largest offset JPA takes; use keyset() for a result this "
                        + "large");
            }
            offset += read;
        }
        return passed;
    }

    /**
     * The export loop of engine/21 §4 in keyset mode: each page is the rows after the last row of the page before, so
     * no offset is skipped and no page overlaps the last (R-PAG-04, R-PAG-06). {@code first} is the unrestricted first
     * page's statement; each later page is built afresh with the keyset predicate added to the query's own. A page
     * holding a key of the page before throws {@code MQ2205} (R-PAG-14).
     */
    private <M, S> long exportByKeyset(ModelQuery<E, ?, M> q, BuiltQuery<M> first, PrimaryKey<M, ?> key,
            int pageSize, long limit, Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        Keyset<M> keyset = Keyset.of(q, key, vendor.profile().defaultAscendingNullOrdering(), providerNulls,
                keysetNullKeys);
        long passed = 0;
        BuiltQuery<M> built = first;
        // The last row's keyset values and the page's keys: the only state carried from page to page, bounded by one
        // page (INV-4).
        Object[] cursor = null;
        Set<Object> previousKeys = Set.of();
        while (passed < limit) {
            Keyset.Beyond after = null;
            if (cursor != null) {
                built = q.buildQuery(cb, Phase.MODEL, renderOptions);
                after = keyset.after(cursor, built.joins(), cb);
                Predicate own = built.query().getRestriction();
                built.query().where(own == null ? after.predicate() : cb.and(own, after.predicate()));
            }
            keyset.appendOrder(built, cb);
            TypedQuery<Tuple> query = create(q, em, built.query(), keyset, cursor, built.joins());
            if (after != null) {
                after.bindTo(query);
            }
            query.setMaxResults(pageSize);
            List<Tuple> rows = rows(q, query);
            Set<Object> keys = new HashSet<>();
            List<Loaded<M>> fresh = new ArrayList<>(rows.size());
            for (Tuple tuple : rows) {
                Row row = built.selection().row(tuple);
                Object rowKey = Keys.keyOf(q, key, row);
                if (previousKeys.contains(rowKey)) {
                    // A cursor value did not compare equal to the stored one once bound, or the row's keyset value
                    // moved after the cursor between pages. Either can skip rows too, and a repeated tie group that
                    // fills a page would loop, so this throws rather than dedupes (R-PAG-14, D-31).
                    throw new ModelQueryExecutionException(MqCode.MQ2205, q + ": a keyset export page repeated a row "
                            + "of the page before: a cursor value did not survive being bound, or a row's keyset "
                            + "value changed during the export");
                }
                // Read from every row, not only the last, so a NULL key is refused wherever the page holds it.
                cursor = keyset.cursor(row);
                // Equal keys sort together and the cursor is past them all, so a key repeats only within a page,
                // when a predicate's to-many join repeats the root, and that repeat is dropped (R-PAG-02).
                if (keys.add(rowKey)) {
                    fresh.add(new Loaded<>(built.map(row), row));
                }
            }
            passed = pass(models(q, fresh), passed, limit, pageTransformer, sink);
            if (rows.size() < pageSize) {
                break;
            }
            previousKeys = keys;
        }
        return passed;
    }

    /**
     * Passes a page's models to {@code pageTransformer} and its items to {@code sink} until {@code limit}; neither is
     * called for an empty page (R-PAG-09). Returns the items passed so far (R-PAG-10).
     */
    private static <M, S> long pass(List<M> fresh, long passed, long limit,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        if (fresh.isEmpty()) {
            return passed;
        }
        List<S> items = Objects.requireNonNull(pageTransformer.apply(fresh), "pageTransformer result");
        long total = passed;
        for (int i = 0; i < items.size() && total < limit; i++) {
            sink.accept(items.get(i));
            total++;
        }
        return total;
    }

    /**
     * The row's group: its group-by values in {@code groupBy} order, unique per row of a grouped query (R-PAG-11). SQL
     * groups NULLs together, so a NULL is a value of the tuple like any other; a whole-table aggregate's is empty.
     */
    private static <M> Object groupKeyOf(ModelQuery<?, ?, M> q, Row row) {
        List<ScalarField<M, ?>> groupBy = q.groupBy();
        Object[] values = new Object[groupBy.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.raw(groupBy.get(i));
        }
        return Arrays.asList(values);
    }

    /**
     * Throws {@code MQ2204} when a column of {@code built}'s selection is read through a to-many join, before any
     * query runs: one primary key then spans several rows, so the stable order is not unique and the boundary dedupe
     * would drop real rows (R-PAG-13). An expression reads every column it is built from, so one of them through a
     * to-many join is refused too (R-COL-17).
     */
    private static void refuseToManySelection(ModelQuery<?, ?, ?> q, BuiltQuery<?> built, String operation) {
        for (SelectField<?, ?> field : built.selection().fields()) {
            boolean expression = field instanceof ExpressionField<?, ?>;
            for (ColumnField<?, ?, ?> column : selectedColumns(field)) {
                Join<?, ?> join = toManyJoin(column, built.joins());
                if (join != null) {
                    Attribute<?, ?> attribute = join.getAttribute();
                    throw new ModelQueryExecutionException(MqCode.MQ2204, q + ": " + operation + " "
                            + (expression ? "reads a column of an expression" : "selects a column")
                            + " through the to-many join "
                            + attribute.getDeclaringType().getJavaType().getSimpleName() + "." + attribute.getName()
                            + ", so one primary key spans several rows; select from the child side, or filter with "
                            + "Filters.exists(...)");
                }
            }
        }
    }

    /** Whether {@code built}'s selection reads any column through a to-many join (R-PAG-13). */
    private static boolean readsThroughToMany(BuiltQuery<?> built) {
        for (SelectField<?, ?> field : built.selection().fields()) {
            for (ColumnField<?, ?, ?> column : selectedColumns(field)) {
                if (toManyJoin(column, built.joins()) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The columns a selected field reads: a column itself, an expression's operands, or none for an aggregate. */
    @SuppressWarnings("unchecked")
    private static List<ColumnField<?, ?, ?>> selectedColumns(SelectField<?, ?> field) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            return List.of(column);
        }
        if (field instanceof ExpressionField<?, ?> expression) {
            return (List<ColumnField<?, ?, ?>>) (List<?>) expression.columns();
        }
        return List.of();
    }

    /** The nearest to-many join {@code column} is read through, or {@code null} (R-PAG-13, R-COL-19). */
    private static Join<?, ?> toManyJoin(ColumnField<?, ?, ?> column, JoinContext joins) {
        for (Path<?> path = column.path(joins); path != null; path = path.getParentPath()) {
            if (path instanceof Join<?, ?> join && join.getAttribute() instanceof PluralAttribute<?, ?, ?>) {
                return join;
            }
        }
        return null;
    }

    // ---- fetch plans

    /** A mapped row and the row it was read from, whose raw values a child load keys on (R-FCH-05). */
    private record Loaded<M>(M model, Row row) {}

    /** {@code tuple} as a {@link Loaded}, its row built once. */
    private static <M> Loaded<M> loaded(BuiltQuery<M> built, Tuple tuple) {
        Row row = built.selection().row(tuple);
        return new Loaded<>(built.map(row), row);
    }

    /** The models of {@code rows}, in order. */
    private static <M> List<M> modelsOf(List<Loaded<M>> rows) {
        List<M> models = new ArrayList<>(rows.size());
        rows.forEach(loaded -> models.add(loaded.model()));
        return models;
    }

    /**
     * The models of {@code rows}, in order, with {@code q}'s fetch plan, if any, run once over them: on exactly the
     * rows a call returns or passes on, never on what paging only reads (R-FCH-09).
     */
    private <M> List<M> models(ModelQuery<?, ?, M> q, List<Loaded<M>> rows) {
        Optional<FetchPlan<M>> plan = q.fetch();
        if (plan.isPresent() && !rows.isEmpty()) {
            return run(plan.get(), q.toString(), rows);
        }
        return modelsOf(rows);
    }

    /**
     * Runs {@code plan} once over {@code rows}, a non-empty page: its children, then its join plans, each running its
     * nested plan whole, then its enrichers in order, so a nested plan's enrichers run before this one's (R-FCH-08).
     * {@code owner} names the plan's model, or its join path, in a message.
     */
    private <M> List<M> run(FetchPlan<M> plan, String owner, List<Loaded<M>> rows) {
        List<M> models = modelsOf(rows);
        for (ChildLoad<M, ?> load : plan.childLoads()) {
            loadChild(load, rows, models);
        }
        for (JoinPlan<M, ?> join : plan.joinPlans()) {
            runJoin(join, owner, rows, models);
        }
        List<M> result = models;
        for (Enricher<M> enricher : plan.enrichers()) {
            result = enricher.enrich(owner, result);
        }
        return result;
    }

    /**
     * Runs {@code join}'s nested plan once over the nested models present in {@code models}, one entry per parent,
     * duplicates included, each read from its parent's row under the join, and sets each result back on its parent.
     * A parent whose join found nothing is skipped (R-FCH-07).
     */
    private <M, N> void runJoin(JoinPlan<M, N> join, String owner, List<Loaded<M>> rows, List<M> models) {
        JoinField<M, N> field = join.field();
        List<Integer> parents = new ArrayList<>();
        List<Loaded<N>> nested = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            Optional<N> present = field.get(models.get(i));
            if (present.isPresent()) {
                parents.add(i);
                nested.add(new Loaded<>(present.get(), rows.get(i).row().scoped(field.table())));
            }
        }
        if (nested.isEmpty()) {
            return;
        }
        List<N> filled = run(join.plan(), owner + "." + field.name(), nested);
        for (int k = 0; k < parents.size(); k++) {
            int i = parents.get(k);
            models.set(i, field.with(models.get(i), filled.get(k)));
        }
    }

    /**
     * Loads {@code load}'s children for the distinct non-null keys of {@code rows}, and sets them on the parent at the
     * same position of {@code models}. A parent whose key is null, or that has no child, keeps the empty value it was
     * mapped with (R-FCH-03).
     */
    private <M, C> void loadChild(ChildLoad<M, C> load, List<Loaded<M>> rows, List<M> models) {
        ColumnField<M, ?, ?> key = load.field().key();
        if (!rows.isEmpty() && !rows.get(0).row().isSelected(key)) {
            // raw(...) is null for an unselected column, which would read as "no key" and leave every child empty.
            throw new IllegalStateException(load + ": the parent key " + key + " is not selected, so no child can be "
                    + "matched to a parent");
        }
        Object[] keys = new Object[rows.size()];
        var distinct = new LinkedHashSet<Object>();
        for (int i = 0; i < keys.length; i++) {
            // The attribute value, before any converter, as the child's foreign key is read (R-FCH-05, R-COL-11).
            keys[i] = rows.get(i).row().raw(key);
            if (keys[i] != null) {
                distinct.add(keys[i]);
            }
        }
        if (distinct.isEmpty()) {
            LOG.log(DEBUG, () -> "child " + load + ": no key on the page, so no child query");
            return;
        }
        Map<Object, List<C>> children = childrenOf(load, load.query(), List.copyOf(distinct));
        for (int i = 0; i < keys.length; i++) {
            List<C> found = keys[i] == null ? null : children.get(keys[i]);
            if (found != null) {
                models.set(i, load.field().with(models.get(i), found));
            }
        }
    }

    /**
     * The children of {@code keys}, grouped by key in child order. The keys go in rounds of at most the largest power
     * of two within the profile's limits, each one statement {@code where foreignKey IN keys} with the child filters
     * and the child order closed by the child's primary key; a child read twice for one key is kept once. The child
     * plan then runs once over all the rows read. A {@code through} child's statement is rooted at the parent's
     * entity and matches the parent's key instead, which {@link ChildLoad} builds (R-FCH-04, R-FCH-05, R-FCH-14,
     * D-99, D-100).
     */
    private <E2, C> Map<Object, List<C>> childrenOf(ChildLoad<?, C> load, ModelQuery<E2, ?, C> q,
            List<Object> keys) {
        checkFirstRun(q);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // The statement's own binds, before any key is added, at one bind per key (R-PAG-07, D-32), plus the values
        // its memoised expressions repeat (R-COL-19); a dialect that binds the LIMIT of the setMaxResults below as a
        // parameter takes one more.
        BuiltQuery<C> counted = load.build(cb, renderOptions);
        int ownBinds = em.createQuery(counted.query()).getParameters().size()
                + counted.joins().repeatedExpressionBinds() + (load.maxPerParent() > 0 ? 1 : 0);
        int round = keyLimits.clamp(ownBinds, 1, OptionalInt.empty());
        LOG.log(DEBUG, () -> "child " + load + ": " + keys.size() + (keys.size() == 1 ? " key" : " keys") + " in "
                + ((keys.size() - 1) / round + 1) + " round(s)");
        List<Loaded<C>> loaded = new ArrayList<>();
        List<Object> parents = new ArrayList<>();
        for (int from = 0; from < keys.size(); from += round) {
            List<Object> roundKeys = keys.subList(from, Math.min(keys.size(), from + round));
            BuiltQuery<C> built = load.build(cb, renderOptions);
            Predicate byKey = load.key(built).in(roundKeys);
            Predicate own = built.query().getRestriction();
            built.query().where(own == null ? byKey : cb.and(own, byKey));
            appendStableOrder(q, built);
            TypedQuery<Tuple> query = create(load, built);
            long cap = (long) roundKeys.size() * load.maxPerParent() + 1;
            if (load.maxPerParent() > 0) {
                query.setMaxResults((int) Math.min(cap, Integer.MAX_VALUE));
            }
            List<Tuple> tuples = rows(load, query);
            Set<Object> wanted = new HashSet<>(roundKeys);
            // Per key: the children kept, by primary key, and the rows read, which MQ2603 names the most of.
            Map<Object, Set<Object>> kept = new HashMap<>();
            Map<Object, Integer> read = new HashMap<>();
            PrimaryKey<C, ?> childKey = q.primaryKey().orElse(null);
            for (Tuple tuple : tuples) {
                Row row = built.selection().row(tuple);
                Object parent = load.key(built, tuple, row);
                if (!wanted.contains(parent)) {
                    throw new ModelQueryExecutionException(MqCode.MQ2604, load + ": a child row's "
                            + load.field().foreignKey().<Object>map(column -> column).orElseGet(load.field()::key)
                            + " is " + parent + ", which equals none of the " + roundKeys.size() + " keys of the "
                            + "statement that matched it; the column's collation equates values Java tells apart, "
                            + "by case or trailing spaces, so key the child on a column with a binary collation");
                }
                read.merge(parent, 1, Integer::sum);
                Set<Object> children = kept.computeIfAbsent(parent, k -> new HashSet<>());
                // A child filter through a to-many join repeats a child, and a many-to-many child belongs to several
                // keys, so a child is kept once per key (R-FCH-04, D-99). Without a primary key, every row is one.
                if (children.add(childKey == null ? new Object() : Keys.keyOf(load, childKey, row))) {
                    if (children.size() > 1 && !load.field().isToMany()) {
                        throw new ModelQueryExecutionException(MqCode.MQ2601, load + ": the to-one child found two "
                                + "distinct rows for key " + parent + "; key it on a column unique per child, or "
                                + "declare the field a List");
                    }
                    loaded.add(new Loaded<>(built.map(row), row));
                    parents.add(parent);
                }
            }
            if (load.maxPerParent() > 0) {
                checkBound(load, roundKeys.size(), tuples.size() >= cap, kept, read);
            }
        }
        List<C> children = models(q, loaded);
        Map<Object, List<C>> grouped = new HashMap<>();
        for (int i = 0; i < children.size(); i++) {
            grouped.computeIfAbsent(parents.get(i), k -> new ArrayList<>()).add(children.get(i));
        }
        return grouped;
    }

    /**
     * Throws {@code MQ2603} when a key of a round has more distinct children than {@code maxPerParent}, or the round
     * read its cap of keys × {@code maxPerParent} + 1 rows, and names the key with the most rows read (R-FCH-11).
     */
    private static void checkBound(ChildLoad<?, ?> load, int keys, boolean capped, Map<Object, Set<Object>> kept,
            Map<Object, Integer> read) {
        int bound = load.maxPerParent();
        Object over = null;
        Object most = null;
        for (Map.Entry<Object, Integer> entry : read.entrySet()) {
            Object key = entry.getKey();
            if (kept.get(key).size() > bound && (over == null || entry.getValue() > read.get(over))) {
                over = key;
            }
            if (most == null || entry.getValue() > read.get(most)) {
                most = key;
            }
        }
        if (over != null) {
            throw new ModelQueryExecutionException(MqCode.MQ2603, load + ": key " + over + " has "
                    + kept.get(over).size() + " children, more than maxPerParent(" + bound + "); raise the bound or "
                    + "narrow the child filters");
        }
        if (capped) {
            throw new ModelQueryExecutionException(MqCode.MQ2603, load + ": a round of " + keys + " keys read its "
                    + "cap of " + keys + " x maxPerParent(" + bound + ") + 1 rows, key " + most + " the most, "
                    + read.get(most) + "; raise the bound or narrow the child filters");
        }
    }

    // ---- count

    private <M> long countRows(ModelQuery<E, ?, M> q) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL, renderOptions);
        CriteriaQuery<Tuple> query = built.query();
        query.orderBy(List.<Order>of()); // the count needs no order (R-EXE-05)
        if (q.isGrouped()) {
            return countGroups(q, built);
        }
        // Every predicate and join stays, since one can change the number of rows; only the selection is replaced.
        From<?, ?> root = query.getRoots().iterator().next();
        boolean readThroughToMany = readsThroughToMany(built);
        Expression<Long> count = hasToManyJoin(root) && !readThroughToMany ? cb.countDistinct(root) : cb.count(root);
        query.multiselect(count);
        return ((Number) single(q, create(q, built)).get(0)).longValue();
    }

    /** {@code count(*)} over the groups: in the database when the provider support can, else client-side. */
    private <M> long countGroups(ModelQuery<E, ?, M> q, BuiltQuery<M> built) {
        CriteriaQuery<Tuple> query = built.query();
        if (!q.groupBy().isEmpty()) {
            // The group keys identify a group, and selecting only them is what the count needs (R-EXE-05).
            List<Selection<?>> keys = new ArrayList<>();
            for (ScalarField<M, ?> key : q.groupBy()) {
                // Two keys over one attribute can resolve to one aliased path, which is selected once (R-COL-10).
                Selection<?> path = key.expression(built.joins());
                if (keys.stream().noneMatch(added -> added == path)) {
                    keys.add(path);
                }
            }
            query.multiselect(keys);
        }
        // The provider support only builds the count; it runs here, so the configured timeout applies (R-EXE-11).
        Optional<CriteriaQuery<Long>> count = vendor.providerSupport().flatMap(p -> p.countQuery(query));
        if (count.isPresent()) {
            return single(q, create(q, em, count.get(), null, null, built.joins()));
        }
        LOG.log(System.Logger.Level.WARNING, "count over the grouped query on {0} runs it and counts its rows in "
                + "memory, because no ProviderSupport counts groups for this persistence provider; add "
                + "model-query-hibernate for a count in the database (R-EXE-03)", rootEntity.getSimpleName());
        return rows(q, create(q, built)).size();
    }

    private static boolean hasToManyJoin(From<?, ?> from) {
        for (Join<?, ?> join : from.getJoins()) {
            if (join.getAttribute() instanceof PluralAttribute<?, ?, ?> || hasToManyJoin(join)) {
                return true;
            }
        }
        return false;
    }

}
