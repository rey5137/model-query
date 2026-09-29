package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Per-query join state: caches the joins created for one Criteria query by {@link JoinKey}. The only mutable holder
 * of join state (CC-IMM-02); create one per query build and never share it.
 *
 * @implSpec R-COL-02
 */
@Incubating
public final class JoinContext {

    private record Resolved(From<?, ?> from, Object condition) {}

    private final Root<?> root;
    private final CriteriaBuilder cb;
    private final Map<JoinKey, Resolved> joins = new HashMap<>();

    private JoinContext(Root<?> root, CriteriaBuilder cb) {
        this.root = root;
        this.cb = cb;
    }

    /** A context over {@code root}, the query's root table. */
    public static JoinContext of(Root<?> root, CriteriaBuilder cb) {
        return new JoinContext(root, cb);
    }

    From<?, ?> root() {
        return root;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    From<?, ?> join(
            JoinKey key,
            From<?, ?> parent,
            String attribute,
            JoinType type,
            BiFunction<? extends From<?, ?>, CriteriaBuilder, ?> condition,
            String description) {
        Resolved cached = joins.get(key);
        if (cached != null) {
            // Lambdas cannot be compared, so an equal key must carry the very same condition instance (R-COL-04).
            if (cached.condition() != condition) {
                throw new ModelQueryDefinitionException(
                        MqCode.MQ1101, description + " is defined twice with different on(...) conditions");
            }
            return cached.from();
        }
        Join<?, ?> join = parent.join(attribute, type);
        if (condition != null) {
            join.on((jakarta.persistence.criteria.Predicate) ((BiFunction) condition).apply(join, cb));
        }
        joins.put(key, new Resolved(join, condition));
        return join;
    }
}
