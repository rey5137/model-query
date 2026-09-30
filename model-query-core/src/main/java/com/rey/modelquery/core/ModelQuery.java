package com.rey.modelquery.core;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * An immutable query definition: what to select, how to order and page it, and how to map a row to a model. It holds
 * no Criteria object and no per-query state, so one instance can be a {@code static final} constant shared by any
 * number of threads (INV-9). Everything resolved against a {@code CriteriaBuilder} lives in a {@link BuiltQuery}
 * created per call to {@link #buildQuery}.
 *
 * @param <E> the root entity
 * @param <K> the primary-key type; {@code Object} when the query defines no key
 * @param <M> the model
 * @implSpec R-QRY-01, R-QRY-02
 */
@Incubating
public final class ModelQuery<E, K, M> {

    private static final System.Logger LOG = System.getLogger(ModelQuery.class.getName());

    /** Options that never chunk nor refuse a list, for scratch builds whose SQL is never run. */
    private static final RenderOptions UNLIMITED =
            RenderOptions.of(Integer.MAX_VALUE, Integer.MAX_VALUE);

    private final TableField<E, E> root;
    private final RowMapper<M> mapper;
    private final ColumnSet<M> columns;
    private final PrimaryKey<M, K> primaryKey;
    private final List<OrderField<M, ?>> orderBy;
    private final boolean keyset;
    private final PrimaryKeyFirst primaryKeyFirst;
    private final BiConsumer<M, Row> afterMap;
    private final UnaryOperator<M> finisher;
    private final QueryCustomizer customizer;
    private final List<Filter> where;
    private final List<ColumnField<M, ?, ?>> groupBy;
    private final List<Filter> having;
    private final boolean grouped;
    private final QuerySpec spec;
    /** What {@code MODEL} and {@code MODEL_BY_KEYS} select, and what {@code PRIMARY_KEY} selects; fixed per query. */
    private final List<SelectField<M, ?>> modelColumns;
    private final RowSelection modelSelection;
    private final RowSelection keySelection;
    private final Function<Row, M> mapping = this::toModel;

    private ModelQuery(Builder<E, K, M> b, boolean grouped) {
        this.root = b.root;
        this.mapper = b.mapper;
        this.columns = b.columns;
        // A group has no row identity, so a grouped query runs without its key (R-AGG-09).
        this.primaryKey = grouped ? null : b.primaryKey;
        this.orderBy = b.orderBy;
        this.keyset = b.keyset;
        this.primaryKeyFirst = b.primaryKeyFirst;
        this.afterMap = b.afterMap;
        this.finisher = b.finisher;
        this.customizer = b.customizer;
        this.where = b.where;
        this.groupBy = b.groupBy;
        this.having = b.having == null ? List.of() : b.having.filters();
        this.grouped = grouped;
        this.spec = new Spec(root.rootEntity(), List.copyOf(columns.columns()),
                Optional.ofNullable(primaryKey), List.copyOf(orderBy), keyset, List.copyOf(groupBy), grouped);
        this.modelColumns = List.copyOf(selected());
        this.modelSelection = RowSelection.of(modelColumns);
        this.keySelection = primaryKey == null ? null : RowSelection.of(primaryKey.columns());
    }

    /**
     * Starts a definition rooted at {@code root}, mapped with {@code mapper}. Only {@link Builder#columns} is also
     * required; every other part has a defined behaviour when absent (spec api/11 §5).
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join, not a root
     */
    public static <E, M> Builder<E, Object, M> builder(TableField<E, E> root, RowMapper<M> mapper) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(mapper, "mapper");
        if (root.rootEntity() == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1203,
                    "builder(...) takes a TableField.root(...), not the " + root.describe());
        }
        return new Builder<>(root, mapper, null, null, List.of(), false, null, null, null, null, List.of(), List.of(),
                null);
    }

    /** The entity the query is rooted at. */
    public Class<E> rootEntity() {
        return root.rootEntity();
    }

    /** The columns selected for the model, without any primary key added for paging. */
    public ColumnSet<M> columns() {
        return columns;
    }

    /** The primary key, when defined; always empty for a grouped query, which ignores one (R-AGG-09). */
    public Optional<PrimaryKey<M, K>> primaryKey() {
        return Optional.ofNullable(primaryKey);
    }

    /** The ordering keys, in order, as a list that throws on mutation. */
    public List<OrderField<M, ?>> orderBy() {
        return orderBy;
    }

    /** Whether keyset paging is allowed. */
    public boolean isKeyset() {
        return keyset;
    }

    /** The two-step deep-paging rule, when defined. */
    public Optional<PrimaryKeyFirst> primaryKeyFirst() {
        return Optional.ofNullable(primaryKeyFirst);
    }

    /** Whether the query is grouped: it has a group-by or selects an aggregate (R-AGG-07). */
    @Incubating
    public boolean isGrouped() {
        return grouped;
    }

    /** The group-by columns in order, as a list that throws on mutation; empty when there is no group-by. */
    @Incubating
    public List<ColumnField<M, ?, ?>> groupBy() {
        return groupBy;
    }

    /** The read-only view a {@link QueryCustomizer} receives. */
    public QuerySpec spec() {
        return spec;
    }

    /**
     * Resolves the statement of {@code phase} against {@code cb}. Every phase has the same joins, predicate, grouping
     * and ordering, and only the SELECT list differs (R-QRY-09, D-26). {@code MODEL} and {@code MODEL_BY_KEYS} select
     * the columns plus, where the {@code ColumnSet} omits them, the primary-key columns of an ungrouped query, every
     * ordering key and every group key, so an executor can read them from the row (R-QRY-04, D-29);
     * {@code PRIMARY_KEY} selects the key columns only. The customizer, if any, runs last. A new {@link JoinContext}
     * is created per call and carries {@code options}, the facts about the target database the build renders by
     * ({@link RenderOptions#portable()} when unknown, D-34).
     *
     * @throws ModelQueryExecutionException {@code MQ2203} for {@code PRIMARY_KEY} on a query without a primary key
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changed the ordering or the grouping
     */
    public BuiltQuery<M> buildQuery(CriteriaBuilder cb, Phase phase, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(options, "options");
        BuiltQuery<M> built = assemble(cb, phase, options);
        if (customizer != null) {
            customize(built, cb, phase);
        }
        return built;
    }

    /**
     * Runs the customizer on {@code built}, which must leave its ordering and grouping as they are: the executor
     * orders, dedupes and reads cursors by the definition's {@code orderBy} and {@code groupBy}, so a change it
     * cannot see makes paging skip or repeat rows (R-QRY-11, D-33).
     *
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changed the ordering or the grouping
     */
    private void customize(BuiltQuery<M> built, CriteriaBuilder cb, Phase phase) {
        CriteriaQuery<Tuple> query = built.query();
        List<Order> orders = List.copyOf(query.getOrderList());
        List<Expression<?>> groups = List.copyOf(query.getGroupList());
        customizer.customize(spec, built.joins(), query, cb, phase);
        boolean reordered = !orders.equals(query.getOrderList());
        if (reordered || !groups.equals(query.getGroupList())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1205, modelName() + ": the QueryCustomizer changed the "
                    + (reordered ? "ORDER BY" : "GROUP BY") + " of phase " + phase + "; order and group the query "
                    + "with orderBy(...) and groupBy(...), which paging and export follow");
        }
    }

    /** Selection, predicate, grouping and ordering of {@code phase}, before any customizer runs. */
    private BuiltQuery<M> assemble(CriteriaBuilder cb, Phase phase, RenderOptions options) {
        if (phase == Phase.PRIMARY_KEY && primaryKey == null) {
            throw new ModelQueryExecutionException(MqCode.MQ2203, modelName() + ": phase PRIMARY_KEY needs a primary "
                    + "key, and " + (grouped ? "a grouped query has none (R-AGG-09)" : "primaryKey(...) was not set"));
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> from = query.from(root.rootEntity());
        JoinContext joins = JoinContext.of(from, cb, query, options);
        // Joins resolve in one order in every phase: selection, ordering and grouping first, then every filter
        // outside or/not, then those inside. An or(...) then finds the INNER join the rest of the query needs and
        // reuses it, instead of joining the path LEFT and leaving the rest to join it again (R-FLT-10, D-26).
        RowSelection selection;
        if (phase == Phase.PRIMARY_KEY) {
            // Resolved for their joins only: a join the model's selection makes can remove rows, and primary-key-first
            // paging must page the rows MODEL returns (R-QRY-09).
            modelColumns.forEach(column -> column.expression(joins));
            selection = keySelection;
        } else {
            selection = modelSelection;
        }
        query.multiselect(selection.selections(joins));
        List<Order> orders = new ArrayList<>();
        for (OrderField<M, ?> order : orderBy) {
            orders.addAll(order.toOrders(joins, cb));
        }
        if (!orders.isEmpty()) {
            query.orderBy(orders);
        }
        if (!groupBy.isEmpty()) {
            query.groupBy(groupBy.stream().<Expression<?>>map(column -> column.path(joins)).toList());
        }
        List<List<Predicate>> predicates = ConditionGroup.clausePredicates(List.of(where, having), joins);
        if (!predicates.get(0).isEmpty()) {
            query.where(predicates.get(0).toArray(Predicate[]::new));
        }
        if (!predicates.get(1).isEmpty()) {
            query.having(predicates.get(1).toArray(Predicate[]::new));
        }
        return new BuiltQuery<>(query, joins, selection, mapping);
    }

    /**
     * Checks a customizer that narrows some phases but not all of them, by adding a predicate or a join other than
     * LEFT (R-QRY-09): it logs a warning, or throws on a query with {@code primaryKeyFirst(...)}, whose pages past
     * the threshold would then skip rows (R-PAG-15). It runs the customizer once per phase the query can run on a
     * scratch query built as {@link #buildQuery} builds it, and compares the predicate and the joins before and
     * after, so the query's own {@code where} and joins do not count. Call it once per ModelQuery, on first execution
     * (D-21).
     *
     * @throws ModelQueryExecutionException {@code MQ2206} when the phases disagree on a query with
     *     {@code primaryKeyFirst(...)}
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changes the ordering or the grouping
     *     of any phase
     */
    public void checkPhases(CriteriaBuilder cb) {
        Objects.requireNonNull(cb, "cb");
        if (customizer == null) {
            return;
        }
        // Without a primary key, as on every grouped query, only MODEL runs: no other phase has to agree with it, but
        // its ordering and grouping are still checked (R-QRY-11).
        List<Phase> phases = primaryKey == null ? List.of(Phase.MODEL) : List.of(Phase.values());
        List<Phase> with = new ArrayList<>();
        List<Phase> without = new ArrayList<>();
        for (Phase phase : phases) {
            // Which phases a customizer narrows does not depend on the database, so the scratch renders without
            // limits: the portable ones would refuse a long in(...) the resolved profile accepts (MQ1306).
            BuiltQuery<M> scratch = assemble(cb, phase, UNLIMITED);
            Predicate own = scratch.query().getRestriction();
            int joined = narrowingJoins(scratch.query());
            customize(scratch, cb, phase);
            boolean narrowed = scratch.query().getRestriction() != own || narrowingJoins(scratch.query()) != joined;
            (narrowed ? with : without).add(phase);
        }
        if (!with.isEmpty() && !without.isEmpty()) {
            if (primaryKeyFirst != null) {
                // Step 1 would page rows step 2 does not return, or the reverse, and the offset the pages share
                // shifts at the switch to key-first reads (R-PAG-15).
                throw new ModelQueryExecutionException(MqCode.MQ2206, modelName() + ": the QueryCustomizer adds a "
                        + "predicate or an INNER join only in phase(s) " + with + ", not in " + without + "; "
                        + "primaryKeyFirst(...) needs every phase to select the same rows");
            }
            LOG.log(System.Logger.Level.WARNING,
                    "QueryCustomizer on {0} adds a predicate or an INNER join only in phase(s) {1}, not in {2}; "
                            + "primary-key-first paging would return rows the phases disagree on",
                    root.rootEntity().getSimpleName(), with, without);
        }
    }

    /** The roots of {@code query} and every join below them that can remove rows, which is any but a LEFT join. */
    private static int narrowingJoins(CriteriaQuery<?> query) {
        int count = 0;
        for (Root<?> from : query.getRoots()) {
            count += 1 + narrowingJoins(from);
        }
        return count;
    }

    private static int narrowingJoins(From<?, ?> from) {
        int count = 0;
        for (Join<?, ?> join : from.getJoins()) {
            count += (join.getJoinType() == JoinType.LEFT ? 0 : 1) + narrowingJoins(join);
        }
        return count;
    }

    /**
     * What {@code MODEL} and {@code MODEL_BY_KEYS} select: the columns, the primary key of an ungrouped query, and
     * every ordering and group key. None of the additions changes which rows return, and their joins are made anyway;
     * selecting them lets an executor read a row's key, cursor and group from the row (R-QRY-04, D-29).
     */
    private List<SelectField<M, ?>> selected() {
        var result = new ArrayList<SelectField<M, ?>>(columns.columns());
        if (primaryKey != null) {
            result.addAll(primaryKey.columns());
        }
        orderBy.forEach(order -> result.add(order.column()));
        result.addAll(groupBy);
        return result;
    }

    private M toModel(Row row) {
        M model = mapper.map(row);
        if (afterMap != null) {
            afterMap.accept(model, row);
        }
        return finisher == null ? model : finisher.apply(model);
    }

    /** The model's simple name, the way a failure message names the query. */
    @Override
    public String toString() {
        return modelName();
    }

    private String modelName() {
        return Builder.modelName(columns, root.rootEntity());
    }

    private record Spec(Class<?> rootEntity, List<SelectField<?, ?>> columns, Optional<PrimaryKey<?, ?>> primaryKey,
            List<OrderField<?, ?>> orderBy, boolean keyset, List<ColumnField<?, ?, ?>> groupBy, boolean isGrouped)
            implements QuerySpec {}

    /**
     * Collects the parts of a {@link ModelQuery}. Immutable: every method returns a new builder, so one builder can
     * be the base of several queries without them affecting each other (R-QRY-01).
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the model
     */
    @Incubating
    public static final class Builder<E, K, M> {

        private final TableField<E, E> root;
        private final RowMapper<M> mapper;
        private final ColumnSet<M> columns;
        private final PrimaryKey<M, K> primaryKey;
        private final List<OrderField<M, ?>> orderBy;
        private final boolean keyset;
        private final PrimaryKeyFirst primaryKeyFirst;
        private final BiConsumer<M, Row> afterMap;
        private final UnaryOperator<M> finisher;
        private final QueryCustomizer customizer;
        private final List<Filter> where;
        private final List<ColumnField<M, ?, ?>> groupBy;
        private final HavingGroup.Clause having;

        private Builder(TableField<E, E> root, RowMapper<M> mapper, ColumnSet<M> columns,
                PrimaryKey<M, K> primaryKey, List<OrderField<M, ?>> orderBy, boolean keyset,
                PrimaryKeyFirst primaryKeyFirst, BiConsumer<M, Row> afterMap, UnaryOperator<M> finisher,
                QueryCustomizer customizer, List<Filter> where, List<ColumnField<M, ?, ?>> groupBy,
                HavingGroup.Clause having) {
            this.root = root;
            this.mapper = mapper;
            this.columns = columns;
            this.primaryKey = primaryKey;
            this.orderBy = orderBy;
            this.keyset = keyset;
            this.primaryKeyFirst = primaryKeyFirst;
            this.afterMap = afterMap;
            this.finisher = finisher;
            this.customizer = customizer;
            this.where = where;
            this.groupBy = groupBy;
            this.having = having;
        }

        /** The columns to select; required. */
        public Builder<E, K, M> columns(ColumnSet<M> columns) {
            return new Builder<>(root, mapper, Objects.requireNonNull(columns, "columns"), primaryKey, orderBy, keyset,
                    primaryKeyFirst, afterMap, finisher, customizer, where, groupBy, having);
        }

        /** The primary key; required for {@link #keyset()} and {@link #primaryKeyFirst}. */
        public <K2> Builder<E, K2, M> primaryKey(PrimaryKey<M, K2> primaryKey) {
            return new Builder<>(root, mapper, columns, Objects.requireNonNull(primaryKey, "primaryKey"), orderBy,
                    keyset, primaryKeyFirst, afterMap, finisher, customizer, where, groupBy, having);
        }

        /** The ordering keys, replacing any set before. */
        @SafeVarargs
        public final Builder<E, K, M> orderBy(OrderField<M, ?>... orderBy) {
            var copy = new ArrayList<OrderField<M, ?>>();
            for (OrderField<M, ?> order : Objects.requireNonNull(orderBy, "orderBy")) {
                copy.add(Objects.requireNonNull(order, "orderBy element"));
            }
            return new Builder<>(root, mapper, columns, primaryKey, List.copyOf(copy), keyset, primaryKeyFirst,
                    afterMap, finisher, customizer, where, groupBy, having);
        }

        /**
         * Allows keyset paging; needs a primary key and an ungrouped query ({@code MQ1201} and {@code MQ1402} at
         * {@link #build()} otherwise).
         */
        public Builder<E, K, M> keyset() {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, true, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having);
        }

        /**
         * Allows two-step deep paging; needs a primary key and an ungrouped query ({@code MQ1201} and {@code MQ1402} at
         * {@link #build()} otherwise).
         */
        public Builder<E, K, M> primaryKeyFirst(PrimaryKeyFirst primaryKeyFirst) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset,
                    Objects.requireNonNull(primaryKeyFirst, "primaryKeyFirst"), afterMap, finisher, customizer,
                    where, groupBy, having);
        }

        /**
         * Runs {@code afterMap} once per row after the {@code RowMapper}, to fill a field derived from other mapped
         * values. It must not query, mutate shared state or throw for ordinary data (R-QRY-06). For a record use
         * {@link #finisher(UnaryOperator)}.
         */
        public Builder<E, K, M> afterMap(BiConsumer<M, Row> afterMap) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst,
                    Objects.requireNonNull(afterMap, "afterMap"), finisher, customizer, where, groupBy, having);
        }

        /** Replaces each mapped model with {@code finisher}'s result, after {@code afterMap}; for records. */
        public Builder<E, K, M> finisher(UnaryOperator<M> finisher) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    Objects.requireNonNull(finisher, "finisher"), customizer, where, groupBy, having);
        }

        /** Raw Criteria access for each phase; see {@link QueryCustomizer}. Replaces any customizer set before. */
        public Builder<E, K, M> customize(QueryCustomizer customizer) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, Objects.requireNonNull(customizer, "customizer"), where, groupBy, having);
        }

        /**
         * The {@code WHERE} predicate: {@code filters} receives an empty {@link Filters}, and every filter it adds is
         * ANDed. It runs once, here, so a {@code null} value fails now with {@code MQ1301}; the query keeps only what
         * it recorded. Replaces any predicate set before. Without it the query has no predicate (api/11 §5).
         */
        public Builder<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, FilterGroup.collect(Objects.requireNonNull(filters, "filters")), groupBy,
                    having);
        }

        /**
         * Groups the query by the columns of {@code columns}, replacing any group-by set before. Passing the set the
         * group keys are selected from keeps the two in step (R-AGG-05). Every selected column must be among them,
         * else {@link #build()} throws {@code MQ1401}; an empty set leaves the query ungrouped (R-AGG-07).
         *
         * @throws ModelQueryDefinitionException {@code MQ1404} when {@code columns} holds an {@link AggregateField},
         *     which cannot be a group key
         */
        public Builder<E, K, M> groupBy(ColumnSet<M> columns) {
            var keys = new ArrayList<ColumnField<M, ?, ?>>();
            for (SelectField<M, ?> column : Objects.requireNonNull(columns, "columns").columns()) {
                if (!(column instanceof ColumnField<M, ?, ?> key)) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1404, modelName(columns, root.rootEntity()) + "."
                            + column.name() + ": groupBy(...) takes columns; an aggregate cannot be a group key");
                }
                keys.add(key);
            }
            return groupBy(keys);
        }

        /** Groups the query by {@code columns}, as {@link #groupBy(ColumnSet)} does (R-AGG-05). */
        @SafeVarargs
        public final Builder<E, K, M> groupBy(ColumnField<M, ?, ?>... columns) {
            var keys = new ArrayList<ColumnField<M, ?, ?>>();
            for (ColumnField<M, ?, ?> column : Objects.requireNonNull(columns, "columns")) {
                keys.add(Objects.requireNonNull(column, "columns element"));
            }
            return groupBy(keys);
        }

        private Builder<E, K, M> groupBy(List<ColumnField<M, ?, ?>> keys) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, List.copyOf(new LinkedHashSet<>(keys)), having);
        }

        /**
         * The {@code HAVING} predicate: {@code filters} receives an empty {@link Having}, the {@code where} DSL over
         * aggregates, and every filter it adds is ANDed (R-AGG-06). It runs once, here, like {@link #where}. Replaces
         * any predicate set before. It does not make the query grouped: on a query with neither a group-by nor a
         * selected aggregate, {@link #build()} throws {@code MQ1407}, even when every filter was skipped (R-AGG-07).
         */
        public Builder<E, K, M> having(UnaryOperator<Having<M>> filters) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy,
                    HavingGroup.collect(Objects.requireNonNull(filters, "filters")));
        }

        /**
         * Builds the immutable query. A query is grouped when it has a group-by or selects an aggregate (R-AGG-07); a
         * grouped query needs no primary key and ignores one (R-AGG-09).
         *
         * @throws ModelQueryDefinitionException {@code MQ1202} when {@link #columns} was not called
         * @throws ModelQueryDefinitionException {@code MQ1407} for {@code having(...)} on an ungrouped query
         * @throws ModelQueryDefinitionException {@code MQ1402} for {@code keyset()} or {@code primaryKeyFirst(...)} on
         *     a grouped query
         * @throws ModelQueryDefinitionException {@code MQ1201} for {@code keyset()} or {@code primaryKeyFirst(...)}
         *     without a primary key
         * @throws ModelQueryDefinitionException {@code MQ1206} for a primary-key column of array type
         * @throws ModelQueryDefinitionException {@code MQ1207} for {@code keyset()} with a {@code Float} or
         *     {@code Double} order or primary-key column
         * @throws ModelQueryDefinitionException {@code MQ1401} for a selected column missing from the group-by
         * @throws ModelQueryDefinitionException {@code MQ1406} for an ordering key that does not fit the grouping
         * @throws ModelQueryDefinitionException {@code MQ1103} for two {@code Agg.of} fields sharing a name with
         *     different expressions
         */
        public ModelQuery<E, K, M> build() {
            if (columns == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1202,
                        "columns(...) is required for a query on " + root.rootEntity().getSimpleName());
            }
            String model = modelName(columns, root.rootEntity());
            // Structural, so skipping every having filter cannot turn a plain query into a grouped one (D-28).
            boolean grouped = !groupBy.isEmpty()
                    || columns.columns().stream().anyMatch(AggregateField.class::isInstance);
            if (!grouped && having != null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1407, model + ": having(...) needs a grouped query; "
                        + "add groupBy(...) or select an aggregate, or filter rows with where(...)");
            }
            if (keyset || primaryKeyFirst != null) {
                String used = keyset ? "keyset()" : "primaryKeyFirst(...)";
                if (grouped) {
                    // Both need a unique per-row key, which a group does not have (R-AGG-10, D-7).
                    throw new ModelQueryDefinitionException(MqCode.MQ1402, model + ": " + used
                            + " is refused on a grouped query, since a group has no unique row key; use offset paging");
                }
                if (primaryKey == null) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1201,
                            model + ": " + used + " requires primaryKey(...)");
                }
            }
            if (primaryKey != null) {
                checkKeyTypes(keyset);
            }
            if (grouped) {
                for (SelectField<M, ?> column : columns.columns()) {
                    // MySQL would return an arbitrary value of the group; PostgreSQL would fail anonymously (R-AGG-08).
                    if (column instanceof ColumnField<M, ?, ?> plain && !groupBy.contains(plain)) {
                        throw new ModelQueryDefinitionException(MqCode.MQ1401,
                                plain + ": selected but not in groupBy");
                    }
                }
                if (primaryKey != null) {
                    LOG.log(System.Logger.Level.DEBUG, "{0}: primaryKey(...) is ignored on a grouped query", model);
                }
            }
            checkOrder(model, grouped);
            checkAggregates(model);
            return new ModelQuery<>(this, grouped);
        }

        /**
         * No primary-key column is an array: an array equals only itself, so a key read from one row never equals the
         * same key read from another, and dedupe and primary-key-first paging would drop every row (R-QRY-12). With
         * {@code keyset}, no order column or primary-key tie-breaker is a {@code Float} or {@code Double} either: a
         * cursor on one may not compare equal to the stored value once bound, so the next page repeats or skips the
         * rest of its tie group (R-QRY-13).
         */
        private void checkKeyTypes(boolean keyset) {
            for (ColumnField<M, ?, ?> column : primaryKey.columns()) {
                if (column.type().isArray()) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1206, column + ": a primary-key column of type "
                            + column.type().getSimpleName() + " cannot identify a row, since an array equals only "
                            + "itself; key the query by a column of a value type");
                }
            }
            if (keyset) {
                for (OrderField<M, ?> order : orderBy) {
                    checkExactType(order.column());
                }
                primaryKey.columns().forEach(this::checkExactType);
            }
        }

        private void checkExactType(SelectField<M, ?> column) {
            if (column.type() == Float.class || column.type() == Double.class) {
                throw new ModelQueryDefinitionException(MqCode.MQ1207, column + ": keyset() cannot page by a "
                        + column.type().getSimpleName() + " column, whose cursor value need not compare equal to "
                        + "the stored one; order by an exact type such as BigDecimal, or export by offset");
            }
        }

        /** Each ordering key fits the grouping: a group key or an aggregate if grouped, else no aggregate (D-28). */
        private void checkOrder(String model, boolean grouped) {
            for (OrderField<M, ?> order : orderBy) {
                SelectField<M, ?> key = order.column();
                if (grouped && key instanceof ColumnField<M, ?, ?> column && !groupBy.contains(column)) {
                    // A group holds many values of the column: MySQL would sort by an arbitrary one (R-AGG-08).
                    throw new ModelQueryDefinitionException(MqCode.MQ1406, column + ": ordered by but not in "
                            + "groupBy; order a grouped query by a group key or an aggregate");
                }
                if (!grouped && key instanceof AggregateField) {
                    // Ordering by it would group the query while its selection says it is not grouped (R-AGG-07).
                    throw new ModelQueryDefinitionException(MqCode.MQ1406, model + "." + key.name() + ": ordered by "
                            + "an aggregate on an ungrouped query; select it or add groupBy(...)");
                }
            }
        }

        /** Two {@code Agg.of} fields sharing a name must be one definition wherever the query names them (R-AGG-02). */
        private void checkAggregates(String model) {
            var named = new ArrayList<SelectField<?, ?>>(columns.columns());
            orderBy.forEach(order -> named.add(order.column()));
            if (having != null) {
                named.addAll(having.aggregates());
            }
            var first = new HashMap<SelectField<?, ?>, SelectField<?, ?>>();
            for (SelectField<?, ?> field : named) {
                SelectField<?, ?> seen = first.putIfAbsent(field, field);
                if (seen != null && AggregateField.conflict(seen, field)) {
                    throw AggregateField.redefined(model, field);
                }
            }
        }

        private static String modelName(ColumnSet<?> columns, Class<?> entity) {
            for (SelectField<?, ?> column : columns.columns()) {
                if (column instanceof ColumnField<?, ?, ?> field) {
                    return field.model().getSimpleName();
                }
            }
            return entity.getSimpleName();
        }
    }
}
