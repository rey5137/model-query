package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** The memo key of {@link #expression}: the expression and whether the context was left-joining when it rendered. */
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
    /** The key each join was cached under, which is not the declared key when its type was changed. */
    private final Map<From<?, ?>, JoinKey> keys = new IdentityHashMap<>();
    /** In an {@code exists}, the {@code on(...)} conditions of its required joins, rendered in its WHERE instead. */
    private final List<Predicate> requiredConditions = new ArrayList<>();
    /** In an {@code exists}, its path and the join it resolved to, for a nested {@code exists} to correlate with. */
    private TableField<?, ?> existsPath;
    private From<?, ?> existsFrom;
    private int leftJoining;
    /**
     * The types every {@code exists} sub-query of this build joined, nested ones included: shared with each nested
     * context, so a bulk write learns whether a sub-query reads its own table (R-WRT-11).
     */
    private final Set<Class<?>> existsJoined;
    /**
     * Inside a correlated {@code exists} over a sub-select, the context a lifted outer column resolves through, over
     * the correlated outer root; {@code null} everywhere else (R-FLT-17).
     */
    private final JoinContext outer;
    private boolean readsToOneLeft;

    private JoinContext(From<?, ?> root, Class<?> rootType, CriteriaBuilder cb, CommonAbstractCriteria query,
            JoinKey rootKey, Set<JoinKey> required, RenderOptions renderOptions, Set<Class<?>> existsJoined) {
        this(root, rootType, cb, query, rootKey, required, renderOptions, existsJoined, null, new int[1]);
    }

    private JoinContext(From<?, ?> root, Class<?> rootType, CriteriaBuilder cb, CommonAbstractCriteria query,
            JoinKey rootKey, Set<JoinKey> required, RenderOptions renderOptions, Set<Class<?>> existsJoined,
            JoinContext outer, int[] repeatedExpressionBinds) {
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

    JoinContext rootedAt(From<?, ?> join, Class<?> entity) {
        JoinContext ctx = new JoinContext(join, entity, cb, query, null, Set.of(), renderOptions, existsJoined, null,
                repeatedExpressionBinds);
        ctx.readsToOneLeft = readsToOneLeft;
        return ctx;
    }

    boolean readsToOneLeft() {
        return readsToOneLeft;
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
        return root;
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
     * Adds the repeats {@code rendered} counted, a context of its own whose sub-query this statement keeps, such as a
     * write's {@code EXISTS} (R-COL-19, D-80).
     */
    void addRepeatedExpressionBinds(JoinContext rendered) {
        repeatedExpressionBinds[0] += rendered.repeatedExpressionBinds[0];
    }

    RenderOptions renderOptions() {
        return renderOptions;
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
                    existsJoined, outer, repeatedExpressionBinds);
        } else {
            From<?, ?> correlated = sub.correlate((Join) existsFrom);
            ctx = new JoinContext(correlated, correlated.getJavaType(), cb, sub, existsPath.key(),
                    path.keysUpTo(existsPath.key()), renderOptions, existsJoined, outer, repeatedExpressionBinds);
        }
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
     * The outer root path a lifted column reads, resolved through this context's correlation. {@code lift} is the
     * column an {@link Outer#column} built; resolving it here rather than outside any correlation is {@code MQ1310}
     * (R-FLT-17).
     */
    @SuppressWarnings("unchecked")
    <T, C> Path<C> liftedColumn(ColumnField<?, T, C> lift) {
        if (outer == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1310, lift
                    + " was resolved outside the correlation it was made in; lift it again inside exists(...)");
        }
        return (Path<C>) lift.liftedFrom().orElseThrow().path(outer);
    }

    /**
     * {@code column IN (SELECT s.c FROM <sub's root> WHERE <sub's filters>)} (R-FLT-16); {@code negated} is the
     * null-safe {@code notIn}: {@code NOT IN (... AND s.c IS NOT NULL) OR column IS NULL}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <C> Predicate inSubSelect(ScalarField<?, C> column, SubSelect<?, C> sub, boolean negated) {
        if (query == null) {
            throw new IllegalStateException("in(...) over a sub-select needs the JoinContext of a ModelQuery build");
        }
        Subquery<C> sq = query.subquery(sub.column().type());
        Root<?> inner = sq.from(sub.rootEntity());
        JoinContext ctx = new JoinContext(inner, sub.rootEntity(), cb, sq, null, Set.of(), renderOptions,
                existsJoined, null, repeatedExpressionBinds);
        Path<C> selected = sub.column().path(ctx);
        // A lifted column reads the outer root, so its embeddable check runs against the outer context, not this one.
        boolean embeddable = column instanceof ColumnField<?, ?, ?> outerColumn && outerColumn.isLifted() && outer != null
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    Predicate existsSubSelect(SubSelect<?, ?> sub, List<Filter> correlation, boolean negated) {
        if (query == null) {
            throw new IllegalStateException("exists(...) needs the JoinContext of a ModelQuery build");
        }
        Subquery<Integer> sq = query.subquery(Integer.class);
        Root<?> inner = sq.from(sub.rootEntity());
        // The correlated outer root a lifted column reads; a through child's root is a join (D-100).
        From<?, ?> correlated = root instanceof Root<?> ? sq.correlate((Root) root) : sq.correlate((Join) root);
        JoinContext outerCtx = new JoinContext(correlated, rootType, cb, sq, rootKey, Set.of(), renderOptions,
                existsJoined, null, repeatedExpressionBinds);
        JoinContext ctx = new JoinContext(inner, sub.rootEntity(), cb, sq, null, Set.of(), renderOptions,
                existsJoined, outerCtx, repeatedExpressionBinds);
        sq.select(cb.literal(1));
        var where = new ArrayList<>(ConditionGroup.toPredicates(sub.filters(), ctx));
        where.addAll(ConditionGroup.toPredicates(correlation, ctx));
        if (!where.isEmpty()) {
            sq.where(where.toArray(Predicate[]::new));
        }
        existsJoined.add(sub.rootEntity());
        Predicate exists = cb.exists(sq);
        return negated ? cb.not(exists) : exists;
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
        Resolved cached = joins.get(cacheKey);
        if (cached != null) {
            // Lambdas cannot be compared, so an equal key must carry the very same condition instance (R-COL-04).
            if (cached.condition() != condition) {
                throw new ModelQueryDefinitionException(
                        MqCode.MQ1101, description + " is defined twice with different on(...) conditions");
            }
            return cached.from();
        }
        Join<?, ?> join;
        try {
            join = parent.join(attribute, resolvedType);
        } catch (IllegalArgumentException e) {
            throw new ModelQueryDefinitionException(MqCode.MQ1002, description + ": "
                    + parent.getJavaType().getSimpleName() + " has no attribute '" + attribute + "'", e);
        }
        if (condition != null) {
            Predicate on = (Predicate) ((BiFunction) condition).apply(join, cb);
            if (required.contains(key)) {
                // Hibernate 6 renders the join off a correlated root as the sub-query's FROM and drops its ON.
                // For an INNER join the condition means the same in WHERE, where every provider keeps it.
                requiredConditions.add(on);
            } else {
                join.on(on);
            }
        }
        joins.put(cacheKey, new Resolved(join, condition));
        keys.put(join, cacheKey);
        if (existsPath != null) {
            existsJoined.add(join.getJavaType());
        }
        return join;
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
