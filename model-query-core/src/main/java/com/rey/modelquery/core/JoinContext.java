package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CommonAbstractCriteria;
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
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Per-query join state: caches the joins created for one Criteria query by {@link JoinKey}. The only mutable holder
 * of join state (CC-IMM-02); create one per query build and never share it.
 *
 * @implSpec R-COL-02, R-FLT-10, R-FLT-11
 */
@Incubating
public final class JoinContext {

    private record Resolved(From<?, ?> from, Object condition) {}

    /**
     * The memo key of {@link #expression}: the expression and whether the context was left-joining when it rendered.
     */
    private record ExpressionKey(ExpressionField<?, ?> field, boolean leftJoining) {}

    private final From<?, ?> root;
    /**
     * The entity {@link #root} stands for, which a root column must sit on: its Java type, or the child's root entity
     * when the root is a {@code through} join (D-100), whose type a provider may report otherwise for a collection.
     */
    private final Class<?> rootType;
    private final CriteriaBuilder cb;
    /** Where an {@code exists} sub-query is created; {@code null} for a context made with {@link #of}. */
    private final CommonAbstractCriteria query;
    /** In a nested {@code exists}, the key of the enclosing path that {@link #root} stands for; else {@code null}. */
    private final JoinKey rootKey;
    /** In an {@code exists}, its path and the path's parents up to {@link #root}: always INNER there. */
    private final Set<JoinKey> required;
    /** The database facts this build renders by; the same object in every nested {@code exists}. */
    private final RenderOptions renderOptions;
    private final Map<JoinKey, Resolved> joins = new HashMap<>();
    /**
     * One Criteria node per equal expression and per join mode, so a select item is shared by GROUP BY and ORDER BY
     * (R-COL-19), while an expression first rendered inside {@code or}/{@code not} is not reused outside it, where its
     * columns join as plain columns would instead of as LEFT (R-COL-19, R-FLT-10).
     */
    private final Map<ExpressionKey, Expression<?>> expressions = new HashMap<>();
    /**
     * The bind values a repeated expression node adds: each extra rendering of a memoised node repeats its values in
     * the statement, which JPA reports once, so the bind-limit check adds them back (R-COL-19, D-80). The one-element
     * array is shared with the build's nested contexts, since a sub-query and a {@code through} child are one
     * statement.
     */
    private final int[] repeatedExpressionBinds;
    /**
     * The statement parameters this build's {@code Expr.value} expressions render, and the value each binds, which the
     * executor sets on the {@code TypedQuery} it creates (R-COL-26). Shared with the build's nested contexts, since a
     * sub-query, an {@code exists} and a {@code through} child are one statement.
     */
    private final Map<ParameterExpression<?>, Object> parameters;
    /**
     * The to-one attributes a write's path read by their foreign-key column, each with the column that read it, keyed
     * by the entity owning the attribute so a column resolved against two entity types keeps both, in rendering
     * order: the executor asks the provider whether each foreign key references its target's primary key (R-COL-29,
     * D-136). Shared with the build's nested contexts, as {@link #parameters} is.
     */
    private final Map<ForeignKeyRead, ColumnField<?, ?, ?>> foreignKeyReads;
    /**
     * The sub-select roots this build narrowed through a treat on purpose, in build order, which the provider's
     * preflight expects (R-VND-26, D-157). Shared with the build's nested contexts, as {@link #parameters} is.
     */
    private final List<Declared> narrowings;
    /**
     * The join to each joined source's CTE this statement made, by its table's key (R-SRC-21). Shared with the build's
     * nested contexts, as {@link #parameters} is, so a sub-query reads the statement's join.
     */
    private final Map<JoinKey, JoinedSource> joinedSources;
    /** The narrowing a treat of {@link #root} declares: a path {@code exists}'s correlated root, else none. */
    private ExpectedNarrowing.Kind declares;
    /** This context's entry in {@link #narrowings}, made by the first treat of {@link #root}; else {@code null}. */
    private Declared declared;
    /** The columns read so far on a treat of {@link #root} that declares a narrowing (D-157). */
    private int treatedReads;
    /** The key each join was cached under, which is not the declared key when its type was changed. */
    private final Map<From<?, ?>, JoinKey> keys = new IdentityHashMap<>();
    /** In an {@code exists}, the {@code on(...)} conditions of its required joins, rendered in its WHERE instead. */
    private final List<Predicate> requiredConditions = new ArrayList<>();
    /** In an {@code exists}, its path and the join it resolved to, for a nested {@code exists} to correlate with. */
    private TableField<?, ?> existsPath;
    private From<?, ?> existsFrom;
    private int leftJoining;

    /** Above zero while an {@code add(...)} predicate renders, whose reads no {@code TreatSite} records (D-156). */
    private int custom;
    /** Above zero while a CASE condition renders, where an {@code ofType} restricts no row of the statement. */
    private int cases;
    /** Enclosing path-exists scopes preserve OR/NOT semantics when a descendant reads a lifted outer column. */
    private boolean enclosingLeftJoining;
    private int sequencesRefused;
    private int aggregating;
    /**
     * The types every {@code exists} sub-query of this build joined, nested ones included: shared with each nested
     * context, so a bulk write learns whether a sub-query reads its own table (R-WRT-11).
     */
    private final Set<Class<?>> existsJoined;
    /**
     * Inside a correlated sub-select, the enclosing context whose joins a lifted column reuses; {@code null}
     * everywhere else (R-FLT-27).
     */
    private final JoinContext outer;
    private boolean readsToOneLeft;
    private Map<JoinKey, List<Filter>> joinFilters = Map.of();
    /** The filtered keys whose ON this statement (or a semi-join sub-query of it) built (R-COL-39, D-194). */
    private final Set<JoinKey> appliedFilters = new HashSet<>();
    /** A join filter's terms are being rendered: a join created now hangs below the filtered join. */
    private boolean buildingFilter;
    /** The statement runs a {@code QueryCustomizer}, which could add a RIGHT or FULL join (R-AGG-19). */
    private boolean customized;
    /** For a {@link #rootedAt} context, the context its root was joined in; {@code null} everywhere else. */
    private JoinContext rootedIn;
    /**
     * For a {@link #sourceOf} context, the CTE columns of the source's key, in key order; empty everywhere else. A
     * {@code firstBy} over the source root appends them to break ties (R-AGG-16).
     */
    private List<String> sourceKey = List.of();
    /**
     * The to-many joins this context itself made, in resolution order: a source's {@code where} predicate that
     * crosses one is {@code MQ1906} (R-SRC-03). A nested {@code exists} joins on a context of its own, so the paths
     * an {@code exists} may legitimately cross are not counted here.
     */
    private final List<Join<?, ?>> toManyJoins = new ArrayList<>();
    /** Where {@link #recordingToMany} collects the plural joins a render reads, or {@code null}. */
    private List<Join<?, ?>> touchedToMany;

