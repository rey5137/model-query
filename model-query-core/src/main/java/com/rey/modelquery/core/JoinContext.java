package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
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

    private final From<?, ?> root;
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

    private JoinContext(From<?, ?> root, CriteriaBuilder cb, CommonAbstractCriteria query, JoinKey rootKey,
            Set<JoinKey> required, RenderOptions renderOptions, Set<Class<?>> existsJoined) {
        this.root = root;
        this.cb = cb;
        this.query = query;
        this.rootKey = rootKey;
        this.required = required;
        this.renderOptions = renderOptions;
        this.existsJoined = existsJoined;
    }

    /** A context over {@code root}, the query's root table, rendering with {@link RenderOptions#portable()}. */
    public static JoinContext of(Root<?> root, CriteriaBuilder cb) {
        return new JoinContext(root, cb, null, null, Set.of(), RenderOptions.portable(), new HashSet<>());
    }

    /** A context over the root of {@code query}, which can also render {@code exists} sub-queries. */
    static JoinContext of(Root<?> root, CriteriaBuilder cb, CommonAbstractCriteria query, RenderOptions options) {
        return new JoinContext(root, cb, query, null, Set.of(), options, new HashSet<>());
    }

    /** Whether an {@code exists} sub-query of this build rendered so far, at any depth (R-WRT-11). */
    boolean renderedExists() {
        return !existsJoined.isEmpty(); // an exists always joins its path
    }

    /**
     * Whether an {@code exists} sub-query of this build joined {@code entity}, a type of its hierarchy or one sharing
     * it, so reads the table of a bulk write on {@code entity} (R-WRT-11).
     */
    boolean existsReads(Class<?> entity) {
        return existsJoined.stream().anyMatch(type -> type.isAssignableFrom(entity) || entity.isAssignableFrom(type));
    }

    From<?, ?> root() {
        return root;
    }

    JoinKey rootKey() {
        return rootKey;
    }

    CriteriaBuilder cb() {
        return cb;
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
        JoinContext ctx = existsPath == null
                ? new JoinContext(sub.correlate((Root) root), cb, sub, null, path.keysUpTo(null), renderOptions,
                        existsJoined)
                : new JoinContext(sub.correlate((Join) existsFrom), cb, sub, existsPath.key(),
                        path.keysUpTo(existsPath.key()), renderOptions, existsJoined);
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
