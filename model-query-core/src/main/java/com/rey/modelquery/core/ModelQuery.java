package com.rey.modelquery.core;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
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
    private final QuerySpec spec;

    private ModelQuery(Builder<E, K, M> b) {
        this.root = b.root;
        this.mapper = b.mapper;
        this.columns = b.columns;
        this.primaryKey = b.primaryKey;
        this.orderBy = b.orderBy;
        this.keyset = b.keyset;
        this.primaryKeyFirst = b.primaryKeyFirst;
        this.afterMap = b.afterMap;
        this.finisher = b.finisher;
        this.customizer = b.customizer;
        this.spec = new Spec(root.rootEntity(), List.copyOf(columns.columns()),
                Optional.ofNullable(primaryKey), List.copyOf(orderBy), keyset);
    }

    /**
     * Starts a definition rooted at {@code root}, mapped with {@code mapper}. Only {@link Builder#columns} is also
     * required; every other part has a defined behaviour when absent (spec api/11 §5).
     */
    public static <E, M> Builder<E, Object, M> builder(TableField<E, E> root, RowMapper<M> mapper) {
        Objects.requireNonNull(root, "root");
        if (root.rootEntity() == null) {
            throw new IllegalArgumentException("root must be a TableField.root(...), not a join");
        }
        return new Builder<>(root, Objects.requireNonNull(mapper, "mapper"), null, null, List.of(), false, null, null,
                null, null);
    }

    /** The entity the query is rooted at. */
    public Class<E> rootEntity() {
        return root.rootEntity();
    }

    /** The columns selected for the model, without any primary key added for paging. */
    public ColumnSet<M> columns() {
        return columns;
    }

    /** The primary key, when defined. */
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

    /** The read-only view a {@link QueryCustomizer} receives. */
    public QuerySpec spec() {
        return spec;
    }

    /**
     * Resolves the statement of {@code phase} against {@code cb}. {@code MODEL} and {@code MODEL_BY_KEYS} select the
     * columns, plus the primary-key columns when the query needs them for paging ({@code keyset()} or
     * {@code primaryKeyFirst(...)}) and the {@code ColumnSet} omits them (R-QRY-04); {@code PRIMARY_KEY} selects the
     * key columns only. The customizer, if any, runs last. A new {@link JoinContext} is created per call.
     *
     * @throws IllegalStateException for {@code PRIMARY_KEY} on a query without a primary key
     */
    public BuiltQuery<M> buildQuery(CriteriaBuilder cb, Phase phase) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(phase, "phase");
        BuiltQuery<M> built = assemble(cb, phase);
        if (customizer != null) {
            customizer.customize(spec, built.joins(), built.query(), cb, phase);
        }
        return built;
    }

    /** Selection and ordering of {@code phase}, before any customizer runs. */
    private BuiltQuery<M> assemble(CriteriaBuilder cb, Phase phase) {
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> from = query.from(root.rootEntity());
        JoinContext joins = JoinContext.of(from, cb);
        RowSelection selection = RowSelection.of(selected(phase));
        query.multiselect(selection.selections(joins));
        List<Order> orders = new ArrayList<>();
        for (OrderField<M, ?> order : orderBy) {
            orders.addAll(order.toOrders(joins, cb));
        }
        if (!orders.isEmpty()) {
            query.orderBy(orders);
        }
        return new BuiltQuery<>(query, joins, selection, this::toModel);
    }

    /**
     * Logs a warning for a customizer that adds a predicate in some phases but not all of them (R-QRY-09). It runs the
     * customizer once per phase on a scratch query with the same selection and ordering as {@link #buildQuery}. Call
     * it once per ModelQuery, on first execution (D-21).
     */
    public void checkPhases(CriteriaBuilder cb) {
        Objects.requireNonNull(cb, "cb");
        if (customizer == null || primaryKey == null) {
            return; // without a primary key only the MODEL phase runs, so there is nothing to keep consistent
        }
        List<Phase> with = new ArrayList<>();
        List<Phase> without = new ArrayList<>();
        for (Phase phase : Phase.values()) {
            BuiltQuery<M> scratch = assemble(cb, phase);
            customizer.customize(spec, scratch.joins(), scratch.query(), cb, phase);
            (scratch.query().getRestriction() != null ? with : without).add(phase);
        }
        if (!with.isEmpty() && !without.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                    "QueryCustomizer on {0} adds a predicate only in phase(s) {1}, not in {2}; primary-key-first "
                            + "paging would return rows the predicate should have removed",
                    root.rootEntity().getSimpleName(), with, without);
        }
    }

    private List<SelectField<M, ?>> selected(Phase phase) {
        var result = new ArrayList<SelectField<M, ?>>();
        if (phase == Phase.PRIMARY_KEY) {
            if (primaryKey == null) {
                throw new IllegalStateException("Phase PRIMARY_KEY needs a primary key on " + modelName());
            }
            result.addAll(primaryKey.columns());
            return result;
        }
        result.addAll(columns.columns());
        if (primaryKey != null && (keyset || primaryKeyFirst != null)) {
            result.addAll(primaryKey.columns());
        }
        return result;
    }

    private M toModel(Row row) {
        M model = mapper.map(row);
        if (afterMap != null) {
            afterMap.accept(model, row);
        }
        return finisher == null ? model : finisher.apply(model);
    }

    private String modelName() {
        return Builder.modelName(columns, root.rootEntity());
    }

    private record Spec(Class<?> rootEntity, List<SelectField<?, ?>> columns, Optional<PrimaryKey<?, ?>> primaryKey,
            List<OrderField<?, ?>> orderBy, boolean keyset) implements QuerySpec {}

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

        private Builder(TableField<E, E> root, RowMapper<M> mapper, ColumnSet<M> columns,
                PrimaryKey<M, K> primaryKey, List<OrderField<M, ?>> orderBy, boolean keyset,
                PrimaryKeyFirst primaryKeyFirst, BiConsumer<M, Row> afterMap, UnaryOperator<M> finisher,
                QueryCustomizer customizer) {
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
        }

        /** The columns to select; required. */
        public Builder<E, K, M> columns(ColumnSet<M> columns) {
            return new Builder<>(root, mapper, Objects.requireNonNull(columns, "columns"), primaryKey, orderBy, keyset,
                    primaryKeyFirst, afterMap, finisher, customizer);
        }

        /** The primary key; required for {@link #keyset()} and {@link #primaryKeyFirst}. */
        public <K2> Builder<E, K2, M> primaryKey(PrimaryKey<M, K2> primaryKey) {
            return new Builder<>(root, mapper, columns, Objects.requireNonNull(primaryKey, "primaryKey"), orderBy,
                    keyset, primaryKeyFirst, afterMap, finisher, customizer);
        }

        /** The ordering keys, replacing any set before. */
        @SafeVarargs
        public final Builder<E, K, M> orderBy(OrderField<M, ?>... orderBy) {
            var copy = new ArrayList<OrderField<M, ?>>();
            for (OrderField<M, ?> order : Objects.requireNonNull(orderBy, "orderBy")) {
                copy.add(Objects.requireNonNull(order, "orderBy element"));
            }
            return new Builder<>(root, mapper, columns, primaryKey, List.copyOf(copy), keyset, primaryKeyFirst,
                    afterMap, finisher, customizer);
        }

        /** Allows keyset paging; needs a primary key ({@code MQ1201} at {@link #build()} otherwise). */
        public Builder<E, K, M> keyset() {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, true, primaryKeyFirst, afterMap,
                    finisher, customizer);
        }

        /** Allows two-step deep paging; needs a primary key ({@code MQ1201} at {@link #build()} otherwise). */
        public Builder<E, K, M> primaryKeyFirst(PrimaryKeyFirst primaryKeyFirst) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset,
                    Objects.requireNonNull(primaryKeyFirst, "primaryKeyFirst"), afterMap, finisher, customizer);
        }

        /**
         * Runs {@code afterMap} once per row after the {@code RowMapper}, to fill a field derived from other mapped
         * values. It must not query, mutate shared state or throw for ordinary data (R-QRY-06). For a record use
         * {@link #finisher(UnaryOperator)}.
         */
        public Builder<E, K, M> afterMap(BiConsumer<M, Row> afterMap) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst,
                    Objects.requireNonNull(afterMap, "afterMap"), finisher, customizer);
        }

        /** Replaces each mapped model with {@code finisher}'s result, after {@code afterMap}; for records. */
        public Builder<E, K, M> finisher(UnaryOperator<M> finisher) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    Objects.requireNonNull(finisher, "finisher"), customizer);
        }

        /** Raw Criteria access for each phase; see {@link QueryCustomizer}. Replaces any customizer set before. */
        public Builder<E, K, M> customize(QueryCustomizer customizer) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, Objects.requireNonNull(customizer, "customizer"));
        }

        /**
         * Builds the immutable query.
         *
         * @throws ModelQueryDefinitionException {@code MQ1202} when {@link #columns} was not called
         * @throws ModelQueryDefinitionException {@code MQ1201} for {@code keyset()} or {@code primaryKeyFirst(...)}
         *     without a primary key
         */
        public ModelQuery<E, K, M> build() {
            if (columns == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1202,
                        "columns(...) is required for a query on " + root.rootEntity().getSimpleName());
            }
            if (primaryKey == null && (keyset || primaryKeyFirst != null)) {
                String used = keyset ? "keyset()" : "primaryKeyFirst(...)";
                throw new ModelQueryDefinitionException(MqCode.MQ1201,
                        modelName(columns, root.rootEntity()) + ": " + used + " requires primaryKey(...)");
            }
            return new ModelQuery<>(this);
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