    private JoinContext(From<?, ?> root, Class<?> rootType, CriteriaBuilder cb, CommonAbstractCriteria query,
            JoinKey rootKey, Set<JoinKey> required, RenderOptions renderOptions, Set<Class<?>> existsJoined) {
        this(root, rootType, cb, query, rootKey, required, renderOptions, existsJoined, null, new int[1],
                new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>(), new HashMap<>());
    }

    private JoinContext(From<?, ?> root, Class<?> rootType, CriteriaBuilder cb, CommonAbstractCriteria query,
            JoinKey rootKey, Set<JoinKey> required, RenderOptions renderOptions, Set<Class<?>> existsJoined,
            JoinContext outer, int[] repeatedExpressionBinds, Map<ParameterExpression<?>, Object> parameters,
            Map<ForeignKeyRead, ColumnField<?, ?, ?>> foreignKeyReads, List<Declared> narrowings,
            Map<JoinKey, JoinedSource> joinedSources) {
        this.root = root;
        this.rootType = rootType;
        this.cb = cb;
        this.query = query;
        this.rootKey = rootKey;
        this.required = required;
        this.renderOptions = renderOptions;
        this.existsJoined = existsJoined;
        this.outer = outer;
        this.repeatedExpressionBinds = repeatedExpressionBinds;
        this.parameters = parameters;
        this.foreignKeyReads = foreignKeyReads;
        this.narrowings = narrowings;
        this.joinedSources = joinedSources;
    }

    /** A context over {@code root}, the query's root table, rendering with {@link RenderOptions#portable()}. */
    @EngineFacing
    public static JoinContext of(Root<?> root, CriteriaBuilder cb) {
        return new JoinContext(root, root.getJavaType(), cb, null, null, Set.of(), RenderOptions.portable(),
                new HashSet<>());
    }

    /** A context over the root of {@code query}, which can also render {@code exists} sub-queries. */
    static JoinContext of(Root<?> root, CriteriaBuilder cb, CommonAbstractCriteria query, RenderOptions options) {
        return new JoinContext(root, root.getJavaType(), cb, query, null, Set.of(), options, new HashSet<>());
    }

    /**
     * A context with no root, for a write expression that reads no column: an insert's computed value, rendered in a
     * row's {@code VALUES} (R-WRT-53). Resolving a column through it throws {@link IllegalStateException}, which the
     * {@code MQ1613} check of {@code Assignment.Computed} keeps from happening.
     */
    static JoinContext rootless(CriteriaBuilder cb, RenderOptions options) {
        return new JoinContext(null, null, cb, null, null, Set.of(), options, new HashSet<>());
    }

    /**
     * A context of the same query whose root is {@code join}, a join of this context reaching {@code entity}: a
     * {@code through} child's model resolves against it, its columns, joins, filters, {@code exists} and order all
     * under the join, while this context keeps the joins up to it (R-FCH-14, D-100). It shares the render options and
     * the {@code exists} bookkeeping, but not the join cache, so the joins it makes sit below {@code join}.
     */
    /**
     * A context of a read: a column over a to-one association resolves through a {@code LEFT} join, so a row whose
     * foreign key is {@code NULL} is kept (D-124). A write's context keeps the implicit path, as a bulk statement
     * takes no join.
     */
    static JoinContext readOf(Root<?> root, CriteriaBuilder cb, CommonAbstractCriteria query, RenderOptions options) {
        JoinContext ctx = of(root, cb, query, options);
        ctx.readsToOneLeft = true;
        return ctx;
    }

    /**
     * A read context over {@code cte}, the root of a source's columns, for the source model {@code model}: its
     * {@link TableField#source} root resolves to it and its columns read the CTE's field-named elements (R-SRC-10).
     * The {@code model} is the context's root type, so a column's {@code MQ1003} check passes for the source root;
     * {@code key} names the CTE columns of the source's key ({@link SourceQuery#keyColumns()}).
     */
    static JoinContext sourceOf(Root<?> cte, Class<?> model, List<String> key, CriteriaBuilder cb,
            CommonAbstractCriteria query, RenderOptions options) {
        JoinContext ctx = new JoinContext(cte, model, cb, query, JoinKey.root(model), Set.of(), options,
                new HashSet<>());
        ctx.readsToOneLeft = true;
        ctx.sourceKey = List.copyOf(key);
        return ctx;
    }

    /** The CTE columns of the key of the source this context's root reads, in key order; empty for any other root. */
    List<String> sourceKey() {
        return sourceKey;
    }

    JoinContext rootedAt(From<?, ?> join, Class<?> entity) {
        JoinContext ctx = new JoinContext(join, entity, cb, query, null, Set.of(), renderOptions, existsJoined, null,
                repeatedExpressionBinds, parameters, foreignKeyReads, narrowings, joinedSources);
        ctx.readsToOneLeft = readsToOneLeft;
        ctx.customized = customized;
        ctx.rootedIn = this;
        return ctx;
    }

    boolean readsToOneLeft() {
        return readsToOneLeft;
    }

    /** Request-bound ON terms, kept on the statement rather than its shared definitions (R-COL-39). */
    void joinFilters(Map<TableField<?, ?>, List<Filter>> filters) {
        var bound = new HashMap<JoinKey, List<Filter>>();
        filters.forEach((table, terms) -> bound.put(table.key(), terms));
        joinFilters = Map.copyOf(bound);
    }

    List<Predicate> joinFilter(JoinKey key) {
        List<Filter> terms = joinFilters.get(key);
        if (terms == null) {
            return List.of();
        }
        appliedFilters.add(key);
        boolean previous = buildingFilter;
        buildingFilter = true;
        try {
            return ConditionGroup.toPredicates(terms, this);
        } finally {
            buildingFilter = previous;
        }
    }

