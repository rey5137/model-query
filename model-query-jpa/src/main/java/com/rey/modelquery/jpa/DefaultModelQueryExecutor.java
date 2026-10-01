package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Query;
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
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The executor {@link ModelQueryExecutor#create} returns.
 *
 * @implSpec R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04, R-EXE-05, R-EXE-06, R-EXE-07, R-EXE-09, R-QRY-09, R-PAG-01,
 *     R-PAG-02, R-PAG-03, R-PAG-04, R-PAG-05, R-PAG-06, R-PAG-07, R-PAG-08, R-PAG-09, R-PAG-10, R-PAG-11, R-PAG-12,
 *     R-PAG-13, R-PAG-14, R-AGG-09, R-EXE-08, R-EXE-11, R-WRT-07, R-WRT-14, R-WRT-16, R-WRT-23, D-61
 */
final class DefaultModelQueryExecutor<E> implements ModelQueryExecutor<E> {

    private static final System.Logger LOG = System.getLogger(DefaultModelQueryExecutor.class.getName());

    /**
     * The queries whose phases were checked, by identity. Static, because the check is once per {@code ModelQuery}
     * whichever executor runs it first (D-21); weak, so a query built per request does not stay reachable.
     */
    private static final Set<ModelQuery<?, ?, ?>> PHASES_CHECKED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /**
     * The write definitions whose metamodel checks passed, per factory (D-61): a definition is checked once for each
     * factory it runs on, since another factory may map the root differently. Weak on both levels, so neither a
     * closed factory nor a definition built per request stays reachable.
     */
    private static final Map<EntityManagerFactory, Set<Object>> WRITES_CHECKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final EntityManager em;
    private final Class<E> rootEntity;
    /** The profile and provider support of {@code em}'s factory, resolved once per factory (R-VND-02). */
    private final ResolvedVendor vendor;
    /** The profile's facts as every query build of this executor renders by (D-34). */
    private final RenderOptions renderOptions;
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

    DefaultModelQueryExecutor(EntityManager em, Class<E> rootEntity, ModelQueryConfig config) {
        this.em = Objects.requireNonNull(em, "em");
        this.rootEntity = Objects.requireNonNull(rootEntity, "rootEntity");
        Objects.requireNonNull(config, "config");
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
        this.primaryKeyFirstBatchSize = config.primaryKeyFirstBatchSize();
        this.queryTimeout = config.queryTimeout();
        this.keysetNullKeys = config.keysetNullKeys();
        this.exportPageSize = config.exportPageSize();
        this.streamFetchSize = config.streamFetchSize();
    }

    @Override
    public <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkPhasesOnce(q);
        if (zeroLimit(limit)) {
            return List.of(); // no statement runs for a zero limit (R-EXE-06)
        }
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        return mapAll(limited(built, limit), built);
    }

    @Override
    public <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(body, "body");
        checkPhasesOnce(q);
        if (zeroLimit(limit)) {
            try (Stream<M> none = Stream.empty()) {
                return body.apply(none); // no statement runs for a zero limit (R-EXE-06)
            }
        }
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        TypedQuery<Tuple> query = limited(built, limit);
        // Precondition first, so a refusal runs no statement; the configured fetch size is read only by the profiles
        // that stream by cursor (R-EXE-08, R-QRY-15).
        vendor.profile().checkStreamingPreconditions(em);
        vendor.profile().applyStreaming(query, streamFetchSize);
        // Rows are mapped one at a time as body pulls them; closing the mapped stream closes the result stream under
        // it, whether body returns, stops early or throws (R-EXE-07, R-EXE-09).
        try (Stream<Tuple> tuples = query.getResultStream(); Stream<M> models = tuples.map(built::map)) {
            return body.apply(models);
        }
    }

    private static boolean zeroLimit(Limit limit) {
        return limit.maxRows().isPresent() && limit.maxRows().getAsInt() == 0;
    }

    /** A statement of {@code query} with the configured timeout applied through the profile (R-EXE-11). */
    private <T> TypedQuery<T> create(CriteriaQuery<T> query) {
        TypedQuery<T> typed = em.createQuery(query);
        queryTimeout.ifPresent(timeout -> vendor.profile().applyTimeout(typed, timeout));
        return typed;
    }

    /** {@code built}'s statement, capped at {@code limit}'s rows if it has any. */
    private <M> TypedQuery<Tuple> limited(BuiltQuery<M> built, Limit limit) {
        TypedQuery<Tuple> query = create(built.query());
        limit.maxRows().ifPresent(query::setMaxResults);
        return query;
    }

    @Override
    public <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(mode, "mode");
        checkPhasesOnce(q);
        int offset = page.offset();
        int size = page.pageSize();
        if (mode != CountMode.ONLY_COUNT && primaryKeyFirst(q, offset)) {
            // Before the count too, so a refused page runs no query at all (R-PAG-13).
            refuseToManySelection(q, q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL_BY_KEYS, renderOptions),
                    "primary-key-first paging");
        }
        switch (mode) {
            case ONLY_COUNT: {
                long total = count(q);
                return new Slice<>(List.of(), page.pageNumber(), size, offset + (long) size < total,
                        OptionalLong.of(total));
            }
            case COUNT: {
                long total = count(q);
                List<M> content = offset >= total ? List.of() : fetch(q, offset, size);
                return new Slice<>(content, page.pageNumber(), size, offset + (long) content.size() < total,
                        OptionalLong.of(total));
            }
            default: {
                // One row beyond the page tells whether another follows, so an exact multiple of the page size
                // does not report a next page that is empty (R-EXE-02).
                List<M> rows = fetch(q, offset, size == Integer.MAX_VALUE ? size : size + 1);
                boolean hasNext = rows.size() > size;
                return new Slice<>(hasNext ? rows.subList(0, size) : rows, page.pageNumber(), size, hasNext,
                        OptionalLong.empty());
            }
        }
    }

    @Override
    public long count(ModelQuery<E, ?, ?> q) {
        Objects.requireNonNull(q, "q");
        checkPhasesOnce(q);
        return countRows(q);
    }

    @Override
    public <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(pageTransformer, "pageTransformer");
        Objects.requireNonNull(sink, "sink");
        checkPhasesOnce(q);
        long limit = options.limit().maxRows().isPresent() ? options.limit().maxRows().getAsInt() : Long.MAX_VALUE;
        int pageSize = options.pageSize().orElse(exportPageSize);
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
        return exportByOffset(q, built, key, row -> keyOf(q, key, row), pageSize, limit, pageTransformer,
                sink);
    }

    @Override
    public long update(ModelUpdate<E, ?> u) {
        Objects.requireNonNull(u, "u");
        checkWriteOnce(u, u::checkMetamodel);
        if (u.writesNothing()) {
            return 0;
        }
        int written = execute(em.createQuery(u.buildWrite(em.getCriteriaBuilder(), renderOptions,
                (target, id) -> em.getReference(target, id))));
        if (written == 0 && u.expectedVersion().isPresent()) {
            throw new OptimisticLockException(rootEntity.getSimpleName() + ": no row was written with version "
                    + u.expectedVersion().get() + "; its version moved, or it no longer exists or matches");
        }
        return written;
    }

    @Override
    public long delete(ModelDelete<E, ?> d) {
        Objects.requireNonNull(d, "d");
        checkWriteOnce(d, d::checkMetamodel);
        if (d.writesNothing()) {
            return 0;
        }
        return execute(em.createQuery(d.buildWrite(em.getCriteriaBuilder(), renderOptions)));
    }

    /** Runs a write statement with the configured timeout applied through the profile (R-EXE-11). */
    private int execute(Query statement) {
        queryTimeout.ifPresent(timeout -> vendor.profile().applyTimeout(statement, timeout));
        return statement.executeUpdate();
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

    /** Runs the R-QRY-09 phase check the first time any executor runs {@code q} (D-21). */
    private void checkPhasesOnce(ModelQuery<E, ?, ?> q) {
        // An orderedBy copy is covered by the check of its definition, so a per-request sort does not repeat it.
        ModelQuery<E, ?, ?> definition = q.definition();
        if (!PHASES_CHECKED.contains(definition)) {
            q.checkPhases(em.getCriteriaBuilder());
            PHASES_CHECKED.add(definition); // only once it passed, so a check that threw runs again next time
        }
    }

    // ---- page content

    private <M> List<M> fetch(ModelQuery<E, ?, M> q, int offset, int maxRows) {
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
        TypedQuery<Tuple> query = create(built.query());
        query.setFirstResult(offset);
        query.setMaxResults(maxRows);
        return mapAll(query, built);
    }

    /**
     * Offset pages of an order with ties overlap or skip rows, so the primary key (the group keys of a grouped query)
     * closes the order (api/11 §5, R-PAG-01, R-PAG-11).
     */
    private <M> void appendStableOrder(ModelQuery<E, ?, M> q, BuiltQuery<M> built) {
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
        TypedQuery<Tuple> query = create(keyQuery.query());
        query.setFirstResult(offset);
        query.setMaxResults(maxRows);
        List<Tuple> rows = query.getResultList();
        List<Object> keys = new ArrayList<>(rows.size());
        for (Tuple tuple : rows) {
            keys.add(keyOf(q, key, keyQuery.selection().row(tuple)));
        }
        return keys;
    }

    /**
     * Step 2: the models of {@code keys}, one per position in {@code keys}, in that order. Each statement takes at
     * most {@code batch} keys, with the query's own predicate and order (R-PAG-07, R-PAG-08). {@code IN}
     * keeps neither the step-1 order nor the order across statements, so rows are placed by their key's position. A
     * key whose row no longer matches between the two steps is skipped.
     */
    private <M> List<M> readByKeys(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key, List<Object> keys, int batch) {
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
            Predicate byKey = keyIn(key, batchKeys, built.joins(), cb);
            Predicate own = built.query().getRestriction();
            built.query().where(own == null ? byKey : cb.and(own, byKey));
            appendStableOrder(q, built);
            for (Tuple tuple : create(built.query()).getResultList()) {
                found.putIfAbsent(keyOf(q, key, built.selection().row(tuple)), new Found<>(built, tuple));
            }
        }
        List<M> models = new ArrayList<>(keys.size());
        for (Object rowKey : keys) {
            Found<M> row = found.get(rowKey);
            if (row != null) {
                models.add(row.built().map(row.tuple())); // mapped per position, so afterMap runs once per row
            }
        }
        return models;
    }

    /** A step-2 row, with the statement that read it. */
    private record Found<M>(BuiltQuery<M> built, Tuple tuple) {}

    /**
     * The most keys one step-2 statement takes: the configured batch size, if any, within the profile's IN-list
     * limit, and within its bind-parameter limit once the statement's own binds are bound, at one bind per key column
     * (R-PAG-07, D-32). At least one, so a query that alone passes the bind limit fails in the database as its
     * one-step page would. It compiles a statement without running it, so a caller computes it once per {@code page}
     * or {@code export} call.
     */
    private <M> int keyBatchSize(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key) {
        // The statement's own binds, the query's values and the customizer's, before any key is added. JPA reports a
        // literal the provider binds as a parameter of the query; one it renders inline takes no bind.
        int ownBinds = em.createQuery(q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL_BY_KEYS, renderOptions).query())
                .getParameters().size();
        int clamp = Math.min(renderOptions.maxInListSize(),
                (renderOptions.maxBindParameters() - ownBinds) / key.columns().size());
        return Math.max(1, Math.min(primaryKeyFirstBatchSize.orElse(Integer.MAX_VALUE), clamp));
    }

    /** {@code key IN (keys)}; a composite key is an OR of per-key conjunctions, since JPA has no row-value IN (P-4). */
    private static <M> Predicate keyIn(PrimaryKey<M, ?> key, List<Object> keys, JoinContext joins, CriteriaBuilder cb) {
        List<ColumnField<M, ?, ?>> columns = key.columns();
        if (columns.size() == 1) {
            return columns.get(0).path(joins).in(keys);
        }
        Predicate[] each = new Predicate[keys.size()];
        for (int i = 0; i < each.length; i++) {
            List<?> values = (List<?>) keys.get(i);
            Predicate[] equal = new Predicate[columns.size()];
            for (int c = 0; c < equal.length; c++) {
                equal[c] = cb.equal(columns.get(c).path(joins), values.get(c));
            }
            each[i] = cb.and(equal);
        }
        return cb.or(each);
    }

    private static <M> List<M> mapAll(TypedQuery<Tuple> query, BuiltQuery<M> built) {
        List<Tuple> tuples = query.getResultList();
        var models = new ArrayList<M>(tuples.size());
        for (Tuple tuple : tuples) {
            models.add(built.map(tuple));
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
            List<M> fresh;
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
                TypedQuery<Tuple> query = create(built.query());
                query.setFirstResult(offset);
                query.setMaxResults(pageSize);
                List<Tuple> rows = query.getResultList();
                fresh = new ArrayList<>(rows.size());
                for (Tuple tuple : rows) {
                    Object rowKey = keyOfRow.apply(built.selection().row(tuple));
                    if (isFresh(keys, previousKeys, rowKey)) {
                        fresh.add(built.map(tuple));
                    }
                }
                read = rows.size();
            }
            passed = pass(fresh, passed, limit, pageTransformer, sink);
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
            if (cursor != null) {
                built = q.buildQuery(cb, Phase.MODEL, renderOptions);
                Predicate after = keyset.after(cursor, built.joins(), cb);
                Predicate own = built.query().getRestriction();
                built.query().where(own == null ? after : cb.and(own, after));
            }
            keyset.appendOrder(built, cb);
            TypedQuery<Tuple> query = create(built.query());
            query.setMaxResults(pageSize);
            List<Tuple> rows = query.getResultList();
            Set<Object> keys = new HashSet<>();
            List<M> fresh = new ArrayList<>(rows.size());
            for (Tuple tuple : rows) {
                Row row = built.selection().row(tuple);
                Object rowKey = keyOf(q, key, row);
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
                    fresh.add(built.map(tuple));
                }
            }
            passed = pass(fresh, passed, limit, pageTransformer, sink);
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
     * The row's primary key, read from the {@code Row} so it works for any model (R-PAG-03): the value of a
     * single-column key, the list of values of a composite one. Each is the attribute's value, before any converter,
     * so two keys a converter maps to one model value stay two keys (R-COL-11).
     *
     * @throws ModelQueryExecutionException {@code MQ2201} when a key column is {@code null}
     */
    private static <M> Object keyOf(ModelQuery<?, ?, M> q, PrimaryKey<M, ?> key, Row row) {
        List<ColumnField<M, ?, ?>> columns = key.columns();
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.raw(columns.get(i));
            if (values[i] == null) {
                // A null key cannot tell this row from another, so the boundary dedupe could drop or keep it wrongly,
                // and step 2 of primary-key-first paging could not read the row back.
                throw new ModelQueryExecutionException(MqCode.MQ2201, q + ": primary-key column "
                        + columns.get(i).name() + " is null in a row; export and primary-key-first paging need a key "
                        + "that identifies every row");
            }
        }
        return values.length == 1 ? values[0] : List.of(values);
    }

    /**
     * The row's group: its group-by values in {@code groupBy} order, unique per row of a grouped query (R-PAG-11). SQL
     * groups NULLs together, so a NULL is a value of the tuple like any other; a whole-table aggregate's is empty.
     */
    private static <M> Object groupKeyOf(ModelQuery<?, ?, M> q, Row row) {
        List<ColumnField<M, ?, ?>> groupBy = q.groupBy();
        Object[] values = new Object[groupBy.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.raw(groupBy.get(i));
        }
        return Arrays.asList(values);
    }

    /**
     * Throws {@code MQ2204} when a column of {@code built}'s selection is read through a to-many join, before any
     * query runs: one primary key then spans several rows, so the stable order is not unique and the boundary dedupe
     * would drop real rows (R-PAG-13).
     */
    private static void refuseToManySelection(ModelQuery<?, ?, ?> q, BuiltQuery<?> built, String operation) {
        for (Selection<?> selection : built.query().getSelection().getCompoundSelectionItems()) {
            Join<?, ?> join = toManyJoin(selection);
            if (join != null) {
                Attribute<?, ?> attribute = join.getAttribute();
                throw new ModelQueryExecutionException(MqCode.MQ2204, q + ": " + operation + " selects a column "
                        + "through the to-many join " + attribute.getDeclaringType().getJavaType().getSimpleName() + "."
                        + attribute.getName() + ", so one primary key spans several rows; select from the child side, "
                        + "or filter with Filters.exists(...)");
            }
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
        boolean readThroughToMany = query.getSelection().getCompoundSelectionItems().stream()
                .anyMatch(selection -> toManyJoin(selection) != null);
        Expression<Long> count = hasToManyJoin(root) && !readThroughToMany ? cb.countDistinct(root) : cb.count(root);
        query.multiselect(count);
        return ((Number) create(query).getSingleResult().get(0)).longValue();
    }

    /** {@code count(*)} over the groups: in the database when the provider support can, else client-side. */
    private <M> long countGroups(ModelQuery<E, ?, M> q, BuiltQuery<M> built) {
        CriteriaQuery<Tuple> query = built.query();
        if (!q.groupBy().isEmpty()) {
            // The group keys identify a group, and selecting only them is what the count needs (R-EXE-05).
            List<Selection<?>> keys = new ArrayList<>();
            for (ColumnField<M, ?, ?> key : q.groupBy()) {
                keys.add(key.path(built.joins()));
            }
            query.multiselect(keys);
        }
        // The provider support only builds the count; it runs here, so the configured timeout applies (R-EXE-11).
        Optional<CriteriaQuery<Long>> count = vendor.providerSupport().flatMap(p -> p.countQuery(query));
        if (count.isPresent()) {
            return create(count.get()).getSingleResult();
        }
        LOG.log(System.Logger.Level.WARNING, "count over the grouped query on {0} runs it and counts its rows in "
                + "memory, because no ProviderSupport counts groups for this persistence provider; add "
                + "model-query-hibernate for a count in the database (R-EXE-03)", rootEntity.getSimpleName());
        return create(query).getResultList().size();
    }

    private static boolean hasToManyJoin(From<?, ?> from) {
        for (Join<?, ?> join : from.getJoins()) {
            if (join.getAttribute() instanceof PluralAttribute<?, ?, ?> || hasToManyJoin(join)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The nearest to-many join {@code selection} is read through, or {@code null}: under one, each joined row is a
     * result, which {@code count} counts (R-EXE-04) and key-based paging refuses (R-PAG-13).
     */
    private static Join<?, ?> toManyJoin(Selection<?> selection) {
        for (Path<?> path = selection instanceof Path<?> p ? p : null; path != null; path = path.getParentPath()) {
            if (path instanceof Join<?, ?> join && join.getAttribute() instanceof PluralAttribute<?, ?, ?>) {
                return join;
            }
        }
        return null;
    }
}
