package com.rey.modelquery.core;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.SingularAttribute;
import java.util.List;

/**
 * Which selected columns a {@code persist} returning query can fill from the flushed entity alone: a root attribute,
 * through embeddables, or the id of a to-one association joined from the root without {@code on(...)}; never an
 * expression, an aggregate, a to-one or collection attribute itself, nor a join beyond a to-one id (R-WRT-48).
 */
final class ReturningColumns {

    /** What a refused column's message advises, but for an association attribute itself. */
    private static final String SELECT_AFTER = "; select it with a query after the persist instead";

    private ReturningColumns() {
    }

    /**
     * Checks each of {@code columns}, resolving its path through {@code joins}, which is rooted at {@code root}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1809} naming the first column the entity cannot fill; as
     *     {@link ColumnField#path} does for a column whose path does not resolve
     */
    static void check(String model, List<? extends SelectField<?, ?>> columns, ManagedType<?> root,
            JoinContext joins) {
        for (SelectField<?, ?> column : columns) {
            String unfillable = unfillable(column, root, joins);
            if (unfillable != null) {
                String name = column instanceof ColumnField<?, ?, ?> field ? field.path() : column.name();
                throw new ModelQueryDefinitionException(MqCode.MQ1809, model + "." + name + ": persist "
                        + "returning a model fills it from the flushed entity, without a statement, and it is "
                        + unfillable);
            }
        }
    }

    /** Why the entity alone cannot fill {@code column} and what to select instead, or {@code null} when it can. */
    private static String unfillable(SelectField<?, ?> column, ManagedType<?> root, JoinContext joins) {
        if (column instanceof AggregateField<?, ?>) {
            return "an aggregate" + SELECT_AFTER;
        }
        if (!(column instanceof ColumnField<?, ?, ?> field)) {
            return "an expression" + SELECT_AFTER;
        }
        TableField<?, ?> table = field.table();
        for (TableField<?, ?> join = table; join.parent() != null; join = join.parent()) {
            if (join.hasCondition()) {
                return "on the join with on(...) " + join + SELECT_AFTER;
            }
        }
        String beyond = "on the join " + table + ", beyond the id of a to-one association" + SELECT_AFTER;
        if (table.parent() != null && table.parent().parent() != null) {
            return beyond;
        }
        field.path(joins); // MQ1001, MQ1002 or MQ1003 before the metamodel is walked as the path resolved
        if (table.parent() == null) {
            Attribute<?, ?> last = attribute(root, field.name());
            if (last.isCollection()) {
                return "the collection " + field.name() + ", which a query joins" + SELECT_AFTER;
            }
            // The entity holds the target as a reference, which has only its id without a statement
            return last.isAssociation() ? "the association " + field.name() + " itself; select its target's id "
                    + "through a join from the root instead" : null;
        }
        Attribute<?, ?> joined = root.getAttribute(table.path());
        if (joined instanceof SingularAttribute<?, ?> toOne && toOne.isAssociation()
                && toOne.getType() instanceof IdentifiableType<?> target && target.hasSingleIdAttribute()
                && field.name().equals(target.getId(target.getIdType().getJavaType()).getName())) {
            return null;
        }
        return beyond;
    }

    /** The attribute {@code path} ends at, walking embeddables from {@code root}, as the column's path resolved. */
    private static Attribute<?, ?> attribute(ManagedType<?> root, String path) {
        String[] segments = path.split("\\.");
        ManagedType<?> type = root;
        for (int i = 0; i < segments.length - 1; i++) {
            type = (ManagedType<?>) ((SingularAttribute<?, ?>) type.getAttribute(segments[i])).getType();
        }
        return type.getAttribute(segments[segments.length - 1]);
    }
}
