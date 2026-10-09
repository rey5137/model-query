package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.metamodel.PluralAttribute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        // An orderedBy copy differs in its order alone, which the view leaves out, so it shares the view (R-INS-04).
        this.conditions = definition != null ? definition.conditions
                : new QueryConditions(ConditionGroup.conditions(where), ConditionGroup.conditions(having));
        this.grouped = grouped;
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
                null, null);
    }

    /** The entity the query is rooted at. */
    public Class<E> rootEntity() {
        return root.rootEntity();
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
        Builder<E, K, M> fetching = builder.fetch(plan);
        ModelQuery<E, K, M> built = CheckedFetches.passed(this, plan) ? fetching.unchecked() : fetching.build();
        CheckedFetches.pass(this, plan, built); // only once it passed, so a plan that threw is checked again next time
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
     * (D-21).
     *
     * @implSpec R-QRY-14
     */
    @Incubating
    public ModelQuery<E, K, M> definition() {
        return definition;
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
     * {@code PRIMARY_KEY} selects the key columns only. The customizer, if any, runs last. A new {@link JoinContext}
     * is created per call and carries {@code options}, the facts about the target database the build renders by
     * ({@link RenderOptions#portable()} when unknown, D-34).
     *
     * @throws ModelQueryExecutionException {@code MQ2203} for {@code PRIMARY_KEY} on a query without a primary key
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changed the ordering or the grouping
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

    /**
     * The {@code MODEL} statement of a {@code through} child load (R-FCH-14, D-100): rooted at the parent's entity,
     * where {@code through} starts, and joined {@code INNER} along it before anything else, so that no {@code or(...)}
     * turns those joins {@code LEFT} (R-FLT-10). The model then resolves as {@link #buildQuery} resolves it, against a
     * context whose root is the path's last join, and {@code parentKey}, read on the parent's root, is selected last.
     * The customizer, if any, runs last, its {@code query.getRoots()} holding the parent's entity.
     *
     * @throws ModelQueryDefinitionException {@code MQ1205} when the customizer changed the ordering or the grouping
     */
    BuiltQuery<M> buildThroughQuery(CriteriaBuilder cb, RenderOptions options, TableField<?, ?> through,
            ColumnField<?, ?, ?> parentKey) {
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        JoinContext parent = JoinContext.readOf(query.from(through.pathRoot()), cb, query, options);
        From<?, ?> join = through.resolve(parent);
        Expression<?> key = parentKey.path(parent);
        BuiltQuery<M> built = assemble(query, parent.rootedAt(join, root.rootEntity()), Phase.MODEL, key);
        if (customizer != null) {
            customize(built, cb, Phase.MODEL);
        }
        return built;
    }

    /** Selection, predicate, grouping and ordering of {@code phase}, before any customizer runs. */
    private BuiltQuery<M> assemble(CriteriaBuilder cb, Phase phase, RenderOptions options) {
        if (phase == Phase.PRIMARY_KEY && primaryKey == null) {
            throw new ModelQueryExecutionException(MqCode.MQ2203, modelName() + ": phase PRIMARY_KEY needs a primary "
                    + "key, and " + (grouped ? "a grouped query has none (R-AGG-09)" : "primaryKey(...) was not set"));
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> from = query.from(root.rootEntity());
        return assemble(query, JoinContext.readOf(from, cb, query, options), phase, null);
    }

    /**
     * {@link #assemble(CriteriaBuilder, Phase, RenderOptions)} on {@code query}, resolving through {@code joins};
     * {@code parentKey}, when not {@code null}, is selected after the model's columns.
     */
    private BuiltQuery<M> assemble(CriteriaQuery<Tuple> query, JoinContext joins, Phase phase,
            Expression<?> parentKey) {
        CriteriaBuilder cb = joins.cb();
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
        query.multiselect(selections);
        List<Order> orders = new ArrayList<>();
        for (OrderField<M, ?> order : orderBy) {
            orders.addAll(order.toOrders(joins, cb, !groupBy.isEmpty()));
        }
        if (!orders.isEmpty()) {
            query.orderBy(orders);
        }
        if (!groupBy.isEmpty()) {
            query.groupBy(groupBy.stream().<Expression<?>>map(key -> key.expression(joins)).toList());
        }
        List<List<Predicate>> predicates = ConditionGroup.clausePredicates(List.of(where, having), joins);
        if (!predicates.get(0).isEmpty()) {
            query.where(predicates.get(0).toArray(Predicate[]::new));
        }
        if (!predicates.get(1).isEmpty()) {
            query.having(predicates.get(1).toArray(Predicate[]::new));
        }
        return new BuiltQuery<>(query, joins, selection, mapping, parentKey);
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
    @EngineFacing
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
        Objects.requireNonNull(cb, "cb");
        if (needed.isEmpty()) {
            return;
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        JoinContext joins = JoinContext.of(query.from(root.rootEntity()), cb, query, UNLIMITED);
        for (ColumnField<M, ?, ?> column : needed) {
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
        if (!clauses.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1809, modelName() + ": persist returning a model takes "
                    + "only the selection, the mapper, afterMap and the finisher of its query, which has "
                    + String.join(", ", clauses) + "; build a query without them");
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> from = query.from(root.rootEntity());
        ReturningColumns.check(modelName(), modelColumns, from.getModel(), JoinContext.of(from, cb, query, UNLIMITED));
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

        private Builder(TableField<E, E> root, RowMapper<M> mapper, SelectSet<M> columns,
                PrimaryKey<M, K> primaryKey, List<OrderField<M, ?>> orderBy, boolean keyset,
                PrimaryKeyFirst primaryKeyFirst, BiConsumer<M, Row> afterMap, UnaryOperator<M> finisher,
                QueryCustomizer customizer, List<Filter> where, List<ScalarField<M, ?>> groupBy,
                HavingGroup.Clause having, FetchPlan<M> fetch) {
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
                    finisher, customizer, where, groupBy, having, null);
        }

        /**
         * Selects {@code plan}'s selection and attaches the plan, replacing any {@link #select} or plan set before. The
         * query also selects every column the plan needs, which {@link ModelQuery#select()} leaves out (R-FCH-02).
         */
        @Incubating
        public Builder<E, K, M> fetch(FetchPlan<M> plan) {
            return new Builder<>(root, mapper, null, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, Objects.requireNonNull(plan, "plan"));
        }

        /**
         * The builder of a child load's query: {@code plan} as {@link #fetch} attaches one, the child filters as its
         * {@code where} and the child order as its {@code orderBy}, each replacing what was set before (R-FCH-04).
         */
        Builder<E, K, M> forChildren(FetchPlan<M> plan, List<Filter> where, List<OrderField<M, ?>> orderBy) {
            return new Builder<>(root, mapper, null, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, plan);
        }

        /** The primary key; required for {@link #keyset()} and {@link #primaryKeyFirst}. */
        public <K2> Builder<E, K2, M> primaryKey(PrimaryKey<M, K2> primaryKey) {
            return new Builder<>(root, mapper, columns, Objects.requireNonNull(primaryKey, "primaryKey"), orderBy,
                    keyset, primaryKeyFirst, afterMap, finisher, customizer, where, groupBy, having, fetch);
        }

        /** The ordering keys, replacing any set before. */
        @SafeVarargs
        public final Builder<E, K, M> orderBy(OrderField<M, ?>... orderBy) {
            var copy = new ArrayList<OrderField<M, ?>>();
            for (OrderField<M, ?> order : Objects.requireNonNull(orderBy, "orderBy")) {
                copy.add(Objects.requireNonNull(order, "orderBy element"));
            }
            return new Builder<>(root, mapper, columns, primaryKey, List.copyOf(copy), keyset, primaryKeyFirst,
                    afterMap, finisher, customizer, where, groupBy, having, fetch);
        }

        /**
         * Allows keyset paging; needs a primary key and an ungrouped query ({@code MQ1201} and {@code MQ1402} at
         * {@link #build()} otherwise).
         */
        public Builder<E, K, M> keyset() {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, true, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch);
        }

        /**
         * Allows two-step deep paging; needs a primary key and an ungrouped query ({@code MQ1201} and {@code MQ1402} at
         * {@link #build()} otherwise).
         */
        public Builder<E, K, M> primaryKeyFirst(PrimaryKeyFirst primaryKeyFirst) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset,
                    Objects.requireNonNull(primaryKeyFirst, "primaryKeyFirst"), afterMap, finisher, customizer,
                    where, groupBy, having, fetch);
        }

        /**
         * Runs {@code afterMap} once per row after the {@code RowMapper}, to fill a field derived from other mapped
         * values. It must not query, mutate shared state or throw for ordinary data (R-QRY-06). For a record use
         * {@link #finisher(UnaryOperator)}.
         */
        public Builder<E, K, M> afterMap(BiConsumer<M, Row> afterMap) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst,
                    Objects.requireNonNull(afterMap, "afterMap"), finisher, customizer, where, groupBy, having, fetch);
        }

        /** Replaces each mapped model with {@code finisher}'s result, after {@code afterMap}; for records. */
        public Builder<E, K, M> finisher(UnaryOperator<M> finisher) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    Objects.requireNonNull(finisher, "finisher"), customizer, where, groupBy, having, fetch);
        }

        /** Raw Criteria access for each phase; see {@link QueryCustomizer}. Replaces any customizer set before. */
        public Builder<E, K, M> customize(QueryCustomizer customizer) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, Objects.requireNonNull(customizer, "customizer"), where, groupBy, having, fetch);
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
                    having, fetch);
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
            return (ScalarField<M, ?>) column;
        }

        private Builder<E, K, M> groupBy(List<ScalarField<M, ?>> keys) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, List.copyOf(new LinkedHashSet<>(keys)), having, fetch);
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
                    HavingGroup.collect(Objects.requireNonNull(filters, "filters")), fetch);
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
         * @throws ModelQueryDefinitionException {@code MQ1406} for an ordering key that does not fit the grouping
         * @throws ModelQueryDefinitionException {@code MQ1103} for two {@code Agg.of} fields sharing a name with
         *     different expressions
         * @throws ModelQueryDefinitionException {@code MQ1701} for a join plan whose join has no selected column
         */
        public ModelQuery<E, K, M> build() {
            return build(null);
        }

        /** This builder with {@code orderBy}, an immutable list, as its ordering keys. */
        private Builder<E, K, M> ordered(List<OrderField<M, ?>> orderBy) {
            return new Builder<>(root, mapper, columns, primaryKey, orderBy, keyset, primaryKeyFirst, afterMap,
                    finisher, customizer, where, groupBy, having, fetch);
        }

        /** Checks and builds; {@code definition} is the query this one is a re-ordered copy of, or null. */
        private ModelQuery<E, K, M> build(ModelQuery<E, K, M> definition) {
            if (columns == null && fetch == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1202,
                        "select(...) or fetch(...) is required for a query on " + root.rootEntity().getSimpleName());
            }
            SelectSet<M> selection = selection();
            List<ColumnField<M, ?, ?>> needed = needed();
            String model = modelName(selection, root.rootEntity());
            boolean grouped = isGrouped(selection);
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
            if (keyset) {
                checkKeysetExpression();
            }
            if (primaryKey != null) {
                checkKeyTypes(keyset);
            }
            if (grouped) {
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

        /**
         * Builds without the checks of {@link #build()}, for a {@link ModelQuery#withFetch} copy whose query and plan
         * passed them before, once the earlier copy was collected (R-FCH-13).
         */
        private ModelQuery<E, K, M> unchecked() {
            SelectSet<M> selection = selection();
            return new ModelQuery<>(this, selection, needed(), isGrouped(selection), null);
        }

        /** The plan's selection, its join plans' re-rooted under their joins, or else what select(...) was given. */
        private SelectSet<M> selection() {
            return fetch == null ? columns : fetch.selection();
        }

        /** The columns the fetch plan needs besides its selection (R-FCH-02). */
        private List<ColumnField<M, ?, ?>> needed() {
            return fetch == null ? List.of() : List.copyOf(fetch.needed());
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
            return groupBy.contains(field)
                    || field instanceof ExpressionField<?, ?> expression
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
