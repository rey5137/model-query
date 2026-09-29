package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
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
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.PluralAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
 *     R-PAG-02, R-PAG-03, R-PAG-09, R-PAG-10, R-PAG-13
 */
final class DefaultModelQueryExecutor<E> implements ModelQueryExecutor<E> {

    private static final System.Logger LOG = System.getLogger(DefaultModelQueryExecutor.class.getName());

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
        if (q.isGrouped() || q.isKeyset()) {
            throw new UnsupportedOperationException(q + ": " + (q.isGrouped() ? "grouped" : "keyset")
                    + " export is not implemented yet; only ungrouped offset export is");
        }
        PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(() -> new ModelQueryExecutionException(MqCode.MQ2203,
                q + ": offset export needs a primary key to order and dedupe its pages, and primaryKey(...) was not set"));
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
        refuseToManySelection(q, built, "offset export");
        appendStableOrder(q, built);
        long limit = options.limit().maxRows().isPresent() ? options.limit().maxRows().getAsInt() : Long.MAX_VALUE;
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

    private static <M> List<M> mapAll(TypedQuery<Tuple> query, BuiltQuery<M> built) {
        List<Tuple> tuples = query.getResultList();
        var models = new ArrayList<M>(tuples.size());
        for (Tuple tuple : tuples) {
            models.add(built.map(tuple));
        }
        return models;
    }

    // ---- export

    /** The export loop of engine/21 §4 in offset mode; {@code built} is already in its stable order. */
    private <M, S> long exportByOffset(ModelQuery<E, ?, M> q, BuiltQuery<M> built, PrimaryKey<M, ?> key,
            int pageSize, long limit, Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        long passed = 0;
        int offset = 0;
        // The only state carried from page to page, so memory stays bounded by one page (R-PAG-02, INV-4).
        Set<Object> previousKeys = Set.of();
        while (passed < limit) {
            TypedQuery<Tuple> query = em.createQuery(built.query());
            query.setFirstResult(offset);
            query.setMaxResults(pageSize);
            List<Tuple> rows = query.getResultList();
            Set<Object> keys = new HashSet<>();
            List<M> fresh = new ArrayList<>(rows.size());
            for (Tuple tuple : rows) {
                Object rowKey = keyOf(q, key, built.selection().row(tuple));
                // A key seen before is a row the stable order already delivered: across the boundary when rows
                // shifted between pages, or within the page when a predicate's to-many join repeats the root.
                if (keys.add(rowKey) && !previousKeys.contains(rowKey)) {
                    fresh.add(built.map(tuple));
                }
            }
            if (!fresh.isEmpty()) {
                List<S> items = Objects.requireNonNull(pageTransformer.apply(fresh), "pageTransformer result");
                for (int i = 0; i < items.size() && passed < limit; i++) {
                    sink.accept(items.get(i));
                    passed++;
                }
            }
            if (rows.size() < pageSize) {
                break;
            }
            previousKeys = keys;
            if (offset > Integer.MAX_VALUE - rows.size()) {
                throw new ModelQueryExecutionException(MqCode.MQ2002, q + ": offset export went past "
                        + Integer.MAX_VALUE + " rows, the largest offset JPA takes; use keyset() for a result this large");
            }
            offset += rows.size();
        }
        return passed;
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
                // A null key cannot tell this row from another, so the boundary dedupe could drop or keep it wrongly.
                throw new ModelQueryExecutionException(MqCode.MQ2201, q + ": primary-key column "
                        + columns.get(i).name() + " is null in an exported row; the key must identify every row");
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