    /** The filters {@code inner}, a semi-join sub-query of this statement, applied count for this statement. */
    void adoptJoinFilters(JoinContext inner) {
        appliedFilters.addAll(inner.appliedFilters);
    }

    /** Content must actually apply every filtered join key, including its type and alias (R-COL-39, D-194). */
    void checkJoinFilters() {
        for (JoinKey key : joinFilters.keySet()) {
            if (!appliedFilters.contains(key)) {
                throw JoinFilterChecks.refused("joinFilter(...): the content statement never creates join " + key);
            }
        }
    }

    void checkJoinFilterId(ColumnField<?, ?, ?> column, JoinKey key) {
        if (joinFilters.containsKey(key)) {
            throw JoinFilterChecks.refused(column + ": an id-form column reads this joinFilter(...) join; "
                    + "map the foreign key as a basic column instead");
        }
    }

    /** Records that the statement this context renders runs a {@code QueryCustomizer} (R-AGG-19). */
    void customized(boolean customized) {
        this.customized = customized;
    }

    boolean customized() {
        return customized;
    }

    /**
     * Whether {@code node} is a treated node this context, an enclosing one, or the one a {@link #rootedAt} root was
     * joined in, made (R-COL-35).
     */
    boolean treatedNode(From<?, ?> node) {
        for (JoinContext ctx = this; ctx != null; ctx = ctx.outer != null ? ctx.outer : ctx.rootedIn) {
            JoinKey key = ctx.keys.get(node);
            if (key != null) {
                return key.type() == null;
            }
        }
        return false;
    }

    /** Records that {@code column}, a to-one of {@code entity}, rendered as its foreign-key column (R-COL-29). */
    void readByForeignKey(ColumnField<?, ?, ?> column, Class<?> entity) {
        foreignKeyReads.putIfAbsent(new ForeignKeyRead(entity, column.name()), column);
    }

    /** Records the foreign-key reads {@code other}, a context of a scratch sub-query of this statement, made. */
    void adoptForeignKeyReads(JoinContext other) {
        other.foreignKeyReads.forEach(foreignKeyReads::putIfAbsent);
    }

    /** A to-one attribute of an entity that a build rendered as its foreign-key column (R-COL-29). */
    record ForeignKeyRead(Class<?> entity, String attribute) {
    }

    /** The to-one attributes this build rendered as their foreign-key column, each with its column (R-COL-29). */
    Map<ForeignKeyRead, ColumnField<?, ?, ?>> foreignKeyReads() {
        return Collections.unmodifiableMap(foreignKeyReads);
    }

    /**
     * A sub-select root a build narrowed on purpose, gaining each subtype a treat of it reads, unless an {@code or}
     * read a column of the treated root in only some of its branches (D-157).
     */
    private static final class Declared {
        final ExpectedNarrowing.Kind kind;
        final Class<?> entity;
        final Set<Class<?>> types = new LinkedHashSet<>();
        boolean whole;

        Declared(ExpectedNarrowing.Kind kind, Class<?> entity) {
            this.kind = kind;
            this.entity = entity;
        }
    }

    /**
     * The sub-select roots this build narrowed through a treat on purpose so far, in build order, each with every
     * subtype it was treated as: only the correlated root of a path {@code exists}, and not one an {@code or} keeps
     * whole. Nothing else is declared, so the preflight refuses any other narrowing (R-VND-26, D-157).
     */
    List<ExpectedNarrowing> expectedNarrowings() {
        return narrowings.stream().filter(each -> !each.whole)
                .map(each -> new ExpectedNarrowing(each.kind, each.entity, each.types)).toList();
    }

    /** The columns read so far on a treat of this context's root that declares a narrowing (D-157). */
    int treatedReads() {
        return treatedReads;
    }

    /**
     * Withdraws the narrowing a treat of this context's root declared: an {@code or} read a column of the treated
     * root in some of its branches only, and Hibernate, measured on 6.6 and 7.4, then reads the root whole. A
     * provider that narrows it anyway is refused by the preflight, which expects nothing of it (D-157).
     */
    void readsTreatedRootUnevenly() {
        if (declared != null) {
            declared.whole = true;
        }
    }

    /** Whether an {@code exists} sub-query of this build rendered so far, at any depth (R-WRT-11). */
    boolean renderedExists() {
        return !existsJoined.isEmpty(); // an exists always joins its path
    }

    /** The types the {@code exists} sub-queries of this build joined so far, at any depth (R-WRT-11). */
    Set<Class<?>> existsJoined() {
        return Collections.unmodifiableSet(existsJoined);
    }

    From<?, ?> root() {
        if (root == null) {
            throw new IllegalStateException("a write expression's context has no root, so it resolves no column");
        }
        return root;
    }

    /**
     * The to-many joins this context itself made, in resolution order. A source's {@code where} predicate that made
     * one is {@code MQ1906}: two conditions on one joined line would match different lines, so the predicate is
     * refused and the user points it at {@code Filters.exists(path, inner)} (R-SRC-03).
     */
    List<Join<?, ?>> toManyJoins() {
        return Collections.unmodifiableList(toManyJoins);
    }

    /** The entity the root stands for, which a column on a root must sit on. */
    Class<?> rootType() {
        return rootType;
    }

    JoinKey rootKey() {
        return rootKey;
    }

    CriteriaBuilder cb() {
        return cb;
    }

    /**
     * The Criteria node for {@code field}, rendering it once per equal expression and join mode in this statement
     * (R-COL-19): selection, group key, order key and filters of one statement share the node, so a provider that
     * references select items by identity renders {@code GROUP BY} and {@code ORDER BY} as references. An expression
     * rendered while left-joining (inside {@code or}/{@code not}, {@link #leftJoining}) is not shared with one rendered
     * outside it: the columns inside it must join as plain columns outside and as LEFT inside (R-COL-19, R-FLT-10).
     */
    Expression<?> expression(ExpressionField<?, ?> field, Supplier<Expression<?>> render) {
        // Not computeIfAbsent: render() renders the field's nested expressions through this same map, and HashMap
        // forbids a mapping function that modifies the map (R-COL-19).
        ExpressionKey key = new ExpressionKey(field, leftJoining > 0);
        Expression<?> existing = expressions.get(key);
        if (existing != null) {
            // The node repeats in the statement, so its values bind once more each; JPA reports the shared node once.
            repeatedExpressionBinds[0] += field.binds();
            return existing;
        }
        Expression<?> rendered = java.util.Objects.requireNonNull(render.get(), "rendered");
        expressions.put(key, rendered);
        return rendered;
    }

