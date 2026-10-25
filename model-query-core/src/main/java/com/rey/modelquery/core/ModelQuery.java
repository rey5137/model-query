package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
@CheckReturnValue
@Incubating
public final class ModelQuery<E, K, M> {

    private static final System.Logger LOG = System.getLogger(ModelQuery.class.getName());

    /** Options that never chunk nor refuse a list, for scratch builds whose SQL is never run. */
    private static final RenderOptions UNLIMITED = RenderOptions.of(Integer.MAX_VALUE, Integer.MAX_VALUE);

    /**
     * {@link #UNLIMITED} for {@link #checkPhases}' scratch builds alone, with a stand-in sequence renderer so a model
     * selecting {@code Expr.nextval} is not refused for a vendor fact the scratch lacks: the real build refuses it on
     * this executor's own options (D-132). Its stand-in operator gate serves every feature for the same reason, so a
     * query with an {@code op} and a customizer passes there (R-VND-23, D-169), and so do its aggregate gate and a
     * renderer whose {@code firstBy} is a NULL literal, for an {@code Agg.firstBy} (R-AGG-16).
     */
    private static final RenderOptions PHASE_SCRATCH_BASE =
            UNLIMITED.withSequenceRenderer((cb, sequence) -> cb.nullLiteral(Long.class))
                    .withOperatorGate(OperatorGate.of("checkPhases' scratch build",
                            EnumSet.allOf(OperatorFeature.class), EnumSet.allOf(OperatorFeature.class)))
                    .withAggregateGate(AggregateGate.of("checkPhases' scratch build",
                            EnumSet.allOf(AggregateFeature.class)))
                    .withAggregateRenderer(new AggregateRenderer() {
                        @Override
                        public Expression<?> firstBy(CriteriaBuilder cb, Expression<?> value, List<Order> orders) {
                            return cb.nullLiteral(value.getJavaType());
                        }

                        @Override
                        public Expression<?> firstBy(CriteriaBuilder cb, Expression<?> value, List<Order> orders,
                                Predicate filter) {
                            return cb.nullLiteral(value.getJavaType());
                        }
                    });

