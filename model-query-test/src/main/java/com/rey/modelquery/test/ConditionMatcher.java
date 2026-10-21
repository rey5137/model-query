package com.rey.modelquery.test;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Condition;
import com.rey.modelquery.core.Condition.Kind;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.ScalarField;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Matches one {@link Condition} of a query, built by {@link FilterMatchers}; nothing else builds one, and core exposes
 * no way to build a {@code Condition} to compare against (D-101). {@link #toString()} is the expectation, in the
 * format a failure message shows a condition.
 *
 * @implSpec api/16 R-INS-07
 */
@Incubating
public final class ConditionMatcher {

    private final Kind kind;
    private final SelectField<?, ?> column;
    private final SelectField<?, ?> right;
    private final Op op;
    private final LikeMode likeMode;
    private final TableField<?, ?> path;
    private final List<Object> values;
    private final String label;
    private final List<ConditionMatcher> children;
    private final SubSelect<?, ?> subSelect;
    private final List<ScalarField<?, ?>> operands;
    /** An {@code OPERATOR}'s operator, matched by identity as the condition compares it (R-INS-13). */
    private final FilterOperator<?, ?> operator;

    ConditionMatcher(Kind kind, SelectField<?, ?> column, SelectField<?, ?> right, Op op, LikeMode likeMode,
            TableField<?, ?> path, List<?> values, String label, List<ConditionMatcher> children) {
        this(kind, column, right, op, likeMode, path, values, label, children, null);
    }

    ConditionMatcher(Kind kind, SelectField<?, ?> column, SelectField<?, ?> right, Op op, LikeMode likeMode,
            TableField<?, ?> path, List<?> values, String label, List<ConditionMatcher> children,
            SubSelect<?, ?> subSelect) {
        this(kind, column, right, op, likeMode, path, values, label, children, subSelect, List.of());
    }

    ConditionMatcher(Kind kind, SelectField<?, ?> column, SelectField<?, ?> right, Op op, LikeMode likeMode,
            TableField<?, ?> path, List<?> values, String label, List<ConditionMatcher> children,
            SubSelect<?, ?> subSelect, List<? extends ScalarField<?, ?>> operands) {
        this(kind, column, right, op, likeMode, path, values, label, children, subSelect, operands, null);
    }

    ConditionMatcher(Kind kind, SelectField<?, ?> column, SelectField<?, ?> right, Op op, LikeMode likeMode,
            TableField<?, ?> path, List<?> values, String label, List<ConditionMatcher> children,
            SubSelect<?, ?> subSelect, List<? extends ScalarField<?, ?>> operands, FilterOperator<?, ?> operator) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.column = column;
        this.right = right;
        this.op = op;
        this.likeMode = likeMode;
        this.path = path;
        this.values = new ArrayList<>(values);
        this.label = label;
        this.children = List.copyOf(children);
        this.subSelect = subSelect;
        this.operands = List.copyOf(operands);
        this.operator = operator;
    }

    /** Whether {@code condition} is the one this matcher describes, with every operand matching in order. */
    public boolean matches(Condition condition) {
        if (condition.kind() != kind
                || !Objects.equals(condition.column().orElse(null), column)
                || !Objects.equals(condition.right().orElse(null), right)
                || condition.op().orElse(null) != op
                || condition.operator().orElse(null) != operator
                || condition.likeMode().orElse(null) != likeMode
                || !Objects.equals(condition.path().orElse(null), path)
                || !Objects.equals(condition.label().orElse(null), label)
                || !Objects.equals(condition.subSelect().orElse(null), subSelect)
                || !operandsMatch(condition.operands())
                || !valuesMatch(condition.values())
                || condition.children().size() != children.size()) {
            return false;
        }
        for (int i = 0; i < children.size(); i++) {
            if (!children.get(i).matches(condition.children().get(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code IN_FIELDS} and {@code NOT_IN_FIELDS} operands match as a multiset, as the values of {@code IN} do. */
    private boolean operandsMatch(List<ScalarField<?, ?>> actual) {
        if (operands.size() != actual.size()) {
            return false;
        }
        var remaining = new ArrayList<>(actual);
        return operands.stream().allMatch(remaining::remove);
    }

    /**
     * {@code IN} and {@code NOT_IN} values match as multisets, every other kind's in order; an {@code OPERATOR} value
     * may be an array, matched by content as {@code Condition.equals} does.
     */
    private boolean valuesMatch(List<Object> actual) {
        if (kind == Kind.OPERATOR) {
            return Arrays.deepEquals(values.toArray(), actual.toArray());
        }
        if (kind != Kind.IN && kind != Kind.NOT_IN) {
            return values.equals(actual);
        }
        if (values.size() != actual.size()) {
            return false;
        }
        var remaining = new ArrayList<>(actual);
        return values.stream().allMatch(remaining::remove);
    }

    @Override
    public String toString() {
        return ConditionText.format(kind, column, op, operator, right, path, values, likeMode, label, subSelect,
                operands, children.stream().map(ConditionMatcher::toString).toList());
    }
}
