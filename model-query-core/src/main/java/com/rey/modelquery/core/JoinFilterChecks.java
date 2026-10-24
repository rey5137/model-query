package com.rey.modelquery.core;

import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.criteria.JoinType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Definition-time checks of request-bound ON terms and the sub-queries that could bypass them (R-COL-39, D-194). */
final class JoinFilterChecks {

    record Binding(List<Filter> terms, JoinKind kind) {}

    private final Set<JoinKey> keys;
    private final TableField<?, ?> filtered;
    private boolean subquery;

    JoinFilterChecks(Map<TableField<?, ?>, Binding> filters, List<? extends EntityJoin<?, ?>> entities) {
        keys = filters.keySet().stream().map(TableField::key).collect(Collectors.toUnmodifiableSet());
        filtered = null;
        filters.forEach((table, binding) -> {
            JoinKey key = table.key();
            boolean left;
            if (key.entity()) {
                var bound = entities.stream().filter(join -> join.root().key().equals(key)).toList();
                if (bound.isEmpty()) {
                    throw refused(table.describe() + ": joinFilter(...) names an entity join the query does not bind");
                }
                left = bound.stream().allMatch(join -> join.kind() == JoinKind.LEFT);
            } else {
                left = key.parent() != null && key.type() == JoinType.LEFT;
            }
            if (!left || binding.kind() == JoinKind.INNER) {
                throw refused(table.describe() + ": joinFilter(...) requires a LEFT join, not a root or INNER join");
            }
            var on = new JoinFilterChecks(keys, table);
            ConditionGroup.conditions(binding.terms()).forEach(on::condition);
        });
    }

    private JoinFilterChecks(Set<JoinKey> keys, TableField<?, ?> filtered) {
        this.keys = keys;
        this.filtered = filtered;
    }

    void field(SelectField<?, ?> field) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            table(column.liftedFrom().orElse(column).table().key(), column.liftedFrom().isPresent());
        } else if (field instanceof ExpressionField<?, ?> expression) {
            expression.checkJoinFilters(this);
        } else if (field instanceof AggregateField<?, ?> aggregate) {
            if (filtered != null) {
                throw refused(aggregate + ": an aggregate cannot be a join filter value");
            }
            aggregate.checkJoinFilters(this);
        } else if (field instanceof WindowField<?, ?> window) {
            if (filtered != null) {
                throw refused(window + ": a window cannot be a join filter value");
            }
            window.readFields().forEach(this::field);
        }
    }

    void table(JoinKey key) {
        table(key, false);
    }

    /** A {@code lifted} outer column reuses the outer statement's join, so it crosses nothing (R-FLT-27). */
    private void table(JoinKey key, boolean lifted) {
        if (filtered != null && !filtered.key().equals(key)) {
            throw refused(filtered.describe() + ": joinFilter(...) may read only that join's own columns; "
                    + "another table or a path below it is not allowed");
        }
        if (subquery && !lifted) {
            for (JoinKey path = key; path != null; path = path.parent()) {
                if (keys.contains(path)) {
                    throw refused("A sub-query crosses a joinFilter(...) join; its ON condition cannot be preserved");
                }
            }
        }
    }

    void condition(Condition condition) {
        if (filtered != null && (condition.path().isPresent() || condition.subSelect().isPresent()
                || condition.kind() == Condition.Kind.CUSTOM)) {
            throw refused(filtered.describe() + ": joinFilter(...) takes columns, values and constants only; "
                    + "exists, sub-queries and custom predicates are not allowed");
        }
        condition.column().ifPresent(this::field);
        condition.right().ifPresent(this::field);
        condition.operands().forEach(this::field);
        if (condition.path().isPresent() || condition.subSelect().isPresent()) {
            subquery(() -> {
                condition.path().ifPresent(path -> table(path.key()));
                condition.subSelect().ifPresent(this::subSelect);
                condition.children().forEach(this::condition);
            });
        } else {
            condition.children().forEach(this::condition);
        }
    }

    void correlated(ExpressionField.CorrelatedValue value) {
        if (filtered != null) {
            throw refused(filtered.describe() + ": a correlated value cannot be a join filter value");
        }
        subquery(() -> {
            subSelect(value.rows());
            value.conditions().forEach(this::condition);
            value.orders().forEach(order -> field(order.column()));
        });
    }

    private void subSelect(SubSelect<?, ?> rows) {
        field(rows.column());
        rows.conditions().forEach(this::condition);
    }

    private void subquery(Runnable action) {
        boolean previous = subquery;
        subquery = true;
        try {
            action.run();
        } finally {
            subquery = previous;
        }
    }

    static ModelQueryDefinitionException refused(String message) {
        return new ModelQueryDefinitionException(MqCode.MQ1912, message);
    }
}
