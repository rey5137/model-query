package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Condition;
import com.rey.modelquery.core.ConstantRenderer;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import java.util.List;
import java.util.stream.Collectors;
import org.hibernate.metamodel.mapping.EntityMappingType;

/**
 * A conflict arbiter predicate as SQL (R-WRT-52): the predicate PostgreSQL matches against a partial unique index,
 * rendered as {@code column op literal} comparisons, ANDed. PostgreSQL infers the index only from an expression equal
 * to the index's own predicate, so every value is inlined as a literal through its attribute's mapping, never bound
 * (R-COL-27, D-128). A column is the root's mapped column name, unqualified: the predicate reads no other table, and
 * PostgreSQL matches it against the index's own text. The engine refuses every construct this does not render at
 * {@code build()}, so the accepted set here and {@code InsertRules.checkConflictArbiter} agree.
 *
 * @implSpec R-WRT-52
 */
final class ArbiterSql {

    /** The value's stored form, through its attribute's mapping (R-COL-27, D-128). */
    private final ConstantRenderer constants;

    /** The mapping the arbiter's columns resolve through. */
    private final EntityMappingType entity;

    /** The entity class {@link #constants} renders the attribute of. */
    private final Class<?> entityClass;

    ArbiterSql(ConstantRenderer constants, EntityMappingType entity, Class<?> entityClass) {
        this.constants = constants;
        this.entity = entity;
        this.entityClass = entityClass;
    }

    /** {@code conditions} as one SQL predicate, their comparisons ANDed. */
    String render(List<Condition> conditions) {
        return conditions.stream().map(this::render).collect(Collectors.joining(" and "));
    }

    private String render(Condition condition) {
        return switch (condition.kind()) {
            case EQ -> column(condition) + " = " + literal(condition);
            case NE -> column(condition) + " <> " + literal(condition);
            case GT -> column(condition) + " > " + literal(condition);
            case GTE -> column(condition) + " >= " + literal(condition);
            case LT -> column(condition) + " < " + literal(condition);
            case LTE -> column(condition) + " <= " + literal(condition);
            case IS_NULL -> column(condition) + " is null";
            case IS_NOT_NULL -> column(condition) + " is not null";
            case IN -> column(condition) + " in (" + literals(condition) + ")";
            case NOT_IN -> column(condition) + " not in (" + literals(condition) + ")";
            case AND -> "(" + render(condition.children()) + ")";
            case OR -> "(" + condition.children().stream().map(this::render).collect(Collectors.joining(" or ")) + ")";
            case NOT -> "not (" + render(condition.children()) + ")";
            default -> throw new ModelQueryDefinitionException(MqCode.MQ1804, condition + ": the arbiter predicate "
                    + "of a partial unique index takes plain comparisons of root columns, which the provider cannot "
                    + "render from " + condition.kind());
        };
    }

    /** {@code condition}'s column, as the mapped column name of the root's table. */
    private String column(Condition condition) {
        return HibernateMutations.selectable(entity, attribute(condition)).getSelectionExpression();
    }

    private String literals(Condition condition) {
        return condition.values().stream().map(value -> literal(condition, value)).collect(Collectors.joining(", "));
    }

    private String literal(Condition condition) {
        return literal(condition, condition.values().get(0));
    }

    /** {@code value} as the SQL literal its attribute stores, or {@code MQ1508} where the mapping names none. */
    private String literal(Condition condition, Object value) {
        String attribute = attribute(condition);
        Object stored = constants.storedValue(entityClass, attribute, value)
                .orElseThrow(() -> new ModelQueryDefinitionException(MqCode.MQ1508, attribute + ": the arbiter "
                        + "predicate's value " + value + " has no literal rendering through the attribute's mapping"));
        if (stored instanceof String text) {
            return "'" + text.replace("'", "''") + "'";
        }
        if (stored instanceof Boolean || stored instanceof Number number && finite(number)) {
            return stored.toString();
        }
        throw new ModelQueryDefinitionException(MqCode.MQ1508, attribute + ": the arbiter predicate's value " + value
                + " stores as " + stored.getClass().getName() + ", which is no SQL literal");
    }

    /** Whether {@code number} has an unquoted SQL literal: NaN and infinity have none. */
    private static boolean finite(Number number) {
        return !(number instanceof Double || number instanceof Float) || Double.isFinite(number.doubleValue());
    }

    /** {@code condition}'s attribute path, which {@code build()} checked to be a root column (R-WRT-52). */
    private static String attribute(Condition condition) {
        return condition.column()
                .filter(ColumnField.class::isInstance)
                .map(field -> ((ColumnField<?, ?, ?>) field).name())
                .orElseThrow(() -> new IllegalStateException(condition + ": an arbiter condition without a column"));
    }
}