    /** The bind values the repeated renderings of memoised expressions add beyond what JPA reports (R-COL-19, D-80). */
    @EngineFacing
    public int repeatedExpressionBinds() {
        return repeatedExpressionBinds[0];
    }

    /**
     * A statement parameter of {@code type} for an {@code Expr.value} of {@code value}, recorded for the executor to
     * bind on the statement that renders it (R-COL-26). The value never reaches SQL as a literal.
     */
    ParameterExpression<?> parameter(Class<?> type, Object value) {
        ParameterExpression<?> parameter = cb.parameter(type);
        parameters.put(parameter, value);
        return parameter;
    }

    /**
     * Where {@link ValueOperands#valueOperand} records the parameters it makes, an {@code OperandBinder}'s included,
     * to bind on the statement that renders them, as {@link #parameter(Class, Object)} records its own (R-FLT-08,
     * R-FLT-21).
     */
    ParameterSink sink() {
        return new ParameterSink() {
            @Override
            public boolean binds(ParameterExpression<?> parameter) {
                return parameters.containsKey(parameter);
            }

            @Override
            public void add(ParameterExpression<?> parameter, Object value) {
                parameters.put(parameter, value);
            }
        };
    }

    /**
     * The statement parameters this build's {@code Expr.value} expressions rendered and the value each binds, which
     * the executor sets on the {@code Query} it creates, every clause of the statement and its sub-queries included
     * (R-COL-26). Empty for a build that renders none.
     *
     * @return an unmodifiable view, in rendering order
     */
    @EngineFacing
    public Map<ParameterExpression<?>, Object> statementParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    /**
     * Adds the repeats and the statement parameters {@code rendered} counted, a context of its own whose sub-query
     * this statement keeps, such as a write's {@code EXISTS} (R-COL-19, R-COL-26, D-80).
     */
    void addSubqueryBinds(JoinContext rendered) {
        addBodyParameters(rendered);
        repeatedExpressionBinds[0] += rendered.repeatedExpressionBinds[0];
    }

    /**
     * Records {@code join}, this statement's join to the CTE of the source whose table's key is {@code key}, made from
     * {@code outer} as an INNER join or not; {@code firstKey} names the CTE column of the source's first key field,
     * which a count of the joined table counts (R-SRC-21, R-AGG-19).
     */
    void joinSource(JoinKey key, From<?, ?> join, From<?, ?> outer, boolean inner, String firstKey) {
        joinedSources.put(key, new JoinedSource(join, outer, inner, join.get(firstKey), false));
    }

    /**
     * The join this statement made to the joined source whose table's key is {@code key} (R-SRC-21).
     *
     * @throws ModelQueryDefinitionException {@code MQ1003} when the query binds no such join: a column of
     *     {@code owner} sits on a table the statement does not read
     */
    From<?, ?> joinedSource(JoinKey key, String owner) {
        return bound(key, owner).join();
    }

    /**
     * What a count of the joined source whose table's key is {@code key} counts: the CTE column of the source's first
     * key field, null exactly where a LEFT join matched no source row, so an unmatched row counts 0 (R-AGG-19).
     *
     * @throws ModelQueryDefinitionException {@code MQ1003} when the query binds no such join
     */
    Path<?> joinedSourceKey(JoinKey key, String owner) {
        return bound(key, owner).key();
    }

    /**
     * The joined source whose first key column {@code counted} is, as {@link #joinedSourceKey} returned it, or
     * {@code null} for any other expression (R-AGG-19).
     */
    JoinedSource joinedSourceCounted(Expression<?> counted) {
        for (JoinedSource joined : joinedSources.values()) {
            if (joined.key() == counted) {
                return joined;
            }
        }
        return null;
    }

    /** Registers an entity join and its first id path; it always ends the row-count walk (R-AGG-19). */
    void joinEntity(JoinKey key, From<?, ?> join, From<?, ?> outer, Path<?> firstId) {
        joinedSources.put(key, new JoinedSource(join, outer, false, firstId, true));
    }

