package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A node in a join path: an immutable definition, not a join. It becomes a Criteria {@link From} only through a
 * {@link JoinContext}, so one constant serves any number of concurrent queries.
 *
 * @param <P> the parent table's entity type
 * @param <T> the entity type this node reaches
 * @implSpec R-COL-01
 */
@Incubating
public final class TableField<P, T> {

    private final Class<T> rootEntity;
    private final TableField<?, P> parent;
    private final String attribute;
    private final JoinType type;
    private final String alias;
    private final BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition;
    private final JoinKey key;

    private TableField(
            Class<T> rootEntity,
            TableField<?, P> parent,
            String attribute,
            JoinType type,
            String alias,
            BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition) {
        this.rootEntity = rootEntity;
        this.parent = parent;
        this.attribute = attribute;
        this.type = type;
        this.alias = alias;
        this.condition = condition;
        this.key = rootEntity != null
                ? JoinKey.root(rootEntity)
                : new JoinKey(parent.key, attribute, type, alias);
    }

    /** The query's root table. */
    public static <T> TableField<T, T> root(Class<T> entity) {
        return new TableField<>(Objects.requireNonNull(entity, "entity"), null, null, null, "", null);
    }

    /** A join from {@code parent} along {@code attribute}. */
    public static <P, T> TableField<P, T> join(TableField<?, P> parent, String attribute, JoinType type) {
        return new TableField<>(
                null,
                Objects.requireNonNull(parent, "parent"),
                Objects.requireNonNull(attribute, "attribute"),
                Objects.requireNonNull(type, "type"),
                "",
                null);
    }

    /** The same path under its own alias, which resolves to a separate join (R-COL-03). */
    public TableField<P, T> as(String alias) {
        return new TableField<>(rootEntity, parent, attribute, type, Objects.requireNonNull(alias, "alias"), condition);
    }

    /** Adds an {@code ON} condition, applied through {@code Join#on}; requires {@link #as(String)} (R-COL-04). */
    public TableField<P, T> on(BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition) {
        return new TableField<>(
                rootEntity, parent, attribute, type, alias, Objects.requireNonNull(condition, "condition"));
    }

    /** The same node under a different parent, keeping alias and {@code ON} condition. */
    public TableField<P, T> withParent(TableField<?, P> newParent) {
        if (rootEntity != null) {
            return this; // a root has no parent to replace
        }
        return new TableField<>(
                null, Objects.requireNonNull(newParent, "newParent"), attribute, type, alias, condition);
    }

    /** Resolves this node to a {@link From}, creating the join on first use within {@code ctx}. */
    @SuppressWarnings("unchecked")
    public From<?, T> resolve(JoinContext ctx) {
        if (rootEntity != null || key.equals(ctx.rootKey())) {
            return (From<?, T>) ctx.root();
        }
        if (condition != null && alias.isEmpty()) {
            throw new ModelQueryDefinitionException(
                    MqCode.MQ1102,
                    describe() + ": on(...) has no alias; call as(...) so the condition can be compared");
        }
        From<?, P> parentFrom = parent.resolve(ctx);
        return (From<?, T>) ctx.join(key, parentFrom, attribute, type, condition, describe());
    }

    /** The entity of a root node, or {@code null} for a join. */
    Class<T> rootEntity() {
        return rootEntity;
    }

    JoinKey key() {
        return key;
    }

    /** Whether this node is {@code ancestor} or sits below it, comparing join keys (CC-IMM-04). */
    boolean isAtOrBelow(TableField<?, ?> ancestor) {
        for (TableField<?, ?> node = this; node != null; node = node.parent) {
            if (node.key.equals(ancestor.key)) {
                return true;
            }
        }
        return false;
    }

    /** The keys of this node and its parents, stopping below {@code top}, or below the root when it is null. */
    Set<JoinKey> keysUpTo(JoinKey top) {
        var keys = new HashSet<JoinKey>();
        for (TableField<?, ?> node = this; node.parent != null && !node.key.equals(top); node = node.parent) {
            keys.add(node.key);
        }
        return keys;
    }

    String describe() {
        if (rootEntity != null) {
            return "root " + rootEntity.getSimpleName();
        }
        return "join '" + attribute + "' (" + type + (alias.isEmpty() ? "" : ", alias '" + alias + "'") + ")";
    }
}
