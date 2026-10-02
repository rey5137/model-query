package com.rey.modelquery.test;

import com.rey.modelquery.core.Condition;
import com.rey.modelquery.core.Condition.Kind;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.TableField;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The text of a condition or of a matcher, in one format so a failure message shows an expectation and an actual
 * condition the same way. Unlike {@code Condition.toString()}, values are shown: a test's data is not personal data.
 */
final class ConditionText {

    private ConditionText() {}

    /** One condition and its operands on one line. */
    static String describe(Condition condition) {
        return format(condition.kind(), condition.column().orElse(null), condition.op().orElse(null),
                condition.right().orElse(null), condition.path().orElse(null), condition.values(),
                condition.likeMode().orElse(null), condition.label().orElse(null),
                condition.children().stream().map(ConditionText::describe).toList());
    }

    /** One condition per line, its operands indented below it. */
    static void tree(Condition condition, int depth, StringBuilder out) {
        out.append("  ".repeat(depth))
                .append(format(condition.kind(), condition.column().orElse(null), condition.op().orElse(null),
                        condition.right().orElse(null), condition.path().orElse(null), condition.values(),
                        condition.likeMode().orElse(null), condition.label().orElse(null), List.of()))
                .append('\n');
        condition.children().forEach(child -> tree(child, depth + 1, out));
    }

    static String format(Kind kind, SelectField<?, ?> column, Op op, SelectField<?, ?> right, TableField<?, ?> path,
            List<?> values, LikeMode likeMode, String label, List<String> children) {
        var parts = new ArrayList<String>();
        if (column != null) {
            parts.add(column.toString());
        }
        if (op != null) {
            parts.add(op.name());
        }
        if (right != null) {
            parts.add(right.toString());
        }
        if (path != null) {
            parts.add(path.toString());
        }
        if (kind == Kind.IN || kind == Kind.NOT_IN) {
            parts.add(values.stream().map(ConditionText::value).collect(Collectors.joining(", ", "[", "]")));
        } else {
            values.forEach(value -> parts.add(value(value)));
        }
        if (likeMode != null) {
            parts.add(likeMode.name());
        }
        if (label != null) {
            parts.add('"' + label + '"');
        }
        parts.addAll(children);
        return kind + "(" + String.join(", ", parts) + ")";
    }

    private static String value(Object value) {
        return value instanceof CharSequence ? '"' + value.toString() + '"' : String.valueOf(value);
    }
}