    /**
     * {@link #PHASE_SCRATCH_BASE} with a stand-in binder, for {@link #checkPhases(CriteriaBuilder)} alone: with an
     * executor's options the scratch build uses the options' binder or none, as the real build does (MQ1502, D-169).
     */
    private static final RenderOptions PHASE_SCRATCH = PHASE_SCRATCH_BASE
                    // A scratch statement never runs, so a parameter typed by the class is enough for any value.
                    .withOperandBinder(new OperandBinder() {
                        @Override
                        public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy,
                                Class<T> type) {
                            return cb.parameter(type);
                        }
                    });

    private final TableField<E, E> root;
    private final RowMapper<M> mapper;
    private final SelectSet<M> columns;
    private final PrimaryKey<M, K> primaryKey;
    private final List<OrderField<M, ?>> orderBy;
    private final boolean keyset;
    private final PrimaryKeyFirst primaryKeyFirst;
    private final BiConsumer<M, Row> afterMap;
    private final UnaryOperator<M> finisher;
    private final QueryCustomizer customizer;
    private final List<Filter> where;
    private final List<ScalarField<M, ?>> groupBy;
    private final List<Filter> having;
    private final QueryConditions conditions;
    private final boolean grouped;
    private final QuerySpec spec;
    /** The fetch plan, or {@code null}. */
    private final FetchPlan<M> fetch;
    /** The columns the fetch plan needs besides {@link #columns}: child keys and enricher columns (R-FCH-02). */
    private final List<ColumnField<M, ?, ?>> needed;
    /** What {@code MODEL} and {@code MODEL_BY_KEYS} select, and what {@code PRIMARY_KEY} selects; fixed per query. */
    private final List<SelectField<M, ?>> modelColumns;
    private final RowSelection modelSelection;
    private final RowSelection keySelection;
    private final Function<Row, M> mapping = this::toModel;
    /** The builder this query was built from, which {@link #orderedBy} re-orders and builds again. */
    private final Builder<E, K, M> builder;
    /** The query {@code build()} returned: this one, or the one this is an {@link #orderedBy} copy of. */
    private final ModelQuery<E, K, M> definition;
    /** The row lock, or {@code null} without one. */
    private final RowLock lock;
    /** The source this query reads, or {@code null} when it is rooted at an entity (R-SRC-10). */
    private final SourceQuery<E> source;
    /** The sources this query joins, in binding order; empty for most queries (R-SRC-21). */
    private final List<JoinedSource> joinedSources;
    private final List<EntityJoin<M, ?>> entityJoins;
    private final Map<TableField<?, ?>, List<Filter>> joinFilters;
    /** The value each {@link QueryParam} is bound to, by param identity: this request's (R-COL-40, INV-9). */
    private final Map<QueryParam<?>, Object> bindings;
    /** Shared with the queries that differ from this one in their bindings alone (D-201). */
    private final DefinitionKey definitionKey;

    private ModelQuery(Builder<E, K, M> b, SelectSet<M> columns, List<ColumnField<M, ?, ?>> needed, boolean grouped,
            ModelQuery<E, K, M> definition) {
        this.builder = b;
        this.definition = definition == null ? this : definition;
        this.root = b.root;
        this.mapper = b.mapper;
        this.columns = columns;
        this.fetch = b.fetch;
        this.needed = needed;
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
        var recorded = new LinkedHashMap<TableField<?, ?>, List<Filter>>();
        // An all-skipped filter adds nothing (R-FLT-01), so it is not in force: it stays out of the map and inspection.
        b.joinFilters.forEach((table, binding) -> {
            if (!binding.terms().isEmpty()) {
                recorded.put(table, binding.terms());
            }
        });
        this.joinFilters = Collections.unmodifiableMap(recorded);
        // An orderedBy copy differs in its order alone, which the view leaves out, so it shares the view (R-INS-04).
        this.conditions = definition != null ? definition.conditions
                : new QueryConditions(ConditionGroup.conditions(where), ConditionGroup.conditions(having),
                        joinFilters);
        this.grouped = grouped;
        this.lock = b.lock;
        this.source = b.source;
        this.joinedSources = b.joinedSources;
        this.entityJoins = b.entityJoins;
        this.bindings = b.bindings;
        this.definitionKey = b.definitionKey;
        this.spec = new Spec(root.rootEntity(), List.copyOf(columns.fields()),
                Optional.ofNullable(primaryKey), List.copyOf(orderBy), keyset, List.copyOf(groupBy), grouped);
        this.modelColumns = List.copyOf(selected());
        this.modelSelection = RowSelection.of(modelColumns);
        this.keySelection = primaryKey == null ? null : RowSelection.of(primaryKey.columns());
    }

    /**
     * Starts a definition rooted at {@code root}, mapped with {@code mapper}. Only {@link Builder#select} is also
     * required; every other part has a defined behaviour when absent (spec api/11 §5).
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join or a source root, which the
     *     overload below takes with the source it reads
     */
    public static <E, M> Builder<E, Object, M> builder(TableField<E, E> root, RowMapper<M> mapper) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(mapper, "mapper");
        if (root.rootEntity() == null || root.isSource() || root.isJoinedEntity()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1203,
                    "builder(...) takes a TableField.root(...), not the " + root.describe());
        }
        return new Builder<>(root, mapper, null, null, List.of(), false, null, null, null, null, List.of(), List.of(),
                null, null, null, null, List.of(), List.of(), Map.of(), Map.of(), null);
    }

    /**
     * Starts a definition over {@code source}: {@code root} is the source root
     * ({@link TableField#source(Class)}), whose columns the query reads by field name, and {@code source} is the
     * {@link SourceQuery} the generated {@code query(SourceQuery<S>)} took (R-SRC-10, R-GEN-37). Everything else is as
     * {@link #builder(TableField, RowMapper)}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join, an entity root or a joined
     *     source's {@link SourceJoin#root()}
     */
    @Incubating
    public static <E, M> Builder<E, Object, M> builder(
            TableField<E, E> root, RowMapper<M> mapper, SourceQuery<E> source) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(source, "source");
        if (root.rootEntity() == null || !root.isSource() || root.isJoinedSource()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1203,
                    "builder(root, mapper, source) takes a TableField.source(...), not the " + root.describe());
        }
        return new Builder<>(root, mapper, null, null, List.of(), false, null, null, null, null, List.of(), List.of(),
                null, null, null, source, List.of(), List.of(), Map.of(), Map.of(), null);
    }

    /** The entity the query is rooted at, or the source model a query over a source is rooted at. */
    public Class<E> rootEntity() {
        return root.rootEntity();
    }

    /**
     * The source the query reads, when it is rooted at one: the {@link SourceQuery} its generated
     * {@code query(SourceQuery<S>)} took. Empty for a query rooted at an entity (R-SRC-10).
     */
    @Incubating
    public Optional<SourceQuery<E>> source() {
        return Optional.ofNullable(source);
    }

    /**
     * Whether the query joins a source ({@link Builder#joinSource}): every statement it builds then reads that
     * source's common table expression, which a locking clause cannot follow, as for a query rooted at a source
     * (R-SRC-11, R-SRC-22).
     */
    @EngineFacing
    @Incubating
    public boolean joinsSource() {
        return !joinedSources.isEmpty();
    }

    /** The immutable entity joins this query binds, in registration order (R-SRC-23, R-INS-01). */
    @EngineFacing
    @Incubating
    public List<EntityJoin<M, ?>> entityJoins() {
        return entityJoins;
    }

    /**
     * What the query selects for the model, without any primary key added for paging nor the columns a fetch plan
     * needs (R-FCH-02). With a plan, it is the plan's selection and its join plans', re-rooted under their joins.
     */
    public SelectSet<M> select() {
        return columns;
    }

    /** The fetch plan the query runs on what it returns, when one is attached (R-FCH-02, R-FCH-13). */
    @Incubating
    public Optional<FetchPlan<M>> fetch() {
        return Optional.ofNullable(fetch);
    }

    /** The row lock this read takes, when one is set (R-QRY-17). */
    @Incubating
    public Optional<RowLock> lock() {
        return Optional.ofNullable(lock);
    }

    /**
     * A copy of this query that takes {@code lock} on the rows it reads, replacing any lock it had, as
     * {@link #orderedBy} copies: an equal lock returns {@code this}, and the copy keeps the fetch plan (R-FCH-13).
     * A constant lock returns the same copy, as {@link #withFetch} does, so it is checked once. The copy passes the
     * checks of {@link Builder#build()}, so a grouped query, which has no row to lock, is refused.
     *
     * @throws NullPointerException when {@code lock} is {@code null}
     * @throws ModelQueryDefinitionException {@code MQ1209} when the query is grouped
     * @implSpec R-QRY-17, D-127
     */
    @Incubating
    public ModelQuery<E, K, M> withLock(RowLock lock) {
        Objects.requireNonNull(lock, "lock");
        if (lock.equals(this.lock)) {
            return this;
        }
        ModelQuery<E, K, M> copy = LockedCopies.copy(this, lock);
        if (copy == null) {
            DefinitionKey key = definitionKey.withLock(lock);
            Builder<E, K, M> locking = builder.lock(lock).keyed(key);
            // A copy of another binding of this definition passed the checks, which read no bound value (D-201).
            copy = LockedCopies.passed(key) ? locking.unchecked() : locking.build();
            LockedCopies.put(this, lock, copy, key);
        }
        return copy;
    }

    /**
     * A copy of this query with {@code plan}'s selection and plan, replacing any it had: a new definition, checked as
     * {@link Builder#build()} checks one, but only the first time this query is given {@code plan}, so a constant
     * plan is checked once and returns the same copy. An {@link #orderedBy} copy of it keeps the plan.
     *
     * @throws ModelQueryDefinitionException as {@link Builder#build()} does
     * @implSpec R-FCH-13
     */
    @Incubating
    public ModelQuery<E, K, M> withFetch(FetchPlan<M> plan) {
        ModelQuery<E, K, M> copy = CheckedFetches.copy(this, plan);
        if (copy != null) {
            return copy;
        }
        DefinitionKey key = definitionKey.withFetch(plan);
        Builder<E, K, M> fetching = builder.fetch(plan).keyed(key);
        // A copy of this query or of another binding of its definition passed the checks, which read no bound value.
        ModelQuery<E, K, M> built = CheckedFetches.passed(key) ? fetching.unchecked() : fetching.build();
        CheckedFetches.pass(this, plan, built, key); // only once it passed, so a plan that threw is checked again
        return built;
    }

    /** The primary key, when defined; always empty for a grouped query, which ignores one (R-AGG-09). */
    public Optional<PrimaryKey<M, K>> primaryKey() {
        return Optional.ofNullable(primaryKey);
    }

    /** The ordering keys, in order, as a list that throws on mutation. */
    public List<OrderField<M, ?>> orderBy() {
        return orderBy;
    }

    /**
     * What this query filters on: the conditions its {@code where} and {@code having} operators recorded, as called,
     * leaving out skipped filters and the predicates of a {@link QueryCustomizer} or of the executor. A fetch plan's
     * child filters are not in it. {@link #withFetch} and {@link #orderedBy} copies have an equal view.
     *
     * @implSpec R-INS-01, R-INS-02, R-INS-04, D-101
     */
    @Incubating
    public QueryConditions conditions() {
        return conditions;
    }

    /**
     * A copy of this query ordered by {@code sort} instead of its own {@code orderBy}, for a sort chosen per call on
     * a definition held in a {@code static final} field; this query when {@code sort} has no key. Each property names
     * one of the selected {@link #select()}, never an attribute the query does not select: first by a column's
     * property path, the model field names from the root ({@code customer.name} for the field {@code name} of the
     * model under the {@code @Join} field {@code customer}), then by its attribute path from the root
     * ({@code customer.fullName}; the attribute itself for a root column), then by an aggregate's
     * {@link SelectField#name()}. A bare attribute name never matches a joined column, and a column without a
     * property ({@link ColumnField#named}) matches by attribute path only. Matching is exact and case-sensitive; a
     * property that names different columns on different tiers is ambiguous, and one column matched on two tiers is
     * not. The copy passes the checks of {@link Builder#build()}, and an executor still closes its order with the
     * primary key or the group keys (R-PAG-01), so an ungrouped query without a primary key, which has nothing to
     * close it with, takes no sort. Every failure is {@code MQ2301}, because the sort comes from the request (D-58).
     *
     * @throws ModelQueryExecutionException {@code MQ2301} for a property matching no selected column, or different
     *     columns; for an ungrouped query without a primary key; and, with the build failure as cause, for a sort
     *     {@link Builder#build()} refuses ({@code MQ1207} for a {@code Float} or {@code Double} key on a keyset
     *     query, {@code MQ1406} for a key that does not fit the grouping)
     * @implSpec R-QRY-14, D-52, D-55, D-58
     */
    @Incubating
    public ModelQuery<E, K, M> orderedBy(SortSpec sort) {
        Objects.requireNonNull(sort, "sort");
        if (sort.keys().isEmpty()) {
            return this;
        }
        if (!grouped && primaryKey == null) {
            throw new ModelQueryExecutionException(MqCode.MQ2301, modelName() + ": an ungrouped query without a "
                    + "primary key takes no sort, because no key closes its order and its pages could repeat or skip "
                    + "rows; give the definition a primaryKey(...)");
        }
        var resolved = new ArrayList<OrderField<M, ?>>();
        for (SortSpec.Key key : sort.keys()) {
            resolved.add(new OrderField<>(resolve(key.property()), key.ascending(), key.nulls()));
        }
        try {
            return builder.ordered(List.copyOf(resolved)).build(definition);
        } catch (ModelQueryDefinitionException e) {
            throw new ModelQueryExecutionException(MqCode.MQ2301, modelName() + ": sort by "
                    + sort.keys().stream().map(SortSpec.Key::property).toList() + " does not fit the query: "
                    + e.getMessage(), e);
        }
    }

    /**
     * The one selected column {@code property} names, matched on every tier: property path, attribute path and
     * aggregate {@code named} property or name (R-QRY-14, D-55, D-58, D-121).
     */
    private SelectField<M, ?> resolve(String property) {
        var matches = new LinkedHashMap<SelectField<M, ?>, List<String>>();
        for (SelectField<M, ?> column : columns.fields()) {
            if (column instanceof ColumnField<M, ?, ?> plain) {
                if (property.equals(FieldIndex.propertyKey(plain))) {
                    matches.computeIfAbsent(column, c -> new ArrayList<>()).add("property path");
                }
                if (plain.path().equals(property)) {
                    matches.computeIfAbsent(column, c -> new ArrayList<>()).add("attribute path");
                }
            } else if (property.equals(FieldIndex.propertyKey(column))) {
                matches.computeIfAbsent(column, c -> new ArrayList<>()).add("aggregate named");
            }
        }
        if (matches.isEmpty()) {
            // An aggregate's canonical name is tried only when no field is named so (R-QRY-14).
            for (SelectField<M, ?> column : columns.fields()) {
                if (!(column instanceof ColumnField<?, ?, ?>) && column.name().equals(property)) {
                    matches.computeIfAbsent(column, c -> new ArrayList<>()).add("aggregate name");
                }
            }
        }
        if (matches.size() == 1) {
            return matches.keySet().iterator().next();
        }
        if (matches.isEmpty()) {
            throw new ModelQueryExecutionException(MqCode.MQ2301, modelName() + ": sort property '" + property
                    + "' names no selected column or aggregate; a sort property is a selected column's property "
                    + "path or attribute path from the root, or an aggregate's named property or name, exact and "
                    + "case-sensitive");
        }
        List<String> candidates = matches.entrySet().stream()
                .map(match -> candidateName(match.getKey()) + " (" + String.join(", ", match.getValue()) + ")")
                .toList();
        throw new ModelQueryExecutionException(MqCode.MQ2301, modelName() + ": sort property '" + property
                + "' names more than one selected column: " + candidates
                + "; name one by a path no other selected column has");
    }

    /**
     * What a sort can name {@code column} by: its attribute path, preceded by its property path when that differs, or
     * an aggregate's {@code named} property, else its name.
     */
    private static String candidateName(SelectField<?, ?> column) {
        if (column instanceof ColumnField<?, ?, ?> plain) {
            String propertyPath = plain.propertyPath();
            return propertyPath == null || propertyPath.equals(plain.path())
                    ? plain.path()
                    : propertyPath + " reading " + plain.path();
        }
        String named = FieldIndex.propertyKey(column);
        return named != null ? named : column.name();
    }

    /**
     * The query whose phase check covers this one: the query {@link Builder#build()} returned, which is this query
     * unless it is an {@link #orderedBy} copy. A copy differs from it in its ordering alone, which a customizer
     * cannot change (R-QRY-11), so an executor runs {@link #checkPhases} once per definition and not once per copy
     * (D-21), keyed on the definition's {@link #definitionKey()}.
     *
     * @implSpec R-QRY-14
     */
    @Incubating
    public ModelQuery<E, K, M> definition() {
        return definition;
    }

    /**
     * What an executor keys its run-once checks and warn-once logs on: an opaque key, the same for the queries one
     * builder builds through {@link Builder#bind} alone, which differ in their bound values only (D-201). A
     * {@link #withLock} or {@link #withFetch} copy has its own key, the same for every such copy of those queries with
     * an equal lock or the same plan. Any other builder change makes a new key, an {@link #orderedBy} copy's order
     * included, so two queries built apart have two keys even when equal; a phase check keys on
     * {@link #definition()}'s. It holds no value (INV-9); compare it by identity. For executors, not applications.
     */
    @Incubating
    @EngineFacing
    public Object definitionKey() {
        return definitionKey;
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

    /** The group-by keys in order, as a list that throws on mutation; empty when there is no group-by. */
    @Incubating
    public List<ScalarField<M, ?>> groupBy() {
        return groupBy;
    }

    /** The read-only view a {@link QueryCustomizer} receives. */
    public QuerySpec spec() {
        return spec;
    }

    /**
     * Resolves the statement of {@code phase} against {@code cb}. Every phase has the same joins, predicate, grouping
     * and ordering, and only the SELECT list differs (R-QRY-09, D-26). {@code MODEL} and {@code MODEL_BY_KEYS} select
     * the columns plus, where the {@code SelectSet} omits them, the primary-key columns of an ungrouped query, every
     * ordering key and every group key, so an executor can read them from the row (R-QRY-04, D-29), and on an
     * ungrouped query the presence key of each {@code presentBy} join a column is read through (D-38);
     * {@code PRIMARY_KEY} selects the key columns only. The {@code where} terms through a to-many join that nothing
     * else in the statement keeps render in one {@code EXISTS} over a second root, in every phase alike, and the
     * other terms on the root (R-EXE-16, D-181). The customizer, if any, runs last. A new {@link JoinContext}
     * is created per call and carries {@code options}, the facts about the target database the build renders by
     * ({@link RenderOptions#portable()} when unknown, D-34).
     *
     * @throws ModelQueryExecutionException {@code MQ2203} for {@code PRIMARY_KEY} on a query without a primary key
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changed the ordering or grouping
     *     or dropped a source check column
     */
    @EngineFacing
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
     * cannot see makes paging skip or repeat rows (R-QRY-11, D-33). On a locked query it must also leave
     * {@code distinct} and the {@code HAVING} restriction as they are: either one makes a statement that is no longer
     * the row-locking read the lock is applied to (R-QRY-17, D-127). A recursive source's check columns must
     * also remain selected, so every statement can read its checks (R-QRY-11, D-147).
     *
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changed the ordering or the grouping,
     *     dropped a source check column, or on a locked query changed {@code distinct} or the {@code HAVING}
     *     restriction; {@code MQ1509} when it made a query selecting a sequence value distinct (R-COL-28)
     */
    private void customize(BuiltQuery<M> built, CriteriaBuilder cb, Phase phase) {
        CriteriaQuery<Tuple> query = built.query();
        List<Order> orders = List.copyOf(query.getOrderList());
        List<Expression<?>> groups = List.copyOf(query.getGroupList());
        boolean distinct = lock != null && query.isDistinct();
        Predicate having = lock != null ? query.getGroupRestriction() : null;
        List<String> checkAliases = built.recursionChecks().stream().map(Selection::getAlias).toList();
        customizer.customize(spec, built.joins(), query, cb, phase);
        if (!checkAliases.isEmpty()) {
            Selection<?> selection = query.getSelection();
            List<? extends Selection<?>> selected = selection == null ? List.of()
                    : selection.isCompoundSelection() ? selection.getCompoundSelectionItems() : List.of(selection);
            List<String> aliases = selected.stream().map(Selection::getAlias).toList();
            List<String> missing = checkAliases.stream().filter(alias -> !aliases.contains(alias)).toList();
            if (!missing.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1205, modelName()
                        + ": the QueryCustomizer replaced the selection and dropped the source's check columns "
                        + missing + " of phase " + phase + "; add to the selection instead of replacing it");
            }
        }
        boolean reordered = !orders.equals(query.getOrderList());
        if (reordered || !groups.equals(query.getGroupList())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1205, modelName() + ": the QueryCustomizer changed the "
                    + (reordered ? "ORDER BY" : "GROUP BY") + " of phase " + phase + "; order and group the query "
                    + "with orderBy(...) and groupBy(...), which paging and export follow");
        }
        if (phase != Phase.PRIMARY_KEY && query.isDistinct()) {
            checkDistinctSequences();
        }
        if (query.isDistinct() && TreatChecks.treated(this) && selectsRootIdAnchor(query)) {
            throw new ModelQueryConfigurationException(MqCode.MQ4016, modelName() + ": the QueryCustomizer made "
                    + "phase " + phase + " distinct, and its treated statement selects the hidden root-id anchor "
                    + BuiltQuery.ROOT_ID_ANCHOR + ", so equal visible rows would stop collapsing (R-COL-35); select "
                    + "the root id in the model, or drop the distinct");
        }
        if (lock == null) {
            return;
        }
        boolean madeDistinct = distinct != query.isDistinct();
        if (madeDistinct || !Objects.equals(having, query.getGroupRestriction())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1205, modelName() + ": the QueryCustomizer changed "
                    + (madeDistinct ? "distinct" : "the HAVING") + " of phase " + phase + " on a locked query; a row "
                    + "lock is applied to the query's own rows, so drop the customizer's distinct or having, or the "
                    + "lock");
        }
    }

    private static boolean selectsRootIdAnchor(CriteriaQuery<Tuple> query) {
        Selection<?> selection = query.getSelection();
        List<? extends Selection<?>> selected = selection == null ? List.of()
                : selection.isCompoundSelection() ? selection.getCompoundSelectionItems() : List.of(selection);
        return selected.stream().anyMatch(item -> BuiltQuery.ROOT_ID_ANCHOR.equals(item.getAlias()));
    }

    /**
     * Refuses a distinct query whose model selects an expression reading {@code Expr.nextval} at any depth, as a
     * group key over one is refused: each row gets its own value, so {@code DISTINCT} would silently stop
     * deduplicating (R-COL-28, D-132).
     *
     * @throws ModelQueryDefinitionException {@code MQ1509} naming the first such column
     */
    private void checkDistinctSequences() {
        for (SelectField<M, ?> column : modelColumns) {
            if (column instanceof ExpressionField<?, ?> expression && expression.readsSequence()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1509, modelName() + ": " + column.name()
                        + " reads a sequence value, which advances on every evaluation, so a distinct query cannot "
                        + "select it: every row would get its own value and DISTINCT would stop deduplicating");
            }
        }
    }

    /**
     * The {@code MODEL} statement of a {@code through} child load (R-FCH-14, D-100): rooted at the parent's entity,
     * where {@code through} starts, and joined {@code INNER} along it before anything else, so that no {@code or(...)}
     * turns those joins {@code LEFT} (R-FLT-10). The model then resolves as {@link #buildQuery} resolves it, against a
     * context whose root is the path's last join, and {@code parentKey}, read on the parent's root, is selected last.
     * The customizer, if any, runs last, its {@code query.getRoots()} holding the parent's entity.
     *
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changed the ordering or grouping
     *     or dropped a source check column
     */
    BuiltQuery<M> buildThroughQuery(CriteriaBuilder cb, RenderOptions options, TableField<?, ?> through,
            ColumnField<?, ?, ?> parentKey) {
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<?> from = query.from(through.pathRoot());
        JoinContext parent = JoinContext.readOf(from, cb, query, bound(options));
        From<?, ?> join = through.resolve(parent);
        Expression<?> key = parentKey.path(parent);
        JoinContext child = parent.rootedAt(join, root.rootEntity());
        Walk walk = joinSources(query, child, join, from);
        BuiltQuery<M> built = assemble(query, child, Phase.MODEL, key, List.of(), walk);
        if (customizer != null) {
            customize(built, cb, Phase.MODEL);
        }
        return built;
    }

    /** Selection, predicate, grouping and ordering of {@code phase}, before any customizer runs. */
    private BuiltQuery<M> assemble(CriteriaBuilder cb, Phase phase, RenderOptions given) {
        RenderOptions options = bound(given);
        if (phase == Phase.PRIMARY_KEY && primaryKey == null) {
            throw new ModelQueryExecutionException(MqCode.MQ2203, modelName() + ": phase PRIMARY_KEY needs a primary "
                    + "key, and " + (grouped ? "a grouped query has none (R-AGG-09)" : "primaryKey(...) was not set"));
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        if (source != null) {
            SourceStatement statement = sourceStatement(query, cb, options);
            return assemble(query, statement.joins(), phase, null, statement.guards(),
                    new Walk(statement.checks(), source, null));
        }
        Root<E> from = query.from(root.rootEntity());
        JoinContext joins = JoinContext.readOf(from, cb, query, options);
        return assemble(query, joins, phase, null, List.of(), joinSources(query, joins, from, from));
    }

    /**
     * Joins each source this query binds to {@code outer}, the statement's root or a {@code through} child's join:
     * the source's CTE chain is registered on {@code query} as a source root's is, then joined on the binding's
     * pairs, so every statement this query builds carries the join, even one no column reads (R-SRC-21, R-SRC-22,
     * D-185). The bodies' parameters are bound by {@code query}, once (R-FLT-08).
     *
     * <p>A joined source that is itself the walk has its walk filter ({@code level <= maxDepth and not mark}) ANDed
     * into the join's {@code on}, so a {@code LEFT} join keeps its unmatched outer rows, and an inner walk's filter
     * stays in the next CTE body (R-SRC-18). The returned walk's checks are for {@code query} to select: the build
     * allows one walk across the joined chains (D-187).
     *
     * @throws ModelQueryConfigurationException {@code MQ4014} when no {@link SourceRenderer} is configured, or the
     *     provider's renderer joins no CTE
     */
    private Walk joinSources(CriteriaQuery<Tuple> query, JoinContext joins, From<?, ?> outer, Root<?> statementRoot) {
        joins.joinFilters(joinFilters);
        if (joinedSources.isEmpty() && entityJoins.isEmpty()) {
            return Walk.NONE;
        }
        RenderOptions options = joins.renderOptions();
        options.metamodel().ifPresent(metamodel -> entityJoins.forEach(join -> {
            try {
                join.checkIds(metamodel.entity(join.entity()));
            } catch (IllegalArgumentException e) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911,
                        join + ": target must be a mapped entity", e);
            }
        }));
        Walk walk = Walk.NONE;
        if (!joinedSources.isEmpty()) {
            walk = joinedSources(query, joins, outer, statementRoot);
        }
        // Column-anchored joins come after the joined sources, in registration order; each anchor's to-one joins are
        // made first, so the ON reads only tables before it (R-SRC-25, D-197).
        for (EntityJoin<M, ?> entityJoin : entityJoins) {
            if (!entityJoin.anchoredOnSource()) {
                joinEntity(entityJoin, entityJoinRenderer(options), statementRoot, joins);
            }
        }
        return walk;
    }

    private Walk joinedSources(CriteriaQuery<Tuple> query, JoinContext joins, From<?, ?> outer,
            Root<?> statementRoot) {
        CriteriaBuilder cb = joins.cb();
        RenderOptions options = joins.renderOptions();
        SourceRenderer renderer = options.sourceRenderer()
                .orElseThrow(() -> new ModelQueryConfigurationException(MqCode.MQ4014, modelName()
                        + ": this query joins a source, and the factory's persistence provider has no "
                        + "SourceRenderer (ProviderSupport#sources()); model-query-hibernate serves Hibernate"));
        Walk walk = Walk.NONE;
        for (JoinedSource bound : joinedSources) {
            SourceJoin<?, ?> join = bound.join();
            SourceQuery<?> source = bound.source();
            var bodies = new ArrayList<JoinContext>();
            var checks = new ArrayList<Selection<?>>();
            SourceRenderer.CteHandle handle = source.register(query, cb, options, renderer, modelName(), checks,
                    bodies);
            if (!checks.isEmpty()) {
                walk = new Walk(checks, source, "join '" + join.name() + "' of " + join.source().getSimpleName());
            }
            // The outer columns resolve first, so a to-one join they make precedes the join to the CTE.
            var outerPaths = new ArrayList<Expression<?>>();
            join.on().forEach(pair -> outerPaths.add(pair.column().path(joins)));
            From<?, Tuple> joined = renderer.join(outer, handle, join.kind(), cte -> {
                var equal = new ArrayList<Predicate>();
                for (int i = 0; i < outerPaths.size(); i++) {
                    equal.add(cb.equal(cte.get(join.on().get(i).alias()), outerPaths.get(i)));
                }
                if (source.recurses()) {
                    equal.addAll(source.walkGuards(cb, cte));
                }
                return cb.and(equal.toArray(Predicate[]::new));
            });
            joins.joinSource(join.root().key(), joined, outer, join.kind() == JoinKind.INNER,
                    source.keyColumns().get(0));
            for (EntityJoin<M, ?> entityJoin : entityJoins) {
                if (entityJoin.on().get(0).anchor().table().key().equals(join.root().key())) {
                    joinEntity(entityJoin, entityJoinRenderer(options), statementRoot, joins);
                }
            }
            bodies.forEach(joins::addBodyParameters);
        }
        return walk;
    }

    private EntityJoinRenderer entityJoinRenderer(RenderOptions options) {
        return options.entityJoinRenderer()
                .orElseThrow(() -> new ModelQueryConfigurationException(MqCode.MQ4014, modelName()
                        + ": this query joins an entity, and the factory's persistence provider has no "
                        + "EntityJoinRenderer (ProviderSupport#entityJoins()); "
                        + "model-query-hibernate serves Hibernate"));
    }

    /**
     * The predicate is built once by the engine; the provider applies it with one on call (R-SRC-24). A renderer that
     * never calls {@code on}, calls it twice, returns another join than the one it called {@code on} with, or leaves
     * the join without an ON would multiply rows on every statement, so it is refused (R-VND-32, INV-5).
     *
     * <p>The anchors and the {@code when} column resolve before the renderer joins, so a to-one join they make
     * precedes the entity join (R-SRC-25). An {@code INNER} join reading through a {@code joinFilter(...)} join is
     * {@code MQ1912}: a blanked anchor would drop the row (D-195, D-197). A column anchor through a to-many segment,
     * or one reading a to-one by its id, is {@code MQ1911}.
     */
    private <T> void joinEntity(EntityJoin<M, T> join, EntityJoinRenderer renderer, From<?, ?> root,
            JoinContext joins) {
        var anchors = new ArrayList<ColumnField<M, ?, ?>>();
        join.on().forEach(pair -> anchors.add(pair.anchor()));
        join.when().ifPresent(when -> anchors.add(when.column()));
        var paths = new ArrayList<Path<?>>();
        for (ColumnField<M, ?, ?> anchor : anchors) {
            if (join.kind() == JoinKind.INNER) {
                JoinKey filtered = joins.filteredAtOrAbove(anchor.table().key());
                if (filtered != null) {
                    throw JoinFilterChecks.refused(join + ": INNER, but its anchor " + anchor.name() + " reads "
                            + "through the joinFilter(...) join " + filtered + ", whose filter would blank it and "
                            + "drop the row; declare the entity join LEFT");
                }
            }
            Path<?> path = anchor.path(joins);
            if (!anchor.table().isJoinedSource()) {
                checkAnchorPath(join, anchor, path, joins);
            }
            paths.add(path);
        }
        int[] calls = {0};
        From<?, ?>[] called = {null};
        From<?, T> joined = renderer.join(root, join.entity(), join.kind(), entity -> {
            calls[0]++;
            called[0] = entity;
            EntityType<?> type = entityType(join, entity, joins);
            join.checkIds(type);
            CriteriaBuilder cb = joins.cb();
            var equal = new ArrayList<Predicate>();
            for (int i = 0; i < join.on().size(); i++) {
                equal.add(cb.equal(entityId(entity, join.on().get(i).idAttribute()), paths.get(i)));
            }
            join.when().ifPresent(when -> equal.add(whenTerm(join, when, paths.get(paths.size() - 1), joins)));
            // Registered here, not after the renderer returns: filter columns need it while the ON is built.
            joins.joinEntity(join.root().key(), entity, root, join.kind() == JoinKind.INNER,
                    entityId(entity, EntityJoin.idAttributes(type).keySet().iterator().next()));
            equal.addAll(joins.joinFilter(join.root().key()));
            return cb.and(equal.toArray(Predicate[]::new));
        });
        if (calls[0] != 1 || joined != called[0] || !hasOn(join, renderer, joined)) {
            throw new ModelQueryConfigurationException(MqCode.MQ4014, join + ": "
                    + renderer.getClass().getName() + " must call on exactly once and apply its predicate as the "
                    + "ON of the join it returns (R-VND-32); it called on " + calls[0] + " time(s)");
        }
    }

    /**
     * {@code MQ1911} for a column anchor through a to-many segment or one reading a to-one's id (R-SRC-25). The walk
     * stops at the context's root, which in a {@code through} child load is the path's last join (R-FCH-14).
     */
    private static void checkAnchorPath(EntityJoin<?, ?> join, ColumnField<?, ?, ?> anchor, Path<?> path,
            JoinContext joins) {
        for (Path<?> node = path; node != null && node != joins.root(); ) {
            if (node instanceof Join<?, ?> step) {
                if (step.getAttribute() instanceof PluralAttribute<?, ?, ?>) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1911, join + ": anchor " + anchor.name()
                            + " reads through the to-many " + step.getAttribute().getName() + "; an anchor reads "
                            + "the root or a to-one path from it, so the join adds at most one row per outer row");
                }
                node = step.getParent();
            } else {
                node = node.getParentPath();
            }
        }
        if (anchor.readsToOneId(joins)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, join + ": anchor " + anchor.name() + " reads a "
                    + "to-one association by its id (R-COL-29); anchor on the foreign key's basic column instead");
        }
    }

    /** The static {@code when} term, a statement parameter, typed by the column under inline value handling. */
    private static Predicate whenTerm(EntityJoin<?, ?> join, EntityJoin.When<?> when, Path<?> path,
            JoinContext joins) {
        CriteriaBuilder cb = joins.cb();
        if (!joins.renderOptions().inlinesValues()) {
            return cb.equal(path, when.value());
        }
        return cb.equal(path, ValueOperands.valueOperand(joins.renderOptions(), cb, path,
                ColumnField.boxed(when.column().attributeType()), when.value(),
                join + ": when(" + when.column().name() + ", ...) under inline value handling", joins.sink()));
    }

    /**
     * Whether {@code joined} carries an ON. A {@link Join} answers {@code getOn()}; a provider's entity join that is no
     * {@code Join} (Hibernate's) answers a {@code getOn()} of a public type it implements, read reflectively. One
     * that answers none cannot be checked, so it passes: its call count and identity were still checked. A read that
     * fails is MQ4014.
     */
    private static boolean hasOn(EntityJoin<?, ?> join, EntityJoinRenderer renderer, From<?, ?> joined) {
        if (joined instanceof Join<?, ?> j) {
            return j.getOn() != null;
        }
        Method getOn = publicGetOn(joined.getClass());
        if (getOn == null) {
            return true;
        }
        try {
            return getOn.invoke(joined) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ModelQueryConfigurationException(MqCode.MQ4014, join + ": "
                    + renderer.getClass().getName() + " returned a join whose getOn() could not be read", e);
        }
    }

    /** {@code getOn()} declared by a public class or interface in {@code type}'s hierarchy, else null. */
    private static Method publicGetOn(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                return type.getMethod("getOn");
            } catch (NoSuchMethodException e) {
                // try the supertypes
            }
        }
        for (Class<?> parent : type.getInterfaces()) {
            Method found = publicGetOn(parent);
            if (found != null) {
                return found;
            }
        }
        return publicGetOn(type.getSuperclass());
    }

    private static EntityType<?> entityType(EntityJoin<?, ?> join, From<?, ?> from, JoinContext joins) {
        EntityType<?> entity = RowCounts.entityOf(from);
        if (entity != null) {
            return entity;
        }
        try {
            return joins.renderOptions().metamodel().map(metamodel -> metamodel.entity(join.entity()))
                    .orElseThrow(() -> new ModelQueryConfigurationException(MqCode.MQ4014,
                            join + ": the provider exposes no entity metamodel for the joined entity"));
        } catch (IllegalArgumentException e) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, join + ": target must be a mapped entity", e);
        }
    }

    static Path<?> entityId(From<?, ?> entity, String attribute) {
        Path<?> path = entity;
        for (String part : attribute.split("\\.")) {
            path = path.get(part);
        }
        return path;
    }

    /** A {@link SourceJoin} and the {@link SourceQuery} {@code joinSource} bound to it (R-SRC-21). */
    private record JoinedSource(SourceJoin<?, ?> join, SourceQuery<?> source) {}

    /**
     * The joins of {@code query}, a statement over {@link #source}: every CTE in the source chain is registered on it
     * first, innermost first, so every statement this query builds over the source carries it (R-SRC-12, R-SRC-18),
     * and the outer columns read the outermost CTE's field-named elements (R-SRC-10).
     *
     * <p>A recursive source is registered as {@code with recursive} instead; its walk filter
     * ({@code level <= maxDepth and not mark}) goes into the next CTE body in a chain or into {@code query} when it
     * is the sole level, while its depth, cycle and duplicate-key checks stay in {@code query}'s selection (R-SRC-07,
     * R-SRC-14, R-SRC-18).
     *
     * @throws ModelQueryConfigurationException {@code MQ4014} when no {@link SourceRenderer} is configured, which is
     *     the factory's provider having no source support, chained CTEs, or no duplicate-key check for a tree source
     */
    private SourceStatement sourceStatement(CriteriaQuery<Tuple> query, CriteriaBuilder cb, RenderOptions options) {
        SourceRenderer renderer = options.sourceRenderer()
                .orElseThrow(() -> new ModelQueryConfigurationException(MqCode.MQ4014, modelName()
                        + ": this query reads the source " + source.model().getSimpleName()
                        + ", and the factory's persistence provider has no SourceRenderer "
                        + "(ProviderSupport#sources()); model-query-hibernate serves Hibernate"));
        // Each CTE body renders in a context of its own; the statement binds the parameters they rendered (R-FLT-08).
        var bodies = new ArrayList<JoinContext>();
        if (source.hasInner()) {
            var checks = new ArrayList<Selection<?>>();
            SourceRenderer.CteHandle handle = source.register(query, cb, options, renderer, modelName(), checks,
                    bodies);
            JoinContext joins = JoinContext.sourceOf(
                    renderer.from(query, handle), source.model(), source.keyColumns(), cb, query, options);
            bodies.forEach(joins::addBodyParameters);
            return new SourceStatement(joins, List.of(), checks);
        }
        if (!source.recurses()) {
            JoinContext joins = JoinContext.sourceOf(renderer.cte(query, source.body(cb, options, renderer, bodies)),
                    source.model(), source.keyColumns(), cb, query, options);
            bodies.forEach(joins::addBodyParameters);
            return new SourceStatement(joins, List.of(), List.of());
        }
        RecursiveSpec spec = source.recursiveSpec(cb, options, renderer, bodies);
        RecursiveCte cte = renderer.recursive(query, spec);
        List<Selection<?>> checks = SourceQuery.recursionChecks(
                modelName(), source, spec, cte.depthKey(), cte.cycleKey(), cte.duplicateKey());
        JoinContext joins = JoinContext.sourceOf(cte.root(), source.model(), source.keyColumns(), cb, query,
                options);
        bodies.forEach(joins::addBodyParameters);
        // The extra level the recursion produced and the row that closed a cycle are both hidden here, in the same
        // statement that produced them (RFC 0013 §5, R-SRC-07).
        return new SourceStatement(joins, source.walkGuards(cb, cte.root()), checks);
    }

    /** What {@link #sourceStatement} adds to a statement: the joins, the recursive guards, and its checks. */
    private record SourceStatement(JoinContext joins, List<Predicate> guards, List<Selection<?>> checks) {}

    /**
     * The recursive walk a statement checks, in its root source's chain or a joined source's ({@code source}, whose
     * chain holds it), the check columns the statement selects for it, and for a joined one the join that reads it
     * (R-SRC-07, R-SRC-14, D-187). {@link #NONE} is a statement over no source; a root source over no walk has no
     * checks.
     */
    private record Walk(List<Selection<?>> checks, SourceQuery<?> source, String join) {
        static final Walk NONE = new Walk(List.of(), null, null);
    }

    /**
     * {@link #assemble(CriteriaBuilder, Phase, RenderOptions)} on {@code query}, resolving through {@code joins}:
     * {@code parentKey}, when not {@code null}, is selected after the model's columns, {@code guards} are ANDed to
     * the predicate, and {@code walk}'s checks are selected after the model's columns, for the executor to read from
     * the statement's first row (R-SRC-07). Guards and checks are empty for a statement over no recursive source.
     */
    private BuiltQuery<M> assemble(CriteriaQuery<Tuple> query, JoinContext joins, Phase phase,
            Expression<?> parentKey, List<Predicate> guards, Walk walk) {
        joins.joinFilters(joinFilters);
        List<Selection<?>> checks = walk.checks();
        CriteriaBuilder cb = joins.cb();
        joins.customized(customizer != null); // before any count is built (R-AGG-19)
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
        List<Selection<?>> selections = new ArrayList<>(selection.selections(joins));
        if (parentKey != null) {
            selections.add(parentKey.alias(BuiltQuery.PARENT_KEY));
        }
        selections.addAll(checks);
        rootIdAnchor(joins, selection).ifPresent(selections::add);
        query.multiselect(selections);
        List<Order> orders = new ArrayList<>();
        for (OrderField<M, ?> order : orderBy) {
            orders.addAll(order.toOrders(joins, cb, !groupBy.isEmpty()));
        }
        if (!orders.isEmpty()) {
            query.orderBy(orders);
        }
        if (!groupBy.isEmpty()) {
            for (ScalarField<M, ?> key : groupBy) {
                if (key instanceof ColumnField<?, ?, ?> column && column.wholeEntityToOne(joins)) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1410, column + ": a group key reads the to-one "
                            + column.name() + " as its whole entity, which has no portable GROUP BY; group by its "
                            + "id with a column of the target's id type, or by a column of the joined target");
                }
            }
            query.groupBy(groupBy.stream()
                    .<Expression<?>>map(key -> joins.withoutSequences(() -> key.expression(joins))).toList());
        }
        WhereSplit split = parentKey == null ? splitWhere(joins) : new WhereSplit(where, List.of());
        // WHERE and HAVING render together (D-26); the terms a semi-join takes render after them, in its sub-query.
        List<List<Predicate>> predicates = ConditionGroup.clausePredicates(List.of(split.root(), having), joins);
        var restrictions = new ArrayList<>(predicates.get(0));
        if (!split.semiJoined().isEmpty()) {
            restrictions.add(semiJoin(split.semiJoined(), query, joins));
        }
        if (!restrictions.isEmpty() || !guards.isEmpty()) {
            restrictions.addAll(guards);
            query.where(restrictions.toArray(Predicate[]::new));
        }
        if (!predicates.get(1).isEmpty()) {
            query.having(predicates.get(1).toArray(Predicate[]::new));
        }
        joins.checkJoinFilters();
        return built(query, joins, selection, parentKey, walk);
    }

    /** The {@link BuiltQuery} of {@code query}, carrying {@code walk}'s checks, limit, key and join (R-SRC-14). */
    private BuiltQuery<M> built(CriteriaQuery<Tuple> query, JoinContext joins, RowSelection selection,
            Expression<?> parentKey, Walk walk) {
        SourceQuery<?> recursive = walk.source() == null ? null : walk.source().recursiveSource();
        SourceQuery<?> keyed = recursive != null ? recursive : walk.source();
        return new BuiltQuery<>(query, joins, selection, mapping, parentKey, walk.checks(),
                recursive == null ? 0 : recursive.maxDepth(),
                keyed == null ? List.of() : keyed.key().columns().stream().map(ColumnField::name).toList(),
                walk.join());
    }

    /**
     * The {@code where} terms of a statement split by where they render (R-EXE-16, D-181). When a top-level term's own
     * render makes a to-many join, every term that joins or nests a sub-query, that one included, renders in one
     * {@code EXISTS} over a second root ({@code semiJoined}; an {@code or} or {@code not} mixing child and root terms
     * is one term), and each root term, which reads only the root's own columns, stays on the statement's root
     * ({@code root}), where an index on the root's table can narrow the outer scan. {@code semiJoined} is empty, and
     * the whole {@code where} renders on the root as before, unless the statement has an entity root with no treated
     * node, no source, no to-many join that a selected column, order term or group key has made on {@code joins}, and
     * a {@code having} that makes none.
     */
    private record WhereSplit(List<Filter> root, List<Filter> semiJoined) {}

    /**
     * {@link WhereSplit} for this definition's {@code where}. Since a filter is a lambda, which joins a term makes is
     * learnt by rendering it alone into a scratch sub-query of a throwaway statement with a fresh context, so nothing
     * it binds reaches the statement (D-65).
     */
    private WhereSplit splitWhere(JoinContext joins) {
        WhereSplit unsplit = new WhereSplit(where, List.of());
        if (where.isEmpty() || source != null || !(joins.root() instanceof Root<?> from) || hasToManyJoin(from)
                || TreatChecks.treated(this)) {
            return unsplit;
        }
        var onRoot = new ArrayList<Filter>();
        var semiJoined = new ArrayList<Filter>();
        boolean toMany = false;
        for (Filter term : where) {
            JoinContext probe = scratch(List.of(term), joins);
            toMany |= hasToManyJoin(probe.root());
            // A root term as a key-first write tells one (R-WRT-11): no join, and no exists(...) sub-query.
            (probe.root().getJoins().isEmpty() && !probe.renderedExists() ? onRoot : semiJoined).add(term);
        }
        if (!toMany || (!having.isEmpty() && hasToManyJoin(scratch(having, joins).root()))) {
            return unsplit;
        }
        return new WhereSplit(List.copyOf(onRoot), List.copyOf(semiJoined));
    }

    /**
     * {@code terms} rendered as one {@code EXISTS} over a second root of the entity correlated by entity equality,
     * so a root comes once however many children match (R-EXE-16, R-WRT-10). The root's other terms stay on the
     * statement: {@code C(root) AND EXISTS(r2 = r AND rest)} chooses the rows {@code EXISTS(r2 = r AND C AND rest)}
     * does (D-181). The statement binds what the sub-query rendered, once.
     */
    private Predicate semiJoin(List<Filter> terms, CriteriaQuery<Tuple> query, JoinContext joins) {
        CriteriaBuilder cb = joins.cb();
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<E> inner = sub.from(root.rootEntity());
        JoinContext innerJoins = JoinContext.readOf(inner, cb, sub, joins.renderOptions());
        innerJoins.adoptJoinedSources(joins);
        innerJoins.joinFilters(joinFilters); // a non-correlated root keeps each filtered join's ON (R-COL-39)
        var restrictions = new ArrayList<Predicate>();
        // Entity equality, not key columns: a read need not declare a primary key, and an embedded id compares whole.
        restrictions.add(cb.equal(inner, joins.root()));
        restrictions.addAll(ConditionGroup.toPredicates(terms, innerJoins));
        sub.select(cb.literal(1)).where(restrictions.toArray(Predicate[]::new));
        joins.adoptForeignKeyReads(innerJoins);
        joins.adoptJoinFilters(innerJoins);
        joins.addSubqueryBinds(innerJoins);
        return cb.exists(sub);
    }

    /** {@code clause} rendered over a second root, as {@link #semiJoin} renders it, in a throwaway statement. */
    private JoinContext scratch(List<Filter> clause, JoinContext joins) {
        CriteriaBuilder cb = joins.cb();
        Subquery<Integer> scratch = cb.createQuery().subquery(Integer.class);
        JoinContext probe = JoinContext.readOf(scratch.from(root.rootEntity()), cb, scratch, joins.renderOptions());
        probe.adoptJoinedSources(joins);
        ConditionGroup.toPredicates(clause, probe);
        return probe;
    }

    /**
     * The hidden anchor of an ungrouped statement over an entity root that has a treated node and whose selection reads
     * no untreated root id: {@code root.<id>} aliased {@code mq_0id}, selected last (R-COL-35). Reading the root
     * untreated keeps the provider from narrowing the root to the subtype, so other subtypes' rows stay. The row a
     * mapper reads holds only {@code selection}'s columns, so the anchor never reaches a model or {@code Rows}.
     */
    private Optional<Selection<?>> rootIdAnchor(JoinContext joins, RowSelection selection) {
        if (grouped || source != null || !(joins.root() instanceof Root<?> from) || !TreatChecks.treated(this)) {
            return Optional.empty();
        }
        String id = TreatChecks.rootId(from.getModel(), modelName());
        return TreatChecks.selectsRootId(selection.fields(), id) ? Optional.empty()
                : Optional.of(from.get(id).alias(BuiltQuery.ROOT_ID_ANCHOR));
    }

    /**
     * The fresh count of a statement with a treated node (R-COL-35, R-EXE-05): {@code count(root.<id>)} over the
     * query's {@code where} and the joins that can change its row count, with no selection and no order. A treated
     * column that is only selected or ordered is absent, since resolving it could narrow the root to its subtype: the
     * path of each column a model or order field reads, an expression's and a window's included, is joined when it
     * holds an {@code INNER} join, and otherwise only up to its first treated node. The count is distinct when a
     * to-many join no selected column reads repeats a root (a customizer's, or one R-EXE-16 leaves in the statement),
     * and counts rows when a selected column is read through a to-many join,
     * as {@code list} returns it (R-EXE-04). The customizer runs on it as on
     * {@code MODEL}, before the count replaces its selection. A joined source's walk checks are selected after the
     * count, which stays the first column (D-187). Only for an ungrouped query over an entity root.
     */
    @EngineFacing
    @Incubating
    public BuiltQuery<M> buildRootIdCount(CriteriaBuilder cb, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        if (!joinFilters.isEmpty()) {
            // The optimized count may omit a LEFT join that content reads; validate against content's joins (D-194).
            assemble(cb, Phase.MODEL, options);
        }
        if (grouped || source != null) {
            throw new IllegalStateException(modelName() + ": a root-id count needs an ungrouped entity-rooted query");
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> from = query.from(root.rootEntity());
        JoinContext joins = JoinContext.readOf(from, cb, query, bound(options));
        Walk walk = joinSources(query, joins, from, from);
        Path<?> id = from.get(TreatChecks.rootId(from.getModel(), modelName()));
        boolean readsToMany = false;
        var counted = new ArrayList<SelectField<M, ?>>(modelColumns);
        orderBy.forEach(order -> counted.add(order.column()));
        // Every column the selection and order read, inside an expression or a window too, in content's order, so an
        // or(...) filter reuses the INNER join they make as it does in content (R-FLT-10).
        var columns = new LinkedHashSet<ColumnField<?, ?, ?>>();
        counted.forEach(field -> readColumns(field, columns));
        for (ColumnField<?, ?, ?> column : columns) {
            TableField<?, ?> rowChanging = rowChangingPath(column.table());
            if (rowChanging.parent() != null) {
                readsToMany |= toMany(rowChanging.resolve(joins));
            }
        }
        List<Predicate> restrictions = ConditionGroup.clausePredicates(List.of(where, having), joins).get(0);
        if (!restrictions.isEmpty()) {
            query.where(restrictions.toArray(Predicate[]::new));
        }
        // A joined walk's checks are selected beside the id and then the count, which is the statement that reads them.
        var selections = new ArrayList<Selection<?>>(List.of(id));
        selections.addAll(walk.checks());
        query.multiselect(selections);
        BuiltQuery<M> built = built(query, joins, RowSelection.of(List.of()), null, walk);
        if (customizer != null) {
            customize(built, cb, Phase.MODEL);
        }
        query.orderBy(List.<Order>of());
        selections.set(0, !readsToMany && hasToManyJoin(from) ? cb.countDistinct(id) : cb.count(id));
        query.multiselect(selections);
        return built;
    }

    /**
     * The part of {@code table}'s path a count joins: all of it when it holds a join other than {@code LEFT}, which
     * can drop rows, else the path up to its first treated node, whose own joins may still repeat rows.
     */
    private static TableField<?, ?> rowChangingPath(TableField<?, ?> table) {
        boolean narrowing = false;
        TableField<?, ?> untreated = table;
        for (TableField<?, ?> node = table; node != null && node.parent() != null; node = node.parent()) {
            JoinKey key = node.key();
            narrowing |= key.type() != null && key.type() != JoinType.LEFT;
            if (key.type() == null) {
                untreated = node.parent();
            }
        }
        return narrowing ? table : untreated;
    }

    /**
     * The columns {@code field} reads in the enclosing statement: a column itself, an expression's operands at any
     * depth (a correlated value's lifted outer columns, not its inner ones), and a window's argument, partition and
     * order keys; an aggregate reads none in an ungrouped statement.
     */
    private static void readColumns(SelectField<?, ?> field, Set<ColumnField<?, ?, ?>> columns) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            columns.add(column.liftedFrom().orElse(column));
        } else if (field instanceof ExpressionField<?, ?> expression) {
            columns.addAll(expression.columns());
        } else if (field instanceof WindowField<?, ?> window) {
            window.readFields().forEach(read -> readColumns(read, columns));
        }
    }

    private static boolean toMany(From<?, ?> from) {
        for (From<?, ?> node = from; node instanceof Join<?, ?> join; node = join.getParent()) {
            if (join.getAttribute() instanceof PluralAttribute<?, ?, ?>) {
                return true;
            }
        }
        return false;
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
     * Checks a customizer that narrows some phases but not all of them, by adding a predicate or a join other than
     * LEFT (R-QRY-09): it logs a warning, or throws on a query with {@code primaryKeyFirst(...)}, whose pages past
     * the threshold would then skip rows (R-PAG-15). It runs the customizer once per phase the query can run on a
     * scratch query built as {@link #buildQuery} builds it, and compares the predicate and the joins before and
     * after, so the query's own {@code where} and joins do not count. Call it once per ModelQuery, on first execution
     * (D-21).
     *
     * @throws ModelQueryExecutionException {@code MQ2206} when the phases disagree on a query with
     *     {@code primaryKeyFirst(...)}
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changes ordering or grouping
     *     or drops a source check column in any phase
     */
    @EngineFacing
    public void checkPhases(CriteriaBuilder cb) {
        checkPhasesOn(cb, PHASE_SCRATCH);
    }

    /**
     * {@link #checkPhases(CriteriaBuilder)} for an executor whose {@code options} carry the source renderer its
     * query over a source builds with: the scratch builds register the source's CTE too (R-SRC-12), and take no other
     * limit from {@code options}.
     */
    @EngineFacing
    public void checkPhases(CriteriaBuilder cb, RenderOptions options) {
        RenderOptions scratch = PHASE_SCRATCH_BASE.withRenderersOf(Objects.requireNonNull(options, "options"));
        if (!joinFilters.isEmpty()) {
            // First resolution of D-194: content's joins decide, since an optimized count may omit a LEFT join.
            assemble(Objects.requireNonNull(cb, "cb"), Phase.MODEL, scratch);
        }
        checkPhasesOn(cb, scratch);
    }

    private void checkPhasesOn(CriteriaBuilder cb, RenderOptions scratchOptions) {
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
            BuiltQuery<M> scratch = assemble(cb, phase, scratchOptions);
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

    /**
     * Checks that no column the fetch plan needs, a child's key or an enricher's column, is read through a to-many
     * join, where one row would hold several of its values: it resolves each on a scratch query and walks its joins
     * up to the root. Call it once per ModelQuery, on first execution, as {@link #checkPhases} (D-21).
     *
     * @throws ModelQueryDefinitionException {@code MQ1702} for a needed column read through a to-many join
     * @implSpec R-FCH-02
     */
    @EngineFacing
    public void checkFetch(CriteriaBuilder cb) {
        checkFetch(cb, UNLIMITED);
    }

    /** Checks fetch keys with the factory's renderers and without statement limits (R-FCH-02, R-COL-34). */
    @EngineFacing
    @Incubating
    public void checkFetch(CriteriaBuilder cb, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        if (needed.isEmpty() || source != null) {
            return; // a source's columns are the CTE's elements, none read through a join (R-SRC-10)
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        JoinContext joins = JoinContext.of(query.from(root.rootEntity()), cb, query, scratchRenderers(options));
        for (ColumnField<M, ?, ?> column : needed) {
            if (column.table().isJoinedSource()) {
                continue; // a joined source's column is its CTE's element, read through no join (R-SRC-21)
            }
            for (Path<?> path = column.path(joins); path != null; path = path.getParentPath()) {
                if (path instanceof Join<?, ?> join && join.getAttribute() instanceof PluralAttribute<?, ?, ?> many) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1702, column + ": the fetch plan needs it, but "
                            + "it is read through the to-many join " + many.getDeclaringType().getJavaType()
                            .getSimpleName() + "." + many.getName() + ", where one row holds several of its values; "
                            + "key the child or the enricher by a column on a to-one path");
                }
            }
        }
    }

    /**
     * Checks that this query can return the model of a {@code persist} from the flushed entity alone: it has no
     * clause but the selection, the mapper, {@code afterMap} and the finisher, a {@code where} or {@code having}
     * counting only when it recorded a filter, and each selected column resolves, on a scratch query, to a basic or
     * embedded root attribute, through embeddables, or the id of a to-one association joined from the root without
     * {@code on(...)}. Call it once per factory, on first execution, before any statement (D-118).
     *
     * @throws ModelQueryDefinitionException {@code MQ1809} naming the first clause, else the first column, refused;
     *     as {@link ColumnField#path} does for a column whose path does not resolve
     * @implSpec R-WRT-48
     */
    @EngineFacing
    public void checkReturning(CriteriaBuilder cb) {
        checkReturning(cb, UNLIMITED);
    }

    /** Checks returning columns with the factory's renderers (R-WRT-48, R-COL-34). */
    @EngineFacing
    @Incubating
    public void checkReturning(CriteriaBuilder cb, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        var clauses = new ArrayList<String>();
        if (!where.isEmpty()) {
            clauses.add("where(...)");
        }
        if (!having.isEmpty()) {
            clauses.add("having(...)");
        }
        if (!groupBy.isEmpty()) {
            clauses.add("groupBy(...)");
        }
        if (fetch != null) {
            clauses.add("a fetch plan");
        }
        if (customizer != null) {
            clauses.add("customize(...)");
        }
        if (!orderBy.isEmpty()) {
            clauses.add("orderBy(...)");
        }
        if (keyset) {
            clauses.add("keyset()");
        }
        if (primaryKeyFirst != null) {
            clauses.add("primaryKeyFirst(...)");
        }
        if (lock != null) {
            clauses.add("a lock");
        }
        if (!joinedSources.isEmpty()) {
            clauses.add("joinSource(...)");
        }
        if (!entityJoins.isEmpty()) {
            clauses.add("joinEntity(...)");
        }
        if (!joinFilters.isEmpty()) {
            clauses.add("joinFilter(...)");
        }
        if (!clauses.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1809, modelName() + ": persist returning a model takes "
                    + "only the selection, the mapper, afterMap and the finisher of its query, which has "
                    + String.join(", ", clauses) + "; build a query without them");
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> from = query.from(root.rootEntity());
        ReturningColumns.check(modelName(), modelColumns, from.getModel(),
                JoinContext.of(from, cb, query, scratchRenderers(options)));
    }

    private RenderOptions scratchRenderers(RenderOptions options) {
        return bound(UNLIMITED.withRenderersOf(Objects.requireNonNull(options, "options")));
    }

    /**
     * {@code options} with this query's param bindings, which every context of its statements reads; a source's body
     * renders with its own source's instead (R-COL-40).
     */
    private RenderOptions bound(RenderOptions options) {
        return options.withBindings(bindings);
    }

    /** The primary-key columns missing from {@code orderBy}, as the executor's keyset and stable order append them. */
    private List<OrderField<M, ?>> keyTieBreakers() {
        if (primaryKey == null || grouped) {
            return List.of();
        }
        boolean ascending = orderBy.isEmpty() || orderBy.get(orderBy.size() - 1).ascending();
        var tieBreakers = new ArrayList<OrderField<M, ?>>();
        for (ColumnField<M, ?, ?> column : primaryKey.columns()) {
            if (orderBy.stream().noneMatch(order -> order.column().equals(column))) {
                tieBreakers.add(new OrderField<>(column, ascending, NullPrecedence.DEFAULT));
            }
        }
        return tieBreakers;
    }

    void checkTreats(TreatChecks checks) {
        if (checks.countOnly()) {
            modelColumns.forEach(checks::joins);
            orderBy.forEach(order -> checks.joins(order.column()));
        } else {
            // A selected group key is one site, {SELECT, GROUP_BY}, as a grouped typeOf is measured (R-COL-36).
            checks.using(TreatUse.SELECT, () -> modelColumns.forEach(field -> {
                if (groupBy.contains(field)) {
                    checks.using(TreatUse.GROUP_BY, () -> checks.field(field));
                } else {
                    checks.field(field);
                }
            }));
            checks.using(TreatUse.ORDER, () -> orderBy.forEach(order -> checks.field(order.column())));
            checks.using(TreatUse.GROUP_BY, () -> groupBy.stream().filter(key -> !modelColumns.contains(key))
                    .forEach(checks::field));
            orderBy.forEach(checks::laterKey);
            // The executor closes an ordered content statement, a keyset's included, with the primary-key columns the
            // order lacks, as ascending as its last key and with the default precedence (R-PAG-01, R-PAG-06).
            List<OrderField<M, ?>> tieBreakers = keyTieBreakers();
            checks.using(TreatUse.ORDER, () -> tieBreakers.forEach(order -> checks.field(order.column())));
            tieBreakers.forEach(checks::laterKey);
        }
        conditions.where().forEach(checks::condition);
        conditions.having().forEach(checks::condition);
        if (checks.fetchesModels()) {
            checks.fetch(fetch);
        }
        checks.source(source);
    }

    /**
     * The model {@code attributeValues} fills: it gives each selected column's value by the column's
     * {@link ColumnField#path() attribute path}, as the entity holds it, before the column's converter; the
     * {@code RowMapper}, {@code afterMap} and the finisher then run as on a read. Only after {@link #checkReturning}
     * passed, so every selected column is a {@link ColumnField} (R-WRT-48).
     */
    @EngineFacing
    public M mapReturning(Function<String, Object> attributeValues) {
        Objects.requireNonNull(attributeValues, "attributeValues");
        return toModel(modelSelection.row(column -> attributeValues.apply(((ColumnField<?, ?, ?>) column).path())));
    }

    /**
     * The attribute paths this query's model rows hold, in order and each once, for a returning write: the executor
     * asks the provider for these columns of the written table and maps each returned row back by path (R-WRT-50).
     * Only after {@link #checkReturning} passed, so every selected column is a {@link ColumnField} (R-WRT-48).
     */
    @EngineFacing
    public List<String> returningPaths() {
        var paths = new LinkedHashSet<String>();
        // Raw attribute paths: a treated returning column never reaches here, refused MQ4016 before any statement.
        modelColumns.forEach(column -> paths.add(((ColumnField<?, ?, ?>) column).path()));
        return List.copyOf(paths);
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
     * What {@code MODEL} and {@code MODEL_BY_KEYS} select: the columns, the primary key of an ungrouped query,
     * every ordering and group key, the columns the fetch plan needs, and on an ungrouped query the presence key of
     * each {@code presentBy} join a selected or needed column is read through. None of the keys changes which rows
     * return, and their joins are made anyway; selecting them lets an executor read a row's key, cursor and group from
     * the row, and a mapper tell a join that missed from one that matched (R-QRY-04, D-29, D-38). A needed column
     * joins as a selected one does (R-FCH-02).
     */
    private List<SelectField<M, ?>> selected() {
        var result = new ArrayList<SelectField<M, ?>>(columns.fields());
        if (primaryKey != null) {
            result.addAll(primaryKey.columns());
        }
        orderBy.forEach(order -> result.add(order.column()));
        result.addAll(groupBy);
        // Selected as the keys are, so they count as selected for every rule that reads the selection (R-FCH-02).
        result.addAll(needed);
        if (!grouped) {
            // Last, and once each: a RowSelection keeps a column at its first position (R-QRY-04).
            var read = new ArrayList<SelectField<M, ?>>(columns.fields());
            read.addAll(needed);
            for (SelectField<M, ?> column : read) {
                if (column instanceof ColumnField<M, ?, ?> plain) {
                    result.addAll(presenceKeys(plain));
                }
            }
        }
        return result;
    }

    /** What {@code MODEL} and {@code MODEL_BY_KEYS} select, as {@link #selected()} lists it. */
    List<SelectField<M, ?>> modelColumns() {
        return modelColumns;
    }

    /**
     * The presence keys {@code column} is read through: the key columns of its own join and of every join above it
     * that names one with {@code presentBy}, each re-rooted under its join as a column of the column's model
     * (R-COL-15, D-38). No join is added by selecting them, since the column's own path already makes each.
     */
    private static <M> List<ColumnField<M, ?, ?>> presenceKeys(ColumnField<M, ?, ?> column) {
        var keys = new ArrayList<ColumnField<M, ?, ?>>();
        for (TableField<?, ?> join = column.table(); join != null; join = join.parent()) {
            if (join.presenceKey() != null) {
                for (ColumnField<?, ?, ?> key : join.presenceKey().columns()) {
                    keys.add(key.under(column.model(), join));
                }
            }
        }
        return keys;
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

    /** The definition's shape for the build log, by field name; never a filter's values (D-95). */
    private String describe() {
        var text = new StringBuilder("built ").append(modelName()).append(" over ")
                .append(root.rootEntity().getSimpleName()).append(": select ").append(names(columns.fields()));
        if (primaryKey != null) {
            text.append(", primaryKey ").append(names(primaryKey.columns()));
        }
        if (!where.isEmpty()) {
            text.append(", where ").append(conditions.where()); // each value as ?, never the value (R-INS-05)
        }
        if (grouped) {
            text.append(", groupBy ").append(names(groupBy));
            if (!having.isEmpty()) {
                text.append(", having ").append(conditions.having());
            }
        }
        if (!orderBy.isEmpty()) {
            var keys = new ArrayList<String>(orderBy.size());
            for (OrderField<M, ?> key : orderBy) {
                keys.add(key.column().name() + (key.ascending() ? " ASC" : " DESC")
                        + (key.nulls() == NullPrecedence.DEFAULT ? "" : " NULLS " + key.nulls()));
            }
            text.append(", orderBy ").append(keys);
        }
        text.append(", paging ").append(keyset ? "keyset" : "offset");
        if (primaryKeyFirst != null) {
            text.append(", primary-key-first above offset ").append(primaryKeyFirst.offsetThreshold());
        }
        if (!bindings.isEmpty()) {
            text.append(", binds ").append(bindings.keySet()); // each param by name and type, never its value
        }
        return text.toString();
    }

    private static List<String> names(List<? extends SelectField<?, ?>> fields) {
        return fields.stream().<String>map(SelectField::name).toList();
    }

    private record Spec(Class<?> rootEntity, List<SelectField<?, ?>> columns, Optional<PrimaryKey<?, ?>> primaryKey,
            List<OrderField<?, ?>> orderBy, boolean keyset, List<ScalarField<?, ?>> groupBy, boolean isGrouped)
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
        private final SelectSet<M> columns;
        private final PrimaryKey<M, K> primaryKey;
        private final List<OrderField<M, ?>> orderBy;
        private final boolean keyset;
        private final PrimaryKeyFirst primaryKeyFirst;
        private final BiConsumer<M, Row> afterMap;
        private final UnaryOperator<M> finisher;
        private final QueryCustomizer customizer;
        private final List<Filter> where;
        private final List<ScalarField<M, ?>> groupBy;
        private final HavingGroup.Clause having;
        /** The fetch plan, whose selection replaces {@link #columns}; {@code null} without one. */
        private final FetchPlan<M> fetch;
        /** The row lock, or {@code null} without one. */
        private final RowLock lock;
        /** The source the query reads, or {@code null} when it is rooted at an entity (R-SRC-10). */
        private final SourceQuery<E> source;
        /** The sources the query joins, in binding order (R-SRC-21). */
        private final List<JoinedSource> joinedSources;
        private final List<EntityJoin<M, ?>> entityJoins;
        private final Map<TableField<?, ?>, JoinFilterChecks.Binding> joinFilters;
        /** The value each {@link QueryParam} is bound to, by param identity, in bind order (R-COL-40). */
        private final Map<QueryParam<?>, Object> bindings;
        /** Kept by {@link #bind} alone; every other change makes a new one (D-201). */
        private final DefinitionKey definitionKey;

        private Builder(TableField<E, E> root, RowMapper<M> mapper, SelectSet<M> columns,
                PrimaryKey<M, K> primaryKey, List<OrderField<M, ?>> orderBy, boolean keyset,
                PrimaryKeyFirst primaryKeyFirst, BiConsumer<M, Row> afterMap, UnaryOperator<M> finisher,
                QueryCustomizer customizer, List<Filter> where, List<ScalarField<M, ?>> groupBy,
                HavingGroup.Clause having, FetchPlan<M> fetch, RowLock lock, SourceQuery<E> source,
                List<JoinedSource> joinedSources, List<EntityJoin<M, ?>> entityJoins,
                Map<TableField<?, ?>, JoinFilterChecks.Binding> joinFilters, Map<QueryParam<?>, Object> bindings,
                DefinitionKey definitionKey) {
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
            this.fetch = fetch;
            this.lock = lock;
            this.source = source;
            this.joinedSources = joinedSources;
            this.entityJoins = entityJoins;
            this.joinFilters = joinFilters;
            this.bindings = bindings;
            this.definitionKey = definitionKey != null ? definitionKey : new DefinitionKey();
        }

        /**
         * Joins {@code source} to every statement this query builds, as {@code join} declares: a join to the source's
         * CTE on its full key, whose columns {@code ColumnField.over(model, join.root(), field)} reads (R-SRC-21,
         * R-SRC-22). The generated {@code query(...)} of a model with a {@code @JoinSource} takes each source it joins
         * and calls this; the bindings are checked when the query is built.
         *
         * @see #build()
         */
        @Incubating
        public <S> Builder<E, K, M> joinSource(SourceJoin<M, S> join, SourceQuery<S> source) {
            var bound = new ArrayList<>(joinedSources);
            bound.add(new JoinedSource(Objects.requireNonNull(join, "join"), Objects.requireNonNull(source, "source")));
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch, lock, this.source, List.copyOf(bound),
                    entityJoins, joinFilters, bindings, null);
        }

        /**
         * Binds an entity to one source already joined by this query, using its full id (R-SRC-23, R-SRC-24).
         * The definition and its pairs remain immutable; statement state lives in {@link JoinContext}.
         *
         * @see #build()
         */
        @Incubating
        public Builder<E, K, M> joinEntity(EntityJoin<M, ?> join) {
            var bound = new ArrayList<>(entityJoins);
            bound.add(Objects.requireNonNull(join, "join"));
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch, lock, source, joinedSources,
                    List.copyOf(bound), joinFilters, bindings, null);
        }

        /**
         * Binds a request filter to this LEFT entity join's ON, replacing its previous filter (R-COL-39).
         * The operator runs once here; only its recorded terms are kept.
         */
        @Incubating
        public Builder<E, K, M> joinFilter(EntityJoin<M, ?> join, UnaryOperator<Filters<M>> on) {
            Objects.requireNonNull(join, "join");
            return joinFilter(join.root(), on, join.kind());
        }

        /**
         * Binds a request filter to this LEFT to-one join's ON, replacing its previous filter (R-COL-39).
         * This does not create the join; the operator runs once here and a null value raises MQ1301.
         */
        @Incubating
        public Builder<E, K, M> joinFilter(TableField<?, ?> table, UnaryOperator<Filters<M>> on) {
            return joinFilter(table, on, null);
        }

        private Builder<E, K, M> joinFilter(TableField<?, ?> table, UnaryOperator<Filters<M>> on, JoinKind kind) {
            Objects.requireNonNull(table, "table");
            List<Filter> terms = FilterGroup.collect(root.rootEntity(), Objects.requireNonNull(on, "on"));
            var bound = new LinkedHashMap<>(joinFilters);
            bound.put(table, new JoinFilterChecks.Binding(terms, kind));
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch, lock, source, joinedSources, entityJoins,
                    Collections.unmodifiableMap(bound), bindings, null);
        }

        /**
         * Binds {@code param} to {@code value} for every statement this query renders, replacing an earlier binding of
         * it (R-COL-40). The value is held on the built query, never on the param or the expression reading it, so a
         * {@code static final} param serves any number of queries (INV-9). A {@code Correlated} sub-select reads this
         * binding; a source's body reads its own {@link SourceQuery}'s. A param bound and read by nothing is allowed.
         * The queries built through {@code bind} alone from one builder share their {@link ModelQuery#definitionKey()},
         * so a definition held in a {@code static final} builder and bound per request is checked, and warned of, once
         * (D-201).
         *
         * @throws ModelQueryDefinitionException {@code MQ1502} when {@code value} is {@code null}, not an instance of
         *     the param's type, or of a type no expression binds (R-COL-18)
         * @see #build()
         */
        @Incubating
        public <C> Builder<E, K, M> bind(QueryParam<C> param, C value) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch, lock, source, joinedSources, entityJoins,
                    joinFilters, ParamReads.bind(bindings, param, value), definitionKey);
        }

        /**
         * What the query selects; this or {@link #fetch} is required. It replaces a fetch plan set before, which is
         * then dropped with a warning: the last of {@code select} and {@code fetch} wins as a whole (R-FCH-02).
         */
        public Builder<E, K, M> select(SelectSet<M> select) {
            Objects.requireNonNull(select, "select");
            if (fetch != null) {
                LOG.log(System.Logger.Level.WARNING, "{0}: select(...) after fetch(...) discards the fetch plan, so "
                        + "no child, join plan or enricher of it runs; call fetch(...) last, with the selection in "
                        + "its FetchPlan.of(...)", modelName(fetch.select(), root.rootEntity()));
            }
            return new Builder<>(root, mapper, select, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, null, lock, source, joinedSources, entityJoins,
                    joinFilters, bindings, null);
        }

        /**
         * Selects {@code plan}'s selection and attaches the plan, replacing any {@link #select} or plan set before. The
         * query also selects every column the plan needs, which {@link ModelQuery#select()} leaves out (R-FCH-02).
         */
        @Incubating
        public Builder<E, K, M> fetch(FetchPlan<M> plan) {
            return new Builder<>(root, mapper, null, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, Objects.requireNonNull(plan, "plan"), lock,
                    source, joinedSources, entityJoins, joinFilters, bindings, null);
        }

        /**
         * The builder of a child load's query: {@code plan} as {@link #fetch} attaches one, the child filters as its
         * {@code where} and the child order as its {@code orderBy}, each replacing what was set before (R-FCH-04).
         * It takes no lock: a fetch plan's statements are not locked (R-QRY-17, D-127).
         */
        Builder<E, K, M> forChildren(FetchPlan<M> plan, List<Filter> where, List<OrderField<M, ?>> orderBy) {
            return new Builder<>(root, mapper, null, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, plan, null, source, joinedSources, entityJoins,
                    joinFilters, bindings, null);
        }

        /** The primary key; required for {@link #keyset()} and {@link #primaryKeyFirst}. */
        public <K2> Builder<E, K2, M> primaryKey(PrimaryKey<M, K2> primaryKey) {
            return new Builder<>(root, mapper, columns, Objects.requireNonNull(primaryKey, "primaryKey"), orderBy,
                    keyset, primaryKeyFirst, afterMap, finisher, customizer, where, groupBy, having, fetch, lock,
                    source, joinedSources, entityJoins, joinFilters, bindings, null);
        }

        /** The ordering keys, replacing any set before. */
        @SafeVarargs
        public final Builder<E, K, M> orderBy(OrderField<M, ?>... orderBy) {
            var copy = new ArrayList<OrderField<M, ?>>();
            for (OrderField<M, ?> order : Objects.requireNonNull(orderBy, "orderBy")) {
                copy.add(Objects.requireNonNull(order, "orderBy element"));
            }
            return new Builder<>(root, mapper, columns, primaryKey, List.copyOf(copy), keyset, primaryKeyFirst,
                    afterMap, finisher, customizer, where, groupBy, having, fetch, lock, source, joinedSources,
                    entityJoins, joinFilters, bindings, null);
        }

        /**
         * Allows keyset paging; needs a primary key and an ungrouped query ({@code MQ1201} and {@code MQ1402} at
         * {@link #build()} otherwise).
         */
        public Builder<E, K, M> keyset() {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, true, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch, lock, source, joinedSources, entityJoins,
                    joinFilters, bindings, null);
        }

        /**
         * Allows two-step deep paging; needs a primary key and an ungrouped query ({@code MQ1201} and {@code MQ1402} at
         * {@link #build()} otherwise).
         */
        public Builder<E, K, M> primaryKeyFirst(PrimaryKeyFirst primaryKeyFirst) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset,
                    Objects.requireNonNull(primaryKeyFirst, "primaryKeyFirst"), afterMap, finisher, customizer,
                    where, groupBy, having, fetch, lock, source, joinedSources, entityJoins, joinFilters, bindings,
                    null);
        }

        /**
         * Runs {@code afterMap} once per row after the {@code RowMapper}, to fill a field derived from other mapped
         * values. It must not query, mutate shared state or throw for ordinary data (R-QRY-06). For a record use
         * {@link #finisher(UnaryOperator)}.
         */
        public Builder<E, K, M> afterMap(BiConsumer<M, Row> afterMap) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst,
                    Objects.requireNonNull(afterMap, "afterMap"), finisher, customizer, where, groupBy, having, fetch,
                    lock, source, joinedSources, entityJoins, joinFilters, bindings, null);
        }

        /** Replaces each mapped model with {@code finisher}'s result, after {@code afterMap}; for records. */
        public Builder<E, K, M> finisher(UnaryOperator<M> finisher) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    Objects.requireNonNull(finisher, "finisher"), customizer, where, groupBy, having, fetch, lock,
                    source, joinedSources, entityJoins, joinFilters, bindings, null);
        }

        /** Raw Criteria access for each phase; see {@link QueryCustomizer}. Replaces any customizer set before. */
        public Builder<E, K, M> customize(QueryCustomizer customizer) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, Objects.requireNonNull(customizer, "customizer"), where, groupBy, having, fetch, lock,
                    source, joinedSources, entityJoins, joinFilters, bindings, null);
        }

        /** The row lock every read of this query takes, replacing any set before (R-QRY-17). */
        @Incubating
        public Builder<E, K, M> lock(RowLock lock) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch, Objects.requireNonNull(lock, "lock"),
                    source, joinedSources, entityJoins, joinFilters, bindings, null);
        }

        /**
         * The {@code WHERE} predicate: {@code filters} receives an empty {@link Filters}, and every filter it adds is
         * ANDed. It runs once, here, so a {@code null} value fails now with {@code MQ1301}; the query keeps only what
         * it recorded. Replaces any predicate set before. Without it the query has no predicate (api/11 §5).
         */
        public Builder<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer,
                    FilterGroup.collect(root.rootEntity(), Objects.requireNonNull(filters, "filters")), groupBy,
                    having, fetch, lock, source, joinedSources, entityJoins, joinFilters, bindings, null);
        }

        /**
         * Groups the query by the columns of {@code columns}, replacing any group-by set before. Passing the set the
         * group keys are selected from keeps the two in step (R-AGG-05). Every selected column must be among them,
         * else {@link #build()} throws {@code MQ1401}; an empty set leaves the query ungrouped (R-AGG-07).
         *
         * @throws ModelQueryDefinitionException {@code MQ1404} when {@code columns} holds an {@link AggregateField},
         *     which cannot be a group key
         */
        public Builder<E, K, M> groupBy(SelectSet<M> columns) {
            var keys = new ArrayList<ScalarField<M, ?>>();
            for (SelectField<M, ?> column : Objects.requireNonNull(columns, "columns").fields()) {
                if (column instanceof AggregateField<?, ?>) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1404, modelName(columns, root.rootEntity()) + "."
                            + column.name() + ": groupBy(...) takes columns; an aggregate cannot be a group key");
                }
                keys.add(groupKey(column));
            }
            return groupBy(keys);
        }

        /** Groups the query by {@code columns}, as {@link #groupBy(SelectSet)} does (R-AGG-05). */
        @SafeVarargs
        public final Builder<E, K, M> groupBy(ScalarField<M, ?>... columns) {
            var keys = new ArrayList<ScalarField<M, ?>>();
            for (ScalarField<M, ?> column : Objects.requireNonNull(columns, "columns")) {
                keys.add(groupKey(Objects.requireNonNull(column, "columns element")));
            }
            return groupBy(keys);
        }

        /** One group key, which may be an expression (R-AGG-05, R-COL-19). */
        private ScalarField<M, ?> groupKey(SelectField<M, ?> column) {
            if (ExpressionField.containsCorrelated(column)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1314,
                        column + ": a correlated value cannot be a group key; group by its lifted columns");
            }
            return (ScalarField<M, ?>) column;
        }

        private Builder<E, K, M> groupBy(List<ScalarField<M, ?>> keys) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, List.copyOf(new LinkedHashSet<>(keys)), having, fetch, lock,
                    source, joinedSources, entityJoins, joinFilters, bindings, null);
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
                    HavingGroup.collect(Objects.requireNonNull(filters, "filters")), fetch, lock,
                    source, joinedSources, entityJoins, joinFilters, bindings, null);
        }

        /**
         * Builds the immutable query. A query is grouped when it has a group-by or selects an aggregate (R-AGG-07); a
         * grouped query needs no primary key and ignores one (R-AGG-09).
         *
         * @throws ModelQueryDefinitionException {@code MQ1202} when neither {@link #select} nor {@link #fetch} was
         *     called
         * @throws ModelQueryDefinitionException {@code MQ1705} for a join plan selecting an aggregate
         * @throws ModelQueryDefinitionException {@code MQ1407} for {@code having(...)} on an ungrouped query
         * @throws ModelQueryDefinitionException {@code MQ1402} for {@code keyset()} or {@code primaryKeyFirst(...)} on
         *     a grouped query
         * @throws ModelQueryDefinitionException {@code MQ1901} for {@code keyset()} or {@code primaryKeyFirst(...)}
         *     over a non-unique source (R-SRC-09)
         * @throws ModelQueryDefinitionException {@code MQ1905} for {@link #joinSource} on a query rooted at a source
         * @throws ModelQueryDefinitionException {@code MQ1910} for a {@link #joinSource} binding whose {@code on}
         *     pairs miss or exceed the source's key, over a non-unique or recursive source, or a join bound twice
         * @throws ModelQueryDefinitionException {@code MQ1201} for {@code keyset()} or {@code primaryKeyFirst(...)}
         *     without a primary key
         * @throws ModelQueryDefinitionException {@code MQ1206} for a primary-key column of array type
         * @throws ModelQueryDefinitionException {@code MQ1207} for {@code keyset()} with a {@code Float} or
         *     {@code Double} order or primary-key column
         * @throws ModelQueryDefinitionException {@code MQ1704} for a fetch plan loading a child on a grouped query
         * @throws ModelQueryDefinitionException {@code MQ1401} for a selected column, or a column an enricher reads,
         *     missing from the group-by
         * @throws ModelQueryDefinitionException {@code MQ1409} for a grouped query selecting a column under a
         *     {@code presentBy} join whose key columns are not all group keys
         * @throws ModelQueryDefinitionException {@code MQ1410} (when rendered, before any statement) for a group key
         *     that reads a whole-entity to-one, not its id
         * @throws ModelQueryDefinitionException {@code MQ1406} for an ordering key that does not fit the grouping
         * @throws ModelQueryDefinitionException {@code MQ1103} for two {@code Agg.of} fields sharing a name with
         *     different expressions
         * @throws ModelQueryDefinitionException {@code MQ1701} for a join plan whose join has no selected column
         */
        public ModelQuery<E, K, M> build() {
            return build(null);
        }

        /** This builder with {@code key} as its definition key: a copy's, derived from its query's (D-201). */
        private Builder<E, K, M> keyed(DefinitionKey key) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch, lock, source, joinedSources, entityJoins,
                    joinFilters, bindings, key);
        }

        /** This builder with {@code orderBy}, an immutable list, as its ordering keys. */
        private Builder<E, K, M> ordered(List<OrderField<M, ?>> orderBy) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch, lock, source, joinedSources, entityJoins,
                    joinFilters, bindings, null);
        }

        /** Checks and builds; {@code definition} is the query this one is a re-ordered copy of, or null. */
        private ModelQuery<E, K, M> build(ModelQuery<E, K, M> definition) {
            if (columns == null && fetch == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1202,
                        "select(...) or fetch(...) is required for a query on " + root.rootEntity().getSimpleName());
            }
            SelectSet<M> selection = selection();
            List<ColumnField<M, ?, ?>> needed = needed();
            if (root.isSource()) {
                var vocabulary = new SourceVocabulary(root.rootEntity(), root, source, sourceReadModel(selection));
                selection.fields().forEach(vocabulary::field);
                vocabulary.filters(where);
                orderBy.forEach(order -> vocabulary.field(order.column()));
                groupBy.forEach(vocabulary::field);
                if (primaryKey != null) {
                    primaryKey.columns().forEach(vocabulary::field);
                }
                if (having != null) {
                    vocabulary.filters(having.filters());
                }
            }
            String model = modelName(selection, root.rootEntity());
            checkJoinedSources(model);
            checkEntityJoins(model);
            if (!joinFilters.isEmpty()) {
                var checks = new JoinFilterChecks(joinFilters, entityJoins);
                selection.fields().forEach(checks::field);
                needed.forEach(checks::field);
                orderBy.forEach(order -> checks.field(order.column()));
                groupBy.forEach(checks::field);
                if (primaryKey != null) {
                    primaryKey.columns().forEach(checks::field);
                }
                ConditionGroup.conditions(where).forEach(checks::condition);
                if (having != null) {
                    ConditionGroup.conditions(having.filters()).forEach(checks::condition);
                }
            }
            boolean grouped = isGrouped(selection);
            if (!grouped && having != null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1407, model + ": having(...) needs a grouped query; "
                        + "add groupBy(...) or select an aggregate, or filter rows with where(...)");
            }
            if (lock != null && grouped) {
                // A group is not a row: a locking clause against one either renders nothing or locks a whole table
                // (R-QRY-17, D-127).
                throw new ModelQueryDefinitionException(MqCode.MQ1209,
                        model + ": a lock on a grouped query has no row to lock; lock an ungrouped query");
            }
            if (keyset || primaryKeyFirst != null) {
                String used = keyset ? "keyset()" : "primaryKeyFirst(...)";
                if (source != null && !source.unique()) {
                    // A cursor identifies a row by its key, which a non-unique source's key does not (R-SRC-09).
                    throw new ModelQueryDefinitionException(MqCode.MQ1901, model + ": " + used + " is refused on the "
                            + "source " + source.model().getSimpleName() + ", whose rows have no unique "
                            + "key; page it by offset or make the branches disjoint with @Discriminator"
                            + (source.recursive() ? "; use tree(...) for a checked recursive walk" : ""));
                }
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
            if (keyset) {
                checkKeysetExpression();
            }
            if (primaryKey != null) {
                checkKeyTypes(keyset);
            }
            if (grouped) {
                selection.fields().forEach(field -> ExpressionField.checkCorrelatedGroup(field, groupBy));
                orderBy.forEach(order -> ExpressionField.checkCorrelatedGroup(order.column(), groupBy));
                String child = fetch == null ? null : fetch.firstChild();
                if (child != null) {
                    // Before MQ1401, which the child's key would otherwise raise first (R-FCH-10).
                    throw new ModelQueryDefinitionException(MqCode.MQ1704, model + "." + child + ": a fetch plan "
                            + "loads this child on a grouped query, whose rows have no single key to match children "
                            + "on; load it from an ungrouped query");
                }
                for (SelectField<M, ?> column : selection.fields()) {
                    // MySQL would return an arbitrary value of the group; PostgreSQL would fail anonymously (R-AGG-08).
                    if (column instanceof ScalarField<M, ?> scalar && !fitsGroup(scalar)) {
                        throw new ModelQueryDefinitionException(MqCode.MQ1401,
                                scalar + ": selected but not in groupBy");
                    }
                }
                for (ColumnField<M, ?, ?> column : needed) {
                    if (!groupBy.contains(column)) {
                        throw new ModelQueryDefinitionException(MqCode.MQ1401, column + ": read by an enricher of the "
                                + "fetch plan but not in groupBy; an enricher on a grouped query reads group keys");
                    }
                }
                if (primaryKey != null) {
                    LOG.log(System.Logger.Level.DEBUG, "{0}: primaryKey(...) is ignored on a grouped query", model);
                }
                checkPresenceKeys(selection, needed);
            }
            checkOrder(model, grouped);
            checkAggregates(selection, model);
            checkParams(model, selection, needed);
            ModelQuery<E, K, M> built = new ModelQuery<>(this, selection, needed, grouped, definition);
            if (fetch != null) {
                checkJoinPlans(built, model);
            }
            // Only the definition: an orderedBy copy is built per call, and the executor logs each call (D-95).
            if (definition == null && LOG.isLoggable(System.Logger.Level.DEBUG)) {
                LOG.log(System.Logger.Level.DEBUG, built.describe());
            }
            return built;
        }

        /** Checks only definition facts; the metamodel checks run before the first statement (R-SRC-23). */
        private void checkEntityJoins(String model) {
            var names = new HashSet<String>();
            var sources = new HashSet<JoinKey>();
            for (JoinedSource bound : joinedSources) {
                names.add(bound.join().name());
                sources.add(bound.join().root().key());
            }
            for (EntityJoin<M, ?> join : entityJoins) {
                if (!names.add(join.name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1911, model + ": entity join '" + join.name()
                            + "' is bound twice or shares a source join's name");
                }
                EntityJoin.check(model, join, sources, root);
            }
        }

        /**
         * The {@code MQ1905} and {@code MQ1910} checks of the {@link #joinSource} bindings, one recursive walk across
         * all their chains included (R-SRC-21, D-187).
         */
        private void checkJoinedSources(String model) {
            if (joinedSources.isEmpty()) {
                return;
            }
            if (root.isSource()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1905, model + ": joinSource(...) takes a query "
                        + "rooted at an entity; " + root.describe() + " is a source root, which joins nothing");
            }
            var names = new HashSet<String>();
            for (JoinedSource bound : joinedSources) {
                if (!names.add(bound.join().name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1910, model + ": the source join '"
                            + bound.join().name() + "' is bound twice; bind each join once");
                }
                SourceJoin.check(model, bound.join(), bound.source());
            }
            // A statement selects one walk's checks under fixed aliases, read by the one check site (R-SRC-14, D-187).
            int walks = joinedSources.stream().mapToInt(bound -> bound.source().walks()).sum();
            if (walks > 1) {
                throw new ModelQueryDefinitionException(MqCode.MQ1910, model + ": the joined sources walk " + walks
                        + " recursive sources, and a statement carries one walk's depth and cycle checks; join one "
                        + "recursive source per query");
            }
        }

        /**
         * Builds without the checks of {@link #build()}, for a {@link ModelQuery#withFetch} copy whose query and plan
         * passed them before, once the earlier copy was collected (R-FCH-13).
         */
        private ModelQuery<E, K, M> unchecked() {
            SelectSet<M> selection = selection();
            // The params the plan's selection reads are this request's, so they are checked on every copy.
            checkParams(modelName(selection, root.rootEntity()), selection, needed());
            return new ModelQuery<>(this, selection, needed(), isGrouped(selection), null);
        }

        /**
         * {@code MQ1512} when an expression this query renders reads a {@link QueryParam} it doesn't bind, or two
         * params with one name: its selection, the fetch plan's columns, order, group keys, primary key, filters,
         * {@code having} and join filters (R-COL-40). A joined or rooted source's body binds its own.
         */
        private void checkParams(String model, SelectSet<M> selection, List<ColumnField<M, ?, ?>> needed) {
            var reads = new ParamReads(model);
            selection.fields().forEach(reads::field);
            needed.forEach(reads::field);
            orderBy.forEach(order -> reads.field(order.column()));
            groupBy.forEach(reads::field);
            if (primaryKey != null) {
                primaryKey.columns().forEach(reads::field);
            }
            ConditionGroup.conditions(where).forEach(reads::condition);
            if (having != null) {
                ConditionGroup.conditions(having.filters()).forEach(reads::condition);
            }
            joinFilters.values().forEach(binding -> ConditionGroup.conditions(binding.terms())
                    .forEach(reads::condition));
            reads.checkBound(bindings);
        }

        /** The plan's selection, its join plans' re-rooted under their joins, or else what select(...) was given. */
        private SelectSet<M> selection() {
            return fetch == null ? columns : fetch.selection();
        }

        /** The columns the fetch plan needs besides its selection (R-FCH-02). */
        private List<ColumnField<M, ?, ?>> needed() {
            return fetch == null ? List.of() : List.copyOf(fetch.needed());
        }

        /** Only correctly rooted columns identify the erased read model; a definition column cannot do so. */
        private Class<?> sourceReadModel(SelectSet<M> selection) {
            var fields = new ArrayList<SelectField<?, ?>>(selection.fields());
            if (primaryKey != null) {
                fields.addAll(primaryKey.columns());
            }
            return fields.stream().filter(ColumnField.class::isInstance)
                    .map(field -> (ColumnField<?, ?, ?>) field)
                    .filter(field -> field.table().key().equals(root.key()))
                    .map(ColumnField::model).findFirst().orElse(null);
        }

        /** Structural, so skipping every having filter cannot turn a plain query into a grouped one (D-28). */
        private boolean isGrouped(SelectSet<M> selection) {
            return !groupBy.isEmpty() || selection.fields().stream().anyMatch(AggregateField.class::isInstance);
        }

        /**
         * {@code keyset()} pages by attribute values: its cursor, fingerprint and bind budget are defined over them,
         * so an expression order key is refused at build, naming it (R-QRY-16).
         */
        private void checkKeysetExpression() {
            for (OrderField<M, ?> order : orderBy) {
                if (order.column() instanceof ExpressionField<?, ?>) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1208, order.column() + ": keyset() cannot "
                            + "order by an expression, whose cursor, fingerprint and bind budget are defined over "
                            + "attribute values; page or export by offset instead");
                }
            }
        }

        /**
         * Whether {@code field} fits a grouped query's group-by (R-AGG-14): it equals a group key ignoring
         * {@code named}, or it is an expression every one of whose columns is a group-key column. A sub-expression is
         * never matched to a group key, since a database that matches by text cannot equal two bound parameters.
         */
        private boolean fitsGroup(ScalarField<M, ?> field) {
            // A type read is per row, so an expression reading one fits only as a group key itself (R-COL-36).
            return groupBy.contains(field)
                    || field instanceof ExpressionField<?, ?> expression
                            && ExpressionField.typeReads(expression).isEmpty()
                            && expression.columns().stream().allMatch(groupBy::contains);
        }

        /**
         * Each join with a plan has a column of the query's selection at or below it: otherwise the join reads no
         * nested model for its plan to apply to (R-FCH-07).
         */
        private void checkJoinPlans(ModelQuery<E, K, M> built, String model) {
            fetch.joinTables().forEach((path, join) -> {
                boolean selected = built.modelColumns().stream().anyMatch(
                        column -> column instanceof ColumnField<M, ?, ?> plain && plain.table().isAtOrBelow(join));
                if (!selected) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1701, model + "." + path + ": the join plan "
                            + "selects no column under its join, which then reads no nested model; select a column "
                            + "of the nested model in its plan, or drop the join(...)");
                }
            });
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
                // The key is read as the attribute's value, whatever a converter makes of it (R-COL-11).
                if (column.attributeType().isArray()) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1206, column + ": a primary-key column of type "
                            + column.attributeType().getSimpleName() + " cannot identify a row, since an array "
                            + "equals only itself; key the query by a column of a value type");
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
            // The cursor is the attribute's value, whatever a converter makes of it (R-COL-11).
            Class<?> type = column instanceof ColumnField<M, ?, ?> plain ? plain.attributeType() : column.type();
            if (type == Float.class || type == Double.class) {
                throw new ModelQueryDefinitionException(MqCode.MQ1207, column + ": keyset() cannot page by a "
                        + type.getSimpleName() + " column, whose cursor value need not compare equal to "
                        + "the stored one; order by an exact type such as BigDecimal, or export by offset");
            }
        }

        /**
         * A grouped query adds no presence key, so every key column of a {@code presentBy} join a selected column is
         * read through must be a group key: otherwise the nested model would map as absent (R-AGG-09, D-38).
         */
        private void checkPresenceKeys(SelectSet<M> selection, List<ColumnField<M, ?, ?>> needed) {
            var read = new ArrayList<SelectField<M, ?>>(selection.fields());
            read.addAll(needed);
            for (SelectField<M, ?> column : read) {
                if (column instanceof ColumnField<M, ?, ?> plain) {
                    for (ColumnField<M, ?, ?> key : presenceKeys(plain)) {
                        if (!groupBy.contains(key)) {
                            throw new ModelQueryDefinitionException(MqCode.MQ1409, plain + ": selected on a grouped "
                                    + "query under the presentBy " + key.table().describe() + ", whose key column "
                                    + key.name() + " is not in groupBy; a grouped query adds no presence key, so "
                                    + "group by the key or select the column through a join without presentBy");
                        }
                    }
                }
            }
        }

        /** Each ordering key fits the grouping: a group key or an aggregate if grouped, else no aggregate (D-28). */
        private void checkOrder(String model, boolean grouped) {
            for (OrderField<M, ?> order : orderBy) {
                SelectField<M, ?> key = order.column();
                if (grouped && key instanceof ScalarField<M, ?> scalar && !fitsGroup(scalar)) {
                    // A group holds many values of the column or expression: MySQL would sort by an arbitrary one.
                    throw new ModelQueryDefinitionException(MqCode.MQ1406, scalar + ": ordered by but not in "
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
        private void checkAggregates(SelectSet<M> selection, String model) {
            var named = new ArrayList<SelectField<?, ?>>(selection.fields());
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

        private static String modelName(SelectSet<?> columns, Class<?> entity) {
            for (SelectField<?, ?> column : columns.fields()) {
                if (column instanceof ColumnField<?, ?, ?> field) {
                    return field.model().getSimpleName();
                }
            }
            return entity.getSimpleName();
        }
    }
}
