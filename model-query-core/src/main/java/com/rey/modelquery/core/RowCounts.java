package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Bindable;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;

/**
 * The count of a table's identity at every site that counts one: a table count, a count of a table's id column, a
 * correlated count and the page total (R-AGG-19, R-EXE-05). Where the identity cannot be null on any row the
 * statement reads, the count is the provider's row count ({@link AggregateRenderer#rowCount}); elsewhere it counts the
 * identity as before. A source root's constant, {@code count(1)}, is counted as it is; a joined source's
 * first key column is the row count only through an INNER join.
 *
 * @implSpec R-AGG-19, D-185
 */
@EngineFacing
@Incubating
public final class RowCounts {

    private RowCounts() {}

    /**
     * {@code count(counted)} in {@code ctx}'s statement: the renderer's row count when {@link #eligible} holds,
     * else {@code cb.count(counted)}.
     */
    @EngineFacing
    public static Expression<Long> count(JoinContext ctx, Expression<?> counted) {
        CriteriaBuilder cb = ctx.cb();
        if (!eligible(ctx, counted)) {
            return cb.count(counted);
        }
        return ctx.renderOptions().aggregateRenderer().orElse(AggregateRenderer.DEFAULT).rowCount(cb, counted);
    }

    /**
     * Whether {@code counted} is the identity of a criteria {@code Root}, or of a chain of INNER joins from one, with
     * no treated node, in a statement without a customizer: then it is non-null on every row and its count is the
     * row count. A table's identity is its {@code From}, or the path to that {@code From}'s own single id attribute;
     * a foreign-key read is neither. The walk follows {@code Join#getParent()}, not the context's root, which in a
     * {@code rootedAt} context is a join. A joined source's table is counted by its first key column
     * ({@link JoinContext#joinedSourceKey}), classified by its join kind: an INNER join is a hop of the chain, from the
     * {@code From} it joins, and a LEFT join keeps the column's count, 0 where no source row matched (R-AGG-19).
     */
    static boolean eligible(JoinContext ctx, Expression<?> counted) {
        if (ctx.customized()) {
            // The customizer runs after the selection is built and could add a RIGHT or FULL join (D-185).
            return false;
        }
        JoinContext.JoinedSource joined = ctx.joinedSourceCounted(counted);
        if (joined != null && !joined.inner()) {
            return false;
        }
        From<?, ?> node = joined != null ? joined.outer() : identified(counted);
        while (node != null) {
            if (ctx.treatedNode(node)) {
                return false;
            }
            if (node instanceof Root<?>) {
                return true;
            }
            if (!(node instanceof Join<?, ?> join) || join.getJoinType() != JoinType.INNER) {
                return false;
            }
            node = join.getParent();
        }
        return false;
    }

    /** The entity {@code From} whose identity {@code counted} is, or {@code null}. */
    private static From<?, ?> identified(Expression<?> counted) {
        if (counted instanceof From<?, ?> from) {
            return entityOf(from) != null ? from : null;
        }
        if (!(counted instanceof Path<?> path) || !(path.getParentPath() instanceof From<?, ?> from)
                || !(path.getModel() instanceof Attribute<?, ?> attribute)) {
            return null;
        }
        EntityType<?> entity = entityOf(from);
        if (entity == null || !entity.hasSingleIdAttribute()) {
            return null;
        }
        String id = entity.getId(entity.getIdType().getJavaType()).getName();
        return attribute.getName().equals(id) ? from : null;
    }

    /**
     * The entity {@code from} reads, or {@code null} for an embeddable or a source's CTE: a root's model is its entity,
     * a join's is the attribute it joins through, whose type or element type is the entity.
     */
    static EntityType<?> entityOf(From<?, ?> from) {
        Bindable<?> model = from.getModel();
        Type<?> type = model instanceof EntityType<?> entity ? entity
                : model instanceof SingularAttribute<?, ?> singular ? singular.getType()
                : model instanceof PluralAttribute<?, ?, ?> plural ? plural.getElementType() : null;
        return type instanceof EntityType<?> entity ? entity : null;
    }
}
