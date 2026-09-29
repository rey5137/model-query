package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfig;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.jpa.spi.GroupedCountStrategy;
import jakarta.persistence.EntityManager;
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
import jakarta.persistence.metamodel.PluralAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The executor {@link ModelQueryExecutor#create} returns.
 *
 * @implSpec R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04, R-EXE-05, R-EXE-06, R-EXE-07, R-EXE-09, R-QRY-09, R-PAG-01,
 *     R-PAG-02, R-PAG-03, R-PAG-04, R-PAG-05, R-PAG-06, R-PAG-07, R-PAG-08, R-PAG-09, R-PAG-10, R-PAG-13
 */
final class DefaultModelQueryExecutor<E> implements ModelQueryExecutor<E> {

    private static final System.Logger LOG = System.getLogger(DefaultModelQueryExecutor.class.getName());

    /**
     * The most keys, and bind parameters, one {@code MODEL_BY_KEYS} statement takes: the lowest of the Tier-1 limits
     * in vendor/41 §2, until the clamp reads {@code VendorProfile.maxInListSize()} and {@code maxBindParameters()}
     * (R-PAG-07, D-32).
     */
    private static final int MAX_IN_LIST_SIZE = 10_000;
    private static final int MAX_BIND_PARAMETERS = 65_535;

    /**
     * The queries whose phases were checked, by identity. Static, because the check is once per {@code ModelQuery}
     * whichever executor runs it first (D-21); weak, so a query built per request does not stay reachable.
     */
    private static final Set<ModelQuery<?, ?, ?>> PHASES_CHECKED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private final EntityManager em;
    private final Class<E> rootEntity;
    private final List<GroupedCountStrategy> groupedCounters;

    DefaultModelQueryExecutor(EntityManager em, Class<E> rootEntity, ModelQueryConfig config) {
        this.em = Objects.requireNonNull(em, "em");
        this.rootEntity = Objects.requireNonNull(rootEntity, "rootEntity");
        Objects.requireNonNull(config, "config");
        var found = new ArrayList<GroupedCountStrategy>();
        ServiceLoader.load(GroupedCountStrategy.class).forEach(found::add);
        this.groupedCounters = List.copyOf(found);
    }

    @Override
    public <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkPhasesOnce(q);
        if (limit.maxRows().isPresent() && limit.maxRows().getAsInt() == 0) {
            return List.of(); // no statement runs for a zero limit (R-EXE-06)
        }
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
        TypedQuery<Tuple> query = em.createQuery(built.query());
        limit.maxRows().ifPresent(query::setMaxResults);
        return mapAll(query, built);
    }

    @Override
    public <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(body, "body");
        checkPhasesOnce(q);
        if (limit.maxRows().isPresent() && limit.maxRows().getAsInt() == 0) {
            try (Stream<M> none = Stream.empty()) {
                return body.apply(none); // no statement runs for a zero limit (R-EXE-06)
            }
        }
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
        TypedQuery<Tuple> query = em.createQuery(built.query());
        limit.maxRows().ifPresent(query::setMaxResults);
        // Rows are mapped one at a time as body pulls them; closing the mapped stream closes the result stream under
        // it, whether body returns, stops early or throws (R-EXE-07, R-EXE-09).
        try (Stream<Tuple> tuples = query.getResultStream(); Stream<M> models = tuples.map(built::map)) {
            return body.apply(models);
        }
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
            refuseToManySelection(q, q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL_BY_KEYS),
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
        if (q.isGrouped()) {
            throw new UnsupportedOperationException(q + ": grouped export is not implemented yet");
        }
        // A keyset query always has a primary key (MQ1201 at build time), so only offset export can fail here.
        PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(() -> new ModelQueryExecutionException(MqCode.MQ2203,
                q + ": offset export needs a primary key to order and dedupe its pages, and primaryKey(...) was not set"));
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
        refuseToManySelection(q, built, q.isKeyset() ? "keyset paging" : "offset export");
        long limit = options.limit().maxRows().isPresent() ? options.limit().maxRows().getAsInt() : Long.MAX_VALUE;
        if (q.isKeyset()) {
            return exportByKeyset(q, built, key, options.pageSize(), limit, pageTransformer, sink);
        }
        appendStableOrder(q, built);
        return exportByOffset(q, built, key, options.pageSize(), limit, pageTransformer, sink);
    }
    /** Runs the R-QRY-09 phase check the first time any executor runs {@code q} (D-21). */
    private void checkPhasesOnce(ModelQuery<E, ?, ?> q) {
        if (!PHASES_CHECKED.contains(q)) {
            q.checkPhases(em.getCriteriaBuilder());
            PHASES_CHECKED.add(q); // only once it passed, so a check that threw runs again next time
        }
    }

    // ---- page content

    private <M> List<M> fetch(ModelQuery<E, ?, M> q, int offset, int maxRows) {
        if (primaryKeyFirst(q, offset)) {
            PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(); // primaryKeyFirst(...) needs one (MQ1201)
            // Read the page's keys in the stable order, then their rows: the same rows as the one-step page, a key
            // read twice through a predicate's to-many join included.
            return readByKeys(q, key, readKeys(q, key, keyQuery(q), offset, maxRows));
        }
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
        appendStableOrder(q, built);
        TypedQuery<Tuple> query = em.createQuery(built.query());
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
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.PRIMARY_KEY);
        appendStableOrder(q, built);
        return built;
    }

    /** Step 1: the primary keys of {@code maxRows} rows from {@code offset}, in {@code keyQuery}'s order. */
    private <M> List<Object> readKeys(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key, BuiltQuery<M> keyQuery,
            int offset, int maxRows) {
        TypedQuery<Tuple> query = em.createQuery(keyQuery.query());
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
     * most {@link #keyBatchSize} keys, with the query's own predicate and order (R-PAG-07, R-PAG-08). {@code IN}
     * keeps neither the step-1 order nor the order across statements, so rows are placed by their key's position. A
     * key whose row no longer matches between the two steps is skipped.
     */
    private <M> List<M> readByKeys(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key, List<Object> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // A predicate's to-many join repeats a key in step 1; its row is read once and placed at every position.
        List<Object> distinct = List.copyOf(new LinkedHashSet<>(keys));
        // The statement's own binds, the query's values and the customizer's, before any key is added. JPA reports a
        // literal the provider binds as a parameter of the query; one it renders inline takes no bind.
        int ownBinds = em.createQuery(q.buildQuery(cb, Phase.MODEL_BY_KEYS).query()).getParameters().size();
        int batch = keyBatchSize(key, ownBinds);
        Map<Object, Found<M>> found = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += batch) {
            List<Object> batchKeys = distinct.subList(from, Math.min(distinct.size(), from + batch));
            BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL_BY_KEYS);
            Predicate byKey = keyIn(key, batchKeys, built.joins(), cb);
            Predicate own = built.query().getRestriction();
            built.query().where(own == null ? byKey : cb.and(own, byKey));
            appendStableOrder(q, built);
            for (Tuple tuple : em.createQuery(built.query()).getResultList()) {
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
     * The most keys one step-2 statement takes: within the IN-list limit, and within the bind-parameter limit once
     * the statement's own {@code ownBinds} are bound, at one bind per key column (R-PAG-07, D-32). At least one, so a
     * query that alone passes the bind limit fails in the database as its one-step page would.
     */
    private static int keyBatchSize(PrimaryKey<?, ?> key, int ownBinds) {
        return Math.max(1, Math.min(MAX_IN_LIST_SIZE, (MAX_BIND_PARAMETERS - ownBinds) / key.columns().size()));
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
     * The export loop of engine/21 §4 in offset mode; {@code built} is already in its stable order. A page past the
     * {@code primaryKeyFirst} offset reads its keys first and only the fresh keys' rows (R-PAG-07).
     */
    private <M, S> long exportByOffset(ModelQuery<E, ?, M> q, BuiltQuery<M> built, PrimaryKey<M, ?> key,
            int pageSize, long limit, Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        long passed = 0;
        int offset = 0;
        BuiltQuery<M> keyQuery = null;
        // The only state carried from page to page, so memory stays bounded by one page (R-PAG-02, INV-4).
        Set<Object> previousKeys = Set.of();
        while (passed < limit) {
            Set<Object> keys = new HashSet<>();
            List<M> fresh;
            int read;
            // A key seen before is a row the stable order already delivered: across the boundary when rows shifted
            // between pages, or within the page when a predicate's to-many join repeats the root.
            if (primaryKeyFirst(q, offset)) {
                keyQuery = keyQuery == null ? keyQuery(q) : keyQuery;
                List<Object> pageKeys = readKeys(q, key, keyQuery, offset, pageSize);
                List<Object> freshKeys = new ArrayList<>(pageKeys.size());
                for (Object rowKey : pageKeys) {
                    if (keys.add(rowKey) && !previousKeys.contains(rowKey)) {
                        freshKeys.add(rowKey);
                    }
                }
                fresh = readByKeys(q, key, freshKeys); // only the fresh keys' rows are read (R-PAG-07)
                read = pageKeys.size();
            } else {
                TypedQuery<Tuple> query = em.createQuery(built.query());
                query.setFirstResult(offset);
                query.setMaxResults(pageSize);
                List<Tuple> rows = query.getResultList();
                fresh = new ArrayList<>(rows.size());
                for (Tuple tuple : rows) {
                    Object rowKey = keyOf(q, key, built.selection().row(tuple));
                    if (keys.add(rowKey) && !previousKeys.contains(rowKey)) {
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
                        + Integer.MAX_VALUE + " rows, the largest offset JPA takes; use keyset() for a result this large");
            }
            offset += read;
        }
        return passed;
    }

    /**
     * The export loop of engine/21 §4 in keyset mode: each page is the rows after the last row of the page before, so
     * no offset is skipped and no page overlaps the last (R-PAG-04, R-PAG-06). {@code first} is the unrestricted first
     * page's statement; each later page is built afresh with the keyset predicate added to the query's own.
     */
    private <M, S> long exportByKeyset(ModelQuery<E, ?, M> q, BuiltQuery<M> first, PrimaryKey<M, ?> key,
            int pageSize, long limit, Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        Keyset<M> keyset = Keyset.of(q, key);
        long passed = 0;
        BuiltQuery<M> built = first;
        // The last row's keyset values: the only state carried from page to page (INV-4).
        Object[] cursor = null;
        while (passed < limit) {
            if (cursor != null) {
                built = q.buildQuery(cb, Phase.MODEL);
                Predicate after = keyset.after(cursor, built.joins(), cb);
                Predicate own = built.query().getRestriction();
                built.query().where(own == null ? after : cb.and(own, after));
            }
            keyset.appendOrder(built, cb);
            TypedQuery<Tuple> query = em.createQuery(built.query());
            query.setMaxResults(pageSize);
            List<Tuple> rows = query.getResultList();
            Set<Object> keys = new HashSet<>();
            List<M> fresh = new ArrayList<>(rows.size());
            for (Tuple tuple : rows) {
                Row row = built.selection().row(tuple);
                Object rowKey = keyOf(q, key, row);
                // Read from every row, not only the last, so a NULL key is refused wherever the page holds it.
                cursor = keyset.cursor(row);
                // Equal keys sort together and the cursor is past them all, so a key repeats only within a page,
                // when a predicate's to-many join repeats the root (R-PAG-02).
                if (keys.add(rowKey)) {
                    fresh.add(built.map(tuple));
                }
            }
            passed = pass(fresh, passed, limit, pageTransformer, sink);
            if (rows.size() < pageSize) {
                break;
            }
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
     * single-column key, the list of values of a composite one.
     *
     * @throws ModelQueryExecutionException {@code MQ2201} when a key column is {@code null}
     */
    private static <M> Object keyOf(ModelQuery<?, ?, M> q, PrimaryKey<M, ?> key, Row row) {
        List<ColumnField<M, ?, ?>> columns = key.columns();
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.get(columns.get(i));
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
        BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL);
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
        return ((Number) em.createQuery(query).getSingleResult().get(0)).longValue();
    }

    /** {@code count(*)} over the groups: in the database when a strategy serves the provider, else client-side. */
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
        TypedQuery<Tuple> typed = em.createQuery(query);
        for (GroupedCountStrategy strategy : groupedCounters) {
            OptionalLong groups = strategy.countGroups(typed);
            if (groups.isPresent()) {
                return groups.getAsLong();
            }
        }
        LOG.log(System.Logger.Level.WARNING, "count over the grouped query on {0} runs it and counts its rows in "
                + "memory, because no GroupedCountStrategy serves this persistence provider; add "
                + "model-query-hibernate for a count in the database (R-EXE-03)", rootEntity.getSimpleName());
        return typed.getResultList().size();
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
