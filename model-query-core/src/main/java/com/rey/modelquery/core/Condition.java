package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One filter a {@link ModelQuery} records, as its {@code where} or {@code having} operator was called: the kind, the
 * column or path, the values as passed (before any converter, and not lower-cased), and the operands of a group.
 * Only the DSL builds one (D-101). Immutable; values are held by reference.
 *
 * <p>Two conditions are equal when built from equal calls: columns compare as {@link ColumnField} and
 * {@link AggregateField} do, paths by their join key, and {@code CUSTOM} conditions by label alone. {@link #toString()}
 * shows each value as {@code ?}, since a bound value can be personal data; only {@link #values()} exposes them.
 *
 * @implSpec api/16 §1, R-INS-01, R-INS-04, R-INS-05, D-101
 */
@Incubating
public final class Condition {

    /**
     * What a condition records. It may gain constants in a later release, so a {@code switch} on it needs a
     * {@code default}.
     *
     * @implSpec api/16 §1
     */
    @Incubating
    public enum Kind {
        /** {@code eq}: {@code column} and one value. */
        EQ,
        /** {@code ne}: {@code column} and one value. */
        NE,
        /** {@code gt}: {@code column} and one value. */
        GT,
        /** {@code gte}, or a {@code range} or {@code between} with only its lower bound: {@code column}, one value. */
        GTE,
        /** {@code lt}, or a {@code range} with only its upper bound: {@code column} and one value. */
        LT,
        /** {@code lte}, or a {@code between} with only its upper bound: {@code column} and one value. */
        LTE,
        /** {@code range} with both bounds: {@code column} and {@code [from, to]}. */
        RANGE,
        /** {@code between} with both bounds: {@code column} and {@code [from, to]}. */
        BETWEEN,
        /** {@code in}: {@code column} and the values, none for an empty collection. */
        IN,
        /** {@code notIn}: {@code column} and the values, none for an empty collection. */
        NOT_IN,
        /** {@code like}: {@code column}, one value and {@code likeMode}. */
        LIKE,
        /** {@code likeIgnoreCase}: {@code column}, one value as passed and {@code likeMode}. */
        LIKE_IGNORE_CASE,
        /** {@code eqIgnoreCase}: {@code column} and one value as passed. */
        EQ_IGNORE_CASE,
        /** {@code isNull}: {@code column}. */
        IS_NULL,
        /** {@code isNotNull}: {@code column}. */
        IS_NOT_NULL,
        /** {@code compare(left, op, right)}: {@code column}, {@code op} and {@code right}. */
        COMPARE,
        /** An {@code or} branch of two or more filters: {@code children}. */
        AND,
        /** {@code or}: {@code children}, one per branch. */
        OR,
        /** {@code not}: {@code children}, ANDed. */
        NOT,
        /** {@code exists}: {@code path} and {@code children}, ANDed. */
        EXISTS,
        /** {@code notExists}: {@code path} and {@code children}, ANDed. */
        NOT_EXISTS,
        /** {@code add}: {@code label} when given; nothing else about the predicate is recorded. */
        CUSTOM
    }

    private final Kind kind;
    private final SelectField<?, ?> column;
    private final SelectField<?, ?> right;
    private final Op op;
    private final LikeMode likeMode;
    private final TableField<?, ?> path;
    private final List<Object> values;
    private final List<Condition> children;
    private final String label;

    private Condition(Kind kind, SelectField<?, ?> column, SelectField<?, ?> right, Op op, LikeMode likeMode,
            TableField<?, ?> path, List<?> values, List<Condition> children, String label) {
        this.kind = kind;
        this.column = column;
        this.right = right;
        this.op = op;
        this.likeMode = likeMode;
        this.path = path;
        this.values = List.copyOf(values);
        this.children = List.copyOf(children);
        this.label = label;
    }

    /** A condition on {@code column} with {@code values}, as passed: a comparison, a range, a set or a null test. */
    static Condition of(Kind kind, SelectField<?, ?> column, List<?> values) {
        return new Condition(kind, column, null, null, null, null, values, List.of(), null);
    }

    /** {@code LIKE} or {@code LIKE_IGNORE_CASE} on {@code column}, with {@code value} as passed. */
    static Condition like(Kind kind, SelectField<?, ?> column, String value, LikeMode mode) {
        return new Condition(kind, column, null, null, mode, null, List.of(value), List.of(), null);
    }

    /** {@code COMPARE} of {@code left} with {@code right}. */
    static Condition compare(SelectField<?, ?> left, Op op, SelectField<?, ?> right) {
        return new Condition(Kind.COMPARE, left, right, op, null, null, List.of(), List.of(), null);
    }

    /** {@code AND}, {@code OR} or {@code NOT} over {@code children}. */
    static Condition group(Kind kind, List<Condition> children) {
        return new Condition(kind, null, null, null, null, null, List.of(), children, null);
    }

    /** {@code EXISTS} or {@code NOT_EXISTS} a row of {@code path} meeting {@code children}. */
    static Condition exists(Kind kind, TableField<?, ?> path, List<Condition> children) {
        return new Condition(kind, null, null, null, null, path, List.of(), children, null);
    }

    /** {@code CUSTOM}, labelled with {@code label}, or {@code null} for none. */
    static Condition custom(String label) {
        return new Condition(Kind.CUSTOM, null, null, null, null, null, List.of(), List.of(), label);
    }

    /** What this condition records. */
    public Kind kind() {
        return kind;
    }

    /** The column filtered on, or the left column of a {@code COMPARE}; empty for a group and {@code CUSTOM}. */
    public Optional<SelectField<?, ?>> column() {
        return Optional.ofNullable(column);
    }

    /** The right column of a {@code COMPARE}. */
    public Optional<SelectField<?, ?>> right() {
        return Optional.ofNullable(right);
    }

    /** The operator of a {@code COMPARE}. */
    public Optional<Op> op() {
        return Optional.ofNullable(op);
    }

    /** The mode of a {@code LIKE} or {@code LIKE_IGNORE_CASE}. */
    public Optional<LikeMode> likeMode() {
        return Optional.ofNullable(likeMode);
    }

    /** The join path of an {@code EXISTS} or {@code NOT_EXISTS}. */
    public Optional<TableField<?, ?>> path() {
        return Optional.ofNullable(path);
    }

    /** The values as passed, before any converter, in order, as a list that throws on mutation. */
    public List<Object> values() {
        return values;
    }

    /** The operands of an {@code AND}, {@code OR}, {@code NOT}, {@code EXISTS} or {@code NOT_EXISTS}, in order. */
    public List<Condition> children() {
        return children;
    }

    /** The label of a labelled {@code CUSTOM}. */
    public Optional<String> label() {
        return Optional.ofNullable(label);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Condition other) || kind != other.kind) {
            return false;
        }
        if (kind == Kind.CUSTOM) {
            return Objects.equals(label, other.label); // a custom predicate is a lambda, which cannot be compared
        }
        return Objects.equals(column, other.column)
                && Objects.equals(right, other.right)
                && op == other.op
                && likeMode == other.likeMode
                && Objects.equals(pathKey(), other.pathKey())
                && values.equals(other.values)
                && children.equals(other.children)
                && Objects.equals(label, other.label);
    }

    @Override
    public int hashCode() {
        // Computed per call: a mutable value changed after the call changes the view (R-INS-04).
        return kind == Kind.CUSTOM
                ? Objects.hash(kind, label)
                : Objects.hash(kind, column, right, op, likeMode, pathKey(), values, children, label);
    }

    /** {@code path}'s identity: a {@code TableField} compares by its join key, not by instance (CC-IMM-04). */
    private JoinKey pathKey() {
        return path == null ? null : path.key();
    }

    /** The kind, column, path, label and operands, each value shown as {@code ?}; the format is not API. */
    @Override
    public String toString() {
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
            parts.add(path.describe());
        }
        if (kind == Kind.IN || kind == Kind.NOT_IN) {
            parts.add(values.isEmpty() ? "no values" : "? x " + values.size());
        } else {
            values.forEach(value -> parts.add("?"));
        }
        if (likeMode != null) {
            parts.add(likeMode.name());
        }
        if (label != null) {
            parts.add('"' + label + '"');
        }
        children.forEach(child -> parts.add(child.toString()));
        return kind + "(" + String.join(", ", parts) + ")";
    }
}