    /** Resolves a registered entity join, never falling back to the statement root (R-SRC-23). */
    From<?, ?> joinedEntity(JoinKey key, String owner) {
        JoinedSource joined = joinedSources.get(key);
        if (joined == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1003, owner + ": the query binds no entity joined as '"
                    + key.alias() + "'; bind it with joinEntity(...)");
        }
        return joined.join();
    }

    /** Whether this statement registered the node as an entity join (R-AGG-19). */
    boolean isJoinedEntity(From<?, ?> node) {
        return joinedSources.values().stream().anyMatch(joined -> joined.entity() && joined.join() == node);
    }

    private JoinedSource bound(JoinKey key, String owner) {
        JoinedSource joined = joinedSources.get(key);
        if (joined == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1003, owner + ": the query binds no source joined as '"
                    + key.alias() + "'; bind it with joinSource(...)");
        }
        return joined;
    }

    /**
     * A bound source or entity join, its anchor and count identity. Entity joins always stop the row-count walk;
     * source joins may continue through an INNER anchor (R-AGG-19).
     */
    record JoinedSource(From<?, ?> join, From<?, ?> outer, boolean inner, Path<?> key, boolean entity) {}

    /**
     * Makes the joined sources of {@code statement} readable here, a context of its own over a second root of the same
     * statement, such as a semi-join's sub-query, which reads the statement's joins (R-SRC-21).
     */
    void adoptJoinedSources(JoinContext statement) {
        joinedSources.putAll(statement.joinedSources);
    }

    /**
     * Adds the statement parameters {@code rendered} recorded, the context of a body this statement registers, such
     * as a source's CTE body, to bind on this statement (R-COL-26, R-FLT-08).
     */
    void addBodyParameters(JoinContext rendered) {
        for (ParameterExpression<?> parameter : rendered.parameters.keySet()) {
            if (parameters.containsKey(parameter)) {
                // Only a provider's operand binder reusing a parameter gets here: cb.parameter is new on every call.
                throw new ModelQueryDefinitionException(MqCode.MQ1502, "a sub-query binds a parameter its statement "
                        + "already binds: the provider's operand binder must make a new parameter on every call");
            }
        }
        parameters.putAll(rendered.parameters);
    }

    /**
     * The options this build renders by: how an engine path outside {@code core} that adds its own values to the
     * statement, such as a keyset cursor, binds them by {@link ValueOperands#valueOperand}'s rule (R-FLT-08, D-177).
     */
    @EngineFacing
    public RenderOptions renderOptions() {
        return renderOptions;
    }

    /**
     * The provider's column-typed constant renderer this build renders by, or empty when it has none and a typed
     * constant is then {@code MQ1508} (R-COL-27). How {@code core}'s expressions reach the provider capability, so
     * {@code core} names no provider (INV-7).
     */
    Optional<ConstantRenderer> constantRenderer() {
        return renderOptions.constantRenderer();
    }

    /**
     * The provider's renderer of a sequence's next value this build renders by, or empty when the vendor or the
     * provider has none and {@code Expr.nextval} is then {@code MQ1509} (R-COL-28). How {@code core}'s expressions
     * reach the provider capability, so {@code core} names no provider (INV-7).
     */
    Optional<SequenceRenderer> sequenceRenderer() {
        return renderOptions.sequenceRenderer();
    }

    /** Whether the clause being rendered refuses a sequence value: a filter, an order or a group key (R-COL-28). */
    boolean sequencesRefused() {
        return sequencesRefused > 0;
    }

    /** Runs {@code render} as a clause that refuses {@code Expr.nextval}: a filter, an order or a group key. */
    <R> R withoutSequences(Supplier<R> render) {
        sequencesRefused++;
        try {
            return render.get();
        } finally {
            sequencesRefused--;
        }
    }

    /**
     * Runs {@code render} with every INNER join it is the first to need resolved as LEFT, because an INNER join made
     * for one {@code or} branch would remove rows another branch matches. An INNER join made before is reused, since
     * its rows are already required (R-FLT-10).
     */
    <R> R leftJoining(Supplier<R> render) {
        leftJoining++;
        try {
            return render.get();
        } finally {
            leftJoining--;
        }
    }

    /**
     * Runs {@code render}, an {@code add(...)} predicate, refusing any subtype treat it reaches: its condition records
     * no column, so no {@code TreatSite} reports the read and the provider's table never checked it (D-156).
     */
    <R> R custom(Supplier<R> render) {
        custom++;
        try {
            return render.get();
        } finally {
            custom--;
        }
    }

    /** Runs {@code render}, a CASE condition, whose {@code ofType} declares no restriction (D-158). */
    <R> R inCase(Supplier<R> render) {
        cases++;
        try {
            return render.get();
        } finally {
            cases--;
        }
    }

    /**
     * {@code TYPE(table)}, the exact entity class of its rows (R-COL-36).
     *
     * @throws ModelQueryConfigurationException {@code MQ4014} without the provider's {@code TreatRenderer}: JPA
     *     leaves a type read outside {@code WHERE} to the provider, so it renders only where the provider's table
     *     checks it (R-VND-26)
     */
    Expression<?> type(TableField<?, ?> table, String description) {
        requireTreatRenderer(description);
        return table.resolve(this).type();
    }

    /**
     * {@code TYPE(table) IN (...)} over every concrete mapped entity assignable to one of {@code types}, read from the
     * metamodel so a grandchild and an {@code orm.xml} subtype count, or its negation, ORed with
     * {@code TYPE(table) IS NULL} over a node reached through a LEFT join (R-FLT-28). A top-level {@code ofType} of
     * the statement over its untreated root declares its restriction for the preflight (D-158).
     *
     * @throws ModelQueryDefinitionException {@code MQ1106} for a type the metamodel does not map as an entity, or one
     *     that is not the node's entity or a subtype of it
     * @throws ModelQueryConfigurationException {@code MQ4014} without the provider's {@code TreatRenderer} or the
     *     persistence unit's metamodel
     */
    Predicate ofType(TableField<?, ?> table, List<Class<?>> types, boolean negated, String description) {
        requireTreatRenderer(description);
        Metamodel metamodel = renderOptions.metamodel().orElseThrow(() -> new ModelQueryConfigurationException(
                MqCode.MQ4014, description + ": an ofType needs the persistence unit's metamodel, which the executor "
                        + "gives with its renderers (RenderOptions#withMetamodel)"));
        From<?, ?> from = table.resolve(this);
        Class<?> node = from.getJavaType();
        var mapped = new HashSet<Class<?>>();
        for (EntityType<?> entity : metamodel.getEntities()) {
            mapped.add(entity.getJavaType());
        }
        for (Class<?> type : types) {
            if (!mapped.contains(type) || !node.isAssignableFrom(type)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1106, description + ": " + type.getName()
                        + (mapped.contains(type) ? " is not " + node.getSimpleName() + " or a subtype of it"
                                : " is not a mapped entity"));
            }
        }
        // By name, so the IN list is the same on every run (R-COL-36).
        var expanded = new TreeSet<Class<?>>(Comparator.comparing(Class::getName));
        for (Class<?> entity : mapped) {
            if (!Modifier.isAbstract(entity.getModifiers())
                    && types.stream().anyMatch(type -> type.isAssignableFrom(entity))) {
                expanded.add(entity);
            }
        }
        if (expanded.isEmpty()) {
            // An abstract type with no concrete subtype: no row has it.
            return negated ? cb.conjunction() : cb.disjunction();
        }
        Expression<?> type = from.type();
        // Type literals, not binds: the provider's preflight reads them to attribute the restriction (D-158).
        Predicate in = type.in(expanded.stream().map(cb::literal).toArray(Expression<?>[]::new));
        if (negated) {
            return TreatChecks.joinUse(table.key()) == TreatUse.LEFT_JOIN ? cb.or(cb.not(in), cb.isNull(type))
                    : cb.not(in);
        }
        // Only the statement's untreated root declares: the preflight matches the declaration to the single root of
        // its entity, so a joined node of the same entity, such as a self-join, must not vouch for it (D-158).
        if (query instanceof CriteriaQuery<?> criteria && table.rootEntity() != null
                && criteria.getRoots().contains(from) && leftJoining == 0 && !enclosingLeftJoining && custom == 0
                && cases == 0) {
            var declaration = new Declared(ExpectedNarrowing.Kind.TYPE_RESTRICTION, table.rootEntity());
            declaration.types.addAll(expanded);
            narrowings.add(declaration);
        }
        return in;
    }

    private void requireTreatRenderer(String description) {
        if (renderOptions.treatRenderer().isEmpty()) {
            throw new ModelQueryConfigurationException(MqCode.MQ4014, description
                    + ": a type read needs the provider's TreatRenderer (ProviderSupport#treats(emf))");
        }
    }

    /**
     * {@code EXISTS (SELECT 1 ... WHERE <inner>)} over {@code path}, correlated to this context's root, or to its own
     * {@code exists} path when this context is itself an {@code exists}. The sub-query joins {@code path} from there,
     * as INNER, and {@code inner} renders against the sub-query's own context, so the outer query joins nothing
     * (R-FLT-11, R-FLT-12).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Predicate exists(TableField<?, ?> path, Function<JoinContext, List<Predicate>> inner) {
        if (query == null) {
            throw new IllegalStateException("exists(...) needs the JoinContext of a ModelQuery build");
        }
        Subquery<Integer> sub = query.subquery(Integer.class);
        JoinContext ctx;
        if (existsPath == null) {
            // The root of a through child's context is a join, which the sub-query correlates as a join (D-100).
            From<?, ?> correlated = root instanceof Root<?> ? sub.correlate((Root) root) : sub.correlate((Join) root);
            ctx = new JoinContext(correlated, rootType, cb, sub, null, path.keysUpTo(null), renderOptions,
                    existsJoined, outer, repeatedExpressionBinds, parameters, foreignKeyReads, narrowings,
                    joinedSources);
            ctx.readsToOneLeft = readsToOneLeft;
            ctx.customized = customized;
            ctx.declares = ExpectedNarrowing.Kind.EXISTS_ROOT; // a treat of the correlated root narrows the sub-select
        } else {
            // A treat of the root, which an exists path may be (R-FLT-11), resolves to a root that no nested
            // sub-select correlates as a join; TreatChecks refuses that correlation first for a measured renderer.
            if (!(existsFrom instanceof Join<?, ?>)) {
                throw new ModelQueryConfigurationException(MqCode.MQ4016, path.describe() + ": an exists(...) nested "
                        + "in exists(...) on " + existsPath.describe() + " would correlate on its treated root, which "
                        + "the TreatRenderer resolves to no join; filter the subtype with ofType(...), or make it a "
                        + "sibling exists(...)");
            }
            From<?, ?> correlated = sub.correlate((Join) existsFrom);
            ctx = new JoinContext(correlated, correlated.getJavaType(), cb, sub, existsPath.key(),
                    path.keysUpTo(existsPath.key()), renderOptions, existsJoined, outer, repeatedExpressionBinds,
                    parameters, foreignKeyReads, narrowings, joinedSources);
            ctx.readsToOneLeft = readsToOneLeft;
            ctx.customized = customized;
        }
        ctx.enclosingLeftJoining = enclosingLeftJoining || leftJoining > 0;
        ctx.existsPath = path;
        ctx.existsFrom = path.resolve(ctx);
        sub.select(cb.literal(1));
        var where = new ArrayList<>(ctx.requiredConditions);
        where.addAll(inner.apply(ctx));
        if (!where.isEmpty()) {
            sub.where(where.toArray(Predicate[]::new));
        }
        return cb.exists(sub);
    }

    /**
     * The outer path a lifted column reads, resolved through this context's correlation. {@code lift} is the
     * column an {@link Outer#column} built; resolving it here rather than outside any correlation is {@code MQ1310}
     * (R-FLT-17).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <T, C> Path<C> liftedColumn(ColumnField<?, T, C> lift) {
        if (outer == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1310, lift
                    + " was resolved outside the correlation it was made in; lift it again inside exists(...)");
        }
        ColumnField<?, T, C> column = (ColumnField<?, T, C>) lift.liftedFrom().orElseThrow();
        From<?, ?> from = enclosingLeftJoining || leftJoining > 0
                ? outer.leftJoining(() -> column.table().resolve(outer)) : column.table().resolve(outer);
        for (From<?, ?> node = from; node != outer.root && node instanceof Join<?, ?> join;
                node = join.getParent()) {
            if (join.getAttribute() instanceof PluralAttribute<?, ?, ?>) {
                throw new ModelQueryDefinitionException(MqCode.MQ1311, column
                        + ": the lifted path crosses a to-many join; lift a root or to-one column");
            }
        }
        Subquery<?> sq = (Subquery<?>) query;
        From<?, ?> correlated = from instanceof Root<?> ? sq.correlate((Root) from) : sq.correlate((Join) from);
        JoinContext ctx = new JoinContext(correlated, outer.rootType, cb, sq, column.table().key(), Set.of(),
                renderOptions, existsJoined, null, repeatedExpressionBinds, parameters, foreignKeyReads, narrowings,
                joinedSources);
        ctx.readsToOneLeft = outer.readsToOneLeft;
        ctx.customized = outer.customized;
        return column.path(ctx);
    }

    /**
     * {@code column IN (SELECT s.c FROM <sub's root> WHERE <sub's filters>)} (R-FLT-16); {@code negated} is the
     * null-safe {@code notIn}: {@code NOT IN (... AND s.c IS NOT NULL) OR column IS NULL}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <C> Predicate inSubSelect(ScalarField<?, C> column, SubSelect<?, C> sub, boolean negated) {
        checkSubSelectRoot(sub);
        if (query == null) {
            throw new IllegalStateException("in(...) over a sub-select needs the JoinContext of a ModelQuery build");
        }
        Subquery<C> sq = query.subquery(sub.column().type());
        Root<?> inner = sq.from(sub.rootEntity());
        JoinContext ctx = new JoinContext(inner, sub.rootEntity(), cb, sq, null, Set.of(), renderOptions,
                existsJoined, null, repeatedExpressionBinds, parameters, foreignKeyReads, narrowings, joinedSources);
        ctx.readsToOneLeft = readsToOneLeft;
        ctx.customized = customized;
        Path<C> selected = sub.column().path(ctx);
        // A lifted column reads the outer root, so its embeddable check runs against the outer context, not this one.
        boolean embeddable = column instanceof ColumnField<?, ?, ?> outerColumn
                && outerColumn.isLifted() && outer != null
                ? outerColumn.liftedFrom().orElseThrow().embeddableValued(outer)
                : column instanceof ColumnField<?, ?, ?> plain && plain.embeddableValued(this);
        if (embeddable || sub.column().embeddableValued(ctx)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1312, "in(...) over " + sub
                    + ": an embeddable-valued column has no portable row-value IN (INV-6)");
        }
        sq.select(selected);
        var where = new ArrayList<>(ConditionGroup.toPredicates(sub.filters(), ctx));
        if (negated) {
            where.add(cb.isNotNull(selected));
        }
        if (!where.isEmpty()) {
            sq.where(where.toArray(Predicate[]::new));
        }
        existsJoined.add(sub.rootEntity());
        Expression<C> outerColumn = column.expression(this);
        Predicate in = cb.in(outerColumn).value(sq);
        return negated ? cb.or(cb.not(in), cb.isNull(outerColumn)) : in;
    }

    /**
     * {@code EXISTS (SELECT 1 FROM <sub's root> s WHERE <sub's filters> AND <correlation>)}, correlated to this
     * context's root through which a lifted outer column resolves (R-FLT-17). The sub-select's column is not rendered.
     */
    Predicate existsSubSelect(SubSelect<?, ?> sub, List<Filter> correlation, boolean negated) {
        Supplier<Subquery<Integer>> render =
                () -> correlatedSubSelect(sub, correlation, Integer.class, ctx -> cb.literal(1), false);
        Predicate exists = cb.exists(negated ? leftJoining(render) : render.get());
        return negated ? cb.not(exists) : exists;
    }

    /** The scalar sub-query of a correlated column; EXISTS is a non-null Boolean CASE (R-COL-31). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Expression<?> correlatedValue(SubSelect<?, ?> sub, List<Filter> correlation, Correlated.Terminal terminal,
            Class<?> type, List<OrderField<?, ?>> orderBy) {
        checkSubSelectRoot(sub);
        SubqueryRenderer renderer = terminal == Correlated.Terminal.FIRST
                ? renderOptions.subqueryRenderer().orElseThrow(() -> new ModelQueryConfigurationException(MqCode.MQ4014,
                        sub + ": Correlated.first needs the provider's SubqueryRenderer; "
                                + "add model-query-hibernate for Hibernate (ProviderSupport#subqueries())"))
                : null;
        if (terminal == Correlated.Terminal.EXISTS) {
            return cb.<Boolean>selectCase().when(existsSubSelect(sub, correlation, false), cb.literal(true))
                    .otherwise(cb.literal(false));
        }
        var orders = new ArrayList<Order>();
        var sq = correlatedSubSelect(sub, correlation, type, ctx -> {
            Expression<?> column = sub.column().expression(ctx);
            if (terminal == Correlated.Terminal.FIRST) {
                orderBy.forEach(order -> orders.addAll(order.toOrders(ctx, cb)));
                SubqueryOrders.appendId((Root<?>) ctx.root, cb, orders);
            }
            return ExpressionField.raw(switch (terminal) {
                case COUNT -> RowCounts.count(ctx, column);
                case MIN -> cb.least(ExpressionField.<Comparable>raw(column));
                case MAX -> cb.greatest(ExpressionField.<Comparable>raw(column));
                case SUM -> cb.sum(ExpressionField.<Number>raw(column));
                case FIRST -> column;
                case EXISTS -> throw new AssertionError("EXISTS renders as a Boolean CASE");
            });
        }, terminal == Correlated.Terminal.COUNT || terminal == Correlated.Terminal.SUM
                || terminal == Correlated.Terminal.FIRST);
        return renderer == null ? sq : renderer.firstRow(cb, sq, List.copyOf(orders));
    }

    /** A fresh statement-local sub-query over the inner root and its correlated outer root (R-FLT-17, INV-9). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <C> Subquery<C> correlatedSubSelect(SubSelect<?, ?> sub, List<Filter> correlation, Class<C> type,
            Function<JoinContext, Expression<C>> selection, boolean refuseToMany) {
        checkSubSelectRoot(sub);
        if (query == null) {
            throw new IllegalStateException("a correlated sub-select needs the JoinContext of a ModelQuery build");
        }
        Subquery<C> sq = query.subquery(type);
        Root<?> inner = sq.from(sub.rootEntity());
        JoinContext ctx = new JoinContext(inner, sub.rootEntity(), cb, sq, null, Set.of(), renderOptions,
                existsJoined, this, repeatedExpressionBinds, parameters, foreignKeyReads, narrowings, joinedSources);
        ctx.readsToOneLeft = readsToOneLeft;
        ctx.customized = customized;
        sq.select(selection.apply(ctx));
        var where = new ArrayList<>(ConditionGroup.toPredicates(sub.filters(), ctx));
        where.addAll(ConditionGroup.toPredicates(correlation, ctx));
        if (refuseToMany && !ctx.toManyJoins.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1511, sub
                    + ": correlated count/sum/first crosses a to-many join, which can multiply the inner rows; "
                    + "filter with Filters.exists(path, inner) instead");
        }
        if (!where.isEmpty()) {
            sq.where(where.toArray(Predicate[]::new));
        }
        existsJoined.add(sub.rootEntity());
        return sq;
    }

    private static void checkSubSelectRoot(SubSelect<?, ?> sub) {
        if (sub.root().isSource()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1909, sub + ": the sub-select's root is a source");
        }
    }

    /**
     * Runs {@code body} and adds to {@code touched} every plural join it reads, one already in the statement included
     * (R-AGG-16): {@link #toManyJoins()} only grows by the joins {@code body} creates.
     */
    <T> T recordingToMany(Supplier<T> body, List<Join<?, ?>> touched) {
        List<Join<?, ?>> previous = touchedToMany;
        touchedToMany = touched;
        try {
            return body.get();
        } finally {
            touchedToMany = previous;
        }
    }

    boolean aggregating() {
        return aggregating > 0;
    }

    <R> R aggregating(Supplier<R> render) {
        aggregating++;
        try {
            return render.get();
        } finally {
            aggregating--;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    From<?, ?> join(
            JoinKey key,
            From<?, ?> parent,
            String attribute,
            JoinType type,
            BiFunction<? extends From<?, ?>, CriteriaBuilder, ?> condition,
            String description) {
        // The parent's own cache key, so a join below a join whose type changed is keyed under it.
        JoinKey parentKey = keys.getOrDefault(parent, key.parent());
        JoinType resolvedType = resolvedType(key, parentKey, type);
        JoinKey cacheKey = new JoinKey(parentKey, attribute, resolvedType, key.alias());
        if (!key.equals(cacheKey) && joinFilters.containsKey(cacheKey)) {
            // Another join key resolves to the filtered join: it would read it, or cache it before the filter (D-194).
            throw JoinFilterChecks.refused(description + ": resolves to the join of a joinFilter(...) as "
                    + resolvedType + " " + cacheKey + "; the filtered join is read only through its own key");
        }
        if (resolvedType == JoinType.INNER) {
            // A filter only blanks columns, so an INNER join below it, as rendered, would drop the row (D-195).
            for (JoinKey above = parentKey; above != null; above = above.parent()) {
                if (joinFilters.containsKey(above)) {
                    throw JoinFilterChecks.refused(description + ": renders INNER below the joinFilter(...) join '"
                            + (above.parent() == null ? above.alias() : above.attribute()) + "'; declare it LEFT, "
                            + "since a join filter only blanks columns and never removes rows");
                }
            }
        }
        Resolved cached = joins.get(cacheKey);
        if (cached != null) {
            // Lambdas cannot be compared, so an equal key must carry the very same condition instance (R-COL-04).
            if (cached.condition() != condition) {
                throw new ModelQueryDefinitionException(
                        MqCode.MQ1101, description + " is defined twice with different on(...) conditions");
            }
            touch(cached.from());
            return cached.from();
        }
        if (buildingFilter) {
            throw JoinFilterChecks.refused(description + ": a joinFilter(...) column reads a path below the filtered "
                    + "join, which its ON cannot reach");
        }
        Join<?, ?> join;
        try {
            join = parent.join(attribute, resolvedType);
        } catch (IllegalArgumentException e) {
            throw new ModelQueryDefinitionException(MqCode.MQ1002, description + ": "
                    + parent.getJavaType().getSimpleName() + " has no attribute '" + attribute + "'", e);
        }
        // The filter's own columns must resolve this join while its ON is being built (R-COL-39).
        joins.put(cacheKey, new Resolved(join, condition));
        keys.put(join, cacheKey);
        var predicates = new ArrayList<Predicate>();
        if (condition != null) {
            predicates.add((Predicate) ((BiFunction) condition).apply(join, cb));
        }
        predicates.addAll(joinFilter(key));
        if (!predicates.isEmpty()) {
            Predicate on = predicates.size() == 1 ? predicates.get(0) : cb.and(predicates.toArray(Predicate[]::new));
            if (required.contains(key)) {
                // Hibernate 6 renders the join off a correlated root as the sub-query's FROM and drops its ON.
                // For an INNER join the condition means the same in WHERE, where every provider keeps it.
                requiredConditions.add(on);
            } else {
                join.on(on);
            }
        }
        if (join.getAttribute() instanceof PluralAttribute<?, ?, ?>) {
            toManyJoins.add(join);
        }
        touch(join);
        if (existsPath != null) {
            existsJoined.add(join.getJavaType());
        }
        return join;
    }

    private void touch(From<?, ?> from) {
        if (touchedToMany != null && from instanceof Join<?, ?> join
                && join.getAttribute() instanceof PluralAttribute<?, ?, ?> && !touchedToMany.contains(join)) {
            touchedToMany.add(join);
        }
    }

    /**
     * {@code parent} read as {@code subtype}, the node shared per statement. {@code read} says a column of the treated
     * node is read, rather than a join made from it or the node itself reached as a path (D-157).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> From<?, T> treat(JoinKey key, From<?, ?> parent, Class<T> subtype, String description, boolean read) {
        Class<?> reached = parent.getJavaType();
        if (!reached.isAssignableFrom(subtype) && !subtype.isAssignableFrom(reached)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1106, description + ": cannot treat "
                    + reached.getSimpleName() + " as " + subtype.getSimpleName());
        }
        if (custom > 0 && !subtype.isAssignableFrom(reached)) {
            throw new ModelQueryConfigurationException(MqCode.MQ4016, description + ": a treated node read inside "
                    + "add(...) has no site the provider's table can check; filter it with a typed condition");
        }
        JoinKey cacheKey = new JoinKey(keys.getOrDefault(parent, key.parent()), subtype.getName(), null, "");
        Resolved cached = joins.get(cacheKey);
        From<?, T> treated;
        if (cached != null) {
            treated = (From<?, T>) cached.from();
        } else {
            TreatRenderer renderer = renderOptions.treatRenderer().orElseThrow(() ->
                    new ModelQueryConfigurationException(MqCode.MQ4014, description
                            + ": a treated path needs the provider's TreatRenderer (ProviderSupport#treats(emf))"));
            treated = subtype.isAssignableFrom(reached) ? (From<?, T>) parent
                    : renderer.treat(cb, (From) parent, subtype);
            joins.put(cacheKey, new Resolved(treated, null));
            if (treated != parent) {
                keys.put(treated, cacheKey);
            }
        }
        if (treated != parent && parent == root && declares != null) {
            if (declared == null) {
                declared = new Declared(declares, reached);
                narrowings.add(declared);
            }
            declared.types.add(subtype);
            if (read) {
                treatedReads++;
            }
        }
        return treated;
    }

    private JoinType resolvedType(JoinKey key, JoinKey parentKey, JoinType type) {
        if (required.contains(key)) {
            return JoinType.INNER; // a LEFT join would make every row "exist"
        }
        if (leftJoining > 0 && type == JoinType.INNER
                && !joins.containsKey(new JoinKey(parentKey, key.attribute(), JoinType.INNER, key.alias()))) {
            return JoinType.LEFT;
        }
        return type;
    }
}
