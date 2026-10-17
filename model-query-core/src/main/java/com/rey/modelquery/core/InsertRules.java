package com.rey.modelquery.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The {@code build()} checks the insert definitions share (R-WRT-27, R-WRT-29, R-WRT-34, D-117). */
final class InsertRules {

    /** The clause a rejected {@code doUpdate} {@code where} belongs to (R-WRT-34). */
    private static final String CONFLICT_WHERE = "doUpdate(...).where(...)";

    /** The clause a rejected conflict arbiter belongs to (R-WRT-52). */
    private static final String ARBITER = "onConflict(...).where(...)";

    private InsertRules() {
    }

    /**
     * {@code table}, checked to be a root an insert can write.
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code table} is a join or a source root, which no
     *     write reads (R-SRC-10)
     */
    static <T> TableField<T, T> requireRoot(TableField<T, T> table, String method) {
        Objects.requireNonNull(table, "root");
        if (table.rootEntity() == null || table.isSource()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1203,
                    method + "(...) takes a TableField.root(...), not the " + table.describe());
        }
        return table;
    }

    /**
     * Checks that {@code column} sits on {@code root} itself.
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} for a column on another table, as through a
     *     self-referencing join
     */
    static void requireOnRoot(TableField<?, ?> root, ColumnField<?, ?, ?> column, String what) {
        if (!Objects.requireNonNull(column, "column").table().key().equals(root.key())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1801, column + ": " + what + " writes only the root "
                    + root.rootEntity().getSimpleName() + "'s own columns, not the " + column.table().describe());
        }
    }

    /**
     * Checks the {@code set} constants and {@code setExpression} values of an insert writing {@code columns}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} for a constant on a column the model has, a column set
     *     twice, or a column not on the root; {@code MQ1609} for a {@code setExpression} on a converted column, whose
     *     value has the entity attribute's type (R-WRT-53)
     */
    static void checkConstants(InsertColumns<?, ?> columns, List<Assignment<?, ?>> constants) {
        Set<String> set = new HashSet<>();
        for (Assignment<?, ?> constant : constants) {
            ColumnField<?, ?, ?> column = constant.column();
            requireOnRoot(columns.root(), column, "set(...)");
            if (constant instanceof Assignment.Computed<?, ?> && column.converterClass() != null) {
                throw converted(column);
            }
            if (columns.indexOf(column) >= 0) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, column + ": set(...) on a column the insert "
                        + "model already writes; each row's value is written");
            }
            if (!set.add(column.name())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, column + ": set(...) twice");
            }
        }
    }

    /** {@code MQ1609} for a {@code setExpression} on {@code column}, whose converter the expression cannot pass. */
    static ModelQueryDefinitionException converted(ColumnField<?, ?, ?> column) {
        return new ModelQueryDefinitionException(MqCode.MQ1609, column + ": setExpression(...) on a column "
                + "converted by " + column.converterClass().getSimpleName() + "; the expression's value has the entity "
                + "attribute's type, not the model's");
    }

    /**
     * Checks a conflict update's {@code where}: the conflict action has no join context, so it reads root columns
     * only, with no {@code exists} and no sub-select (R-WRT-34).
     *
     * @throws ModelQueryDefinitionException {@code MQ1804} for a joined column, an {@code exists} or a sub-select
     */
    static void checkConflictWhere(TableField<?, ?> root, List<Condition> conditions) {
        for (Condition condition : conditions) {
            if (ExpressionField.containsCorrelated(condition)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1804, CONFLICT_WHERE + " with a correlated value: "
                        + "the conflict action filters the stored row on its own columns, with no join or sub-query");
            }
            switch (condition.kind()) {
                case EXISTS, NOT_EXISTS, IN_SUBSELECT, NOT_IN_SUBSELECT, EXISTS_SUBSELECT, NOT_EXISTS_SUBSELECT ->
                    throw new ModelQueryDefinitionException(MqCode.MQ1804, CONFLICT_WHERE + " with "
                            + condition.kind() + ": the conflict action filters the stored row on its own columns, "
                            + "with no join or sub-query");
                default -> {
                    condition.column().ifPresent(column -> requireRootColumn(root, column, CONFLICT_WHERE));
                    condition.right().ifPresent(column -> requireRootColumn(root, column, CONFLICT_WHERE));
                    condition.operands().forEach(column -> requireRootColumn(root, column, CONFLICT_WHERE));
                    checkConflictWhere(root, condition.children());
                }
            }
        }
    }

    /**
     * Checks a conflict arbiter's {@code where}: the predicate of a partial unique index names root columns and
     * compares each with a plain value, so PostgreSQL can match it against the index's own predicate (R-WRT-52). The
     * provider renders it into the statement with literal values, where an expression, {@code exists} or sub-select
     * has no rendering.
     *
     * @throws ModelQueryDefinitionException {@code MQ1804} for any other kind, a column not on the root, a joined
     *     column, an expression operand, an {@code Expr.value} or {@code Expr.constant}, or an empty {@code in},
     *     {@code notIn} or {@code or}, which has no SQL of its own
     */
    static void checkConflictArbiter(TableField<?, ?> root, List<Condition> conditions) {
        for (Condition condition : conditions) {
            switch (condition.kind()) {
                case EQ, NE, GT, GTE, LT, LTE, IN, NOT_IN -> {
                    condition.column().ifPresent(field -> arbiterColumn(root, field));
                    if (condition.values().isEmpty()) {
                        throw empty(condition);
                    }
                    condition.values().forEach(InsertRules::arbiterValue);
                }
                case IS_NULL, IS_NOT_NULL -> condition.column().ifPresent(field -> arbiterColumn(root, field));
                case AND, OR, NOT -> {
                    if (condition.children().isEmpty()) {
                        throw empty(condition);
                    }
                    checkConflictArbiter(root, condition.children());
                }
                default -> throw new ModelQueryDefinitionException(MqCode.MQ1804, ARBITER + " with "
                        + condition.kind() + ": the arbiter predicate of a partial unique index compares root columns "
                        + "with plain values, and holds no exists, sub-select, expression or other operator");
            }
        }
    }

    /** The refusal of an arbiter {@code condition} with no operand: it renders as no partial index's predicate. */
    private static ModelQueryDefinitionException empty(Condition condition) {
        return new ModelQueryDefinitionException(MqCode.MQ1804, ARBITER + " with an empty " + condition.kind()
                + ": a partial unique index's predicate names its values, and an empty list or branch has none");
    }

    /** {@code field}, checked to be a column of {@code root}; an expression or another table's column is refused. */
    private static void arbiterColumn(TableField<?, ?> root, SelectField<?, ?> field) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            if (!column.table().key().equals(root.key())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1804, column + ": " + ARBITER + " reads the "
                        + column.table().describe() + "; the arbiter has no join, so it filters on root columns");
            }
            return;
        }
        throw new ModelQueryDefinitionException(MqCode.MQ1804, field + ": " + ARBITER + " takes a root column, and "
                + "no expression");
    }

    /** {@code value}, checked to be a plain value: a field, such as {@code Expr.value}, has no literal rendering. */
    private static void arbiterValue(Object value) {
        if (value instanceof SelectField<?, ?>) {
            throw new ModelQueryDefinitionException(MqCode.MQ1804, value + ": " + ARBITER + " compares a root column "
                    + "with a plain value; an Expr.value or Expr.constant binds or renders where PostgreSQL cannot "
                    + "match it against the index's predicate");
        }
    }

    private static void requireRootColumn(TableField<?, ?> root, SelectField<?, ?> field, String what) {
        if (field instanceof ExpressionField<?, ?> expression) {
            expression.columns().forEach(column -> requireRootColumn(root, column, what));
        } else if (field instanceof ColumnField<?, ?, ?> column && !column.table().key().equals(root.key())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1804, column + ": " + what + " reads the "
                    + column.table().describe() + "; the conflict action has no join, so it filters on root columns");
        }
    }
}
