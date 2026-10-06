package com.rey.modelquery.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The {@code build()} checks the insert definitions share (R-WRT-27, R-WRT-29, R-WRT-34, D-117). */
final class InsertRules {

    private InsertRules() {
    }

    /**
     * {@code table}, checked to be a root.
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code table} is a join
     */
    static <T> TableField<T, T> requireRoot(TableField<T, T> table, String method) {
        Objects.requireNonNull(table, "root");
        if (table.rootEntity() == null) {
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
     * Checks the {@code set} constants of an insert writing {@code columns}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} for a constant on a column the model has, a column set
     *     twice, or a column not on the root
     */
    static void checkConstants(InsertColumns<?, ?> columns, List<Assignment<?, ?>> constants) {
        Set<String> set = new HashSet<>();
        for (Assignment<?, ?> constant : constants) {
            ColumnField<?, ?, ?> column = constant.column();
            requireOnRoot(columns.root(), column, "set(...)");
            if (columns.indexOf(column) >= 0) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, column + ": set(...) on a column the insert "
                        + "model already writes; each row's value is written");
            }
            if (!set.add(column.name())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, column + ": set(...) twice");
            }
        }
    }

    /**
     * Checks a conflict update's {@code where}: the conflict action has no join context, so it reads root columns
     * only, with no {@code exists} and no sub-select (R-WRT-34).
     *
     * @throws ModelQueryDefinitionException {@code MQ1804} for a joined column, an {@code exists} or a sub-select
     */
    static void checkConflictWhere(TableField<?, ?> root, List<Condition> conditions) {
        for (Condition condition : conditions) {
            switch (condition.kind()) {
                case EXISTS, NOT_EXISTS, IN_SUBSELECT, NOT_IN_SUBSELECT, EXISTS_SUBSELECT, NOT_EXISTS_SUBSELECT ->
                    throw new ModelQueryDefinitionException(MqCode.MQ1804, "doUpdate(...).where(...) with "
                            + condition.kind() + ": the conflict action filters the stored row on its own columns, "
                            + "with no join or sub-query");
                default -> {
                    condition.column().ifPresent(column -> requireRootColumn(root, column));
                    condition.right().ifPresent(column -> requireRootColumn(root, column));
                    checkConflictWhere(root, condition.children());
                }
            }
        }
    }

    private static void requireRootColumn(TableField<?, ?> root, SelectField<?, ?> field) {
        if (field instanceof ExpressionField<?, ?> expression) {
            expression.columns().forEach(column -> requireRootColumn(root, column));
        } else if (field instanceof ColumnField<?, ?, ?> column && !column.table().key().equals(root.key())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1804, column + ": doUpdate(...).where(...) reads the "
                    + column.table().describe() + "; the conflict action has no join, so it filters on root columns");
        }
    }
}
