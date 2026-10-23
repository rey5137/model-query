package com.rey.modelquery.core;

import java.util.List;

/** Build-time separation of a source's defining constants and its read-side Rows constants (R-SRC-17). */
final class SourceVocabulary {

    private final Class<?> model;
    private final TableField<?, ?> root;
    private final SourceQuery<?> source;
    private final Class<?> readModel;

    SourceVocabulary(Class<?> model, TableField<?, ?> root, SourceQuery<?> source) {
        this(model, root, source, null);
    }

    SourceVocabulary(Class<?> model, TableField<?, ?> root, SourceQuery<?> source, Class<?> readModel) {
        this.model = model;
        this.root = root;
        this.source = source;
        this.readModel = readModel;
    }

    void field(SelectField<?, ?> field) {
        if (source != null && source.fields().fields().contains(field)) {
            reject(SourceQuery.propertyOf(field).orElse(field.name()));
        }
        if (field instanceof ColumnField<?, ?, ?> column) {
            column(column, false);
        } else if (field instanceof ExpressionField<?, ?> expression) {
            expression.columns().forEach(column -> column(column, false));
        } else if (field instanceof AggregateField<?, ?> aggregate) {
            aggregate.checkSourceVocabulary(this);
        } else if (field instanceof WindowField<?, ?> window) {
            window.checkSourceVocabulary(this);
        }
    }

    void filters(List<Filter> filters) {
        ConditionGroup.conditions(filters).forEach(condition -> condition(condition, false));
    }

    void condition(Condition condition) {
        condition(condition, false);
    }

    private void condition(Condition condition, boolean liftedOnly) {
        condition.column().ifPresent(field -> operand(field, liftedOnly));
        condition.right().ifPresent(field -> operand(field, liftedOnly));
        condition.operands().forEach(field -> operand(field, liftedOnly));
        boolean inner = liftedOnly || condition.subSelect().isPresent();
        condition.children().forEach(child -> condition(child, inner));
        condition.subSelect().ifPresent(sub -> sub.conditions().forEach(child -> condition(child, true)));
    }

    private void operand(SelectField<?, ?> field, boolean liftedOnly) {
        if (!liftedOnly) {
            field(field);
        } else if (field instanceof ColumnField<?, ?, ?> column) {
            column(column, true);
        } else if (field instanceof ExpressionField<?, ?> expression) {
            expression.columns().forEach(column -> column(column, true));
        }
    }

    private void column(ColumnField<?, ?, ?> column, boolean liftedOnly) {
        if (liftedOnly && !column.isLifted()) {
            return;
        }
        boolean rows = column.table().isSource() && column.table().rootEntity().equals(model);
        if (source != null ? column.model().equals(model) && !rows : rows && !column.table().key().equals(root.key())) {
            reject(column.property().orElseGet(() -> property(column.name())));
        }
    }

    /** Aggregates retain their source key even when their operand is an unconverted column or a counted table. */
    void table(JoinKey key, String attribute) {
        if (key == null) {
            return;
        }
        while (key.parent() != null) {
            key = key.parent();
        }
        if (definitionRoot(key) && !key.equals(root.key())) {
            reject(property(attribute));
        }
    }

    private boolean definitionRoot(JoinKey key) {
        if (source == null) {
            return key.equals(JoinKey.root(model));
        }
        if (source.root() != null) {
            return key.equals(source.root().key());
        }
        return key.equals(JoinKey.root(model))
                || SourceQuery.branches(source).stream().anyMatch(branch -> key.equals(branch.root().key()));
    }

    private static String property(String name) {
        return name.startsWith("mq_") ? name.substring(3) : name;
    }

    private void reject(String property) {
        var constant = new StringBuilder();
        for (int i = 0; i < property.length(); i++) {
            char c = property.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char before = property.charAt(i - 1);
                boolean endsAcronym = Character.isUpperCase(before) && i + 1 < property.length()
                        && Character.isLowerCase(property.charAt(i + 1));
                if (Character.isLowerCase(before) || Character.isDigit(before) || endsAcronym) {
                    constant.append('_');
                }
            }
            constant.append(Character.toUpperCase(c));
        }
        String replacement = "Q" + model.getSimpleName() + "." + constant;
        if (source != null) {
            replacement = readModel == null || readModel.equals(model)
                    ? "Q" + model.getSimpleName() + ".Rows." + constant
                    : "Q" + readModel.getSimpleName() + "." + constant;
            if (readModel == null) {
                replacement += " for a direct read, or the outer query model's " + constant + " constant";
            }
        }
        throw new ModelQueryDefinitionException(MqCode.MQ1905, model.getSimpleName() + "." + property
                + ": a " + (source == null ? "source definition takes its own constants, not Rows constants"
                        : "query over a source takes its read model's constants, not the source definition's constants")
                + "; use " + replacement);
    }
}
