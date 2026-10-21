package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One filter a {@link ModelQuery} records, as its {@code where} or {@code having} operator was called: the kind, the
 * column or path, the values as passed (before any converter, and not lower-cased), and the operands of a group.
 * Only the DSL builds one (D-101). Immutable; values are held by reference.
 *
 * <p>Two conditions are equal when built from equal calls: columns compare as {@link ColumnField} and
 * {@link AggregateField} do, paths by their join key, {@code CUSTOM} conditions by label alone and an
 * {@code OPERATOR}'s operator by identity. {@link #toString()} shows each value as {@code ?}, since a bound value
 * can be personal data; only {@link #values()} exposes them.
 *
 * @implSpec api/16 §1, R-INS-01, R-INS-04, R-INS-05, R-INS-13, D-101
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
        /** {@code in} against operand fields: {@code column} and {@code operands}, never {@code values}. */
        @Incubating
        IN_FIELDS,
        /** {@code notIn} against operand fields: {@code column} and {@code operands}, never {@code values}. */
        @Incubating
        NOT_IN_FIELDS,
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
        /** {@code in} over a sub-select: {@code column} and {@code subSelect}, never {@code values}. */
        IN_SUBSELECT,
        /** {@code notIn} over a sub-select: {@code column} and {@code subSelect}, never {@code values}. */
        NOT_IN_SUBSELECT,
        /** {@code exists} over a sub-select: {@code subSelect} and the correlation as {@code children}. */
        EXISTS_SUBSELECT,
        /** {@code notExists} over a sub-select: {@code subSelect} and the correlation as {@code children}. */
        NOT_EXISTS_SUBSELECT,
        /** {@code add}: {@code label} when given; nothing else about the predicate is recorded. */
        CUSTOM,
        /**
         * {@code ofType}: {@code column}, the node's {@code Expr.typeOf}, and the types as passed, none for an empty
         * collection (R-FLT-28).
         */
        @Incubating
        OF_TYPE,
        /**
         * {@code notOfType}: {@code column}, the node's {@code Expr.typeOf}, and the types as passed, none for an
         * empty collection (R-FLT-28).
         */
        @Incubating
        NOT_OF_TYPE,
        /**
         * {@code op}: {@code column}, {@code operator}, and one value as passed (before {@code prepare} and any
         * converter) or the {@code right} operand (R-INS-13).
         */
        @Incubating
        OPERATOR
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
    private final SubSelect<?, ?> subSelect;
    private final List<ScalarField<?, ?>> operands;
    /** The operator of an {@code OPERATOR}, compared by identity (R-INS-13). */
    private final FilterOperator<?, ?> operator;

    private Condition(Kind kind, SelectField<?, ?> column, SelectField<?, ?> right, Op op, LikeMode likeMode,
            TableField<?, ?> path, List<?> values, List<Condition> children, String label, SubSelect<?, ?> subSelect,
            List<? extends ScalarField<?, ?>> operands) {
        this(kind, column, right, op, likeMode, path, values, children, label, subSelect, operands, null);
    }

    private Condition(Kind kind, SelectField<?, ?> column, SelectField<?, ?> right, Op op, LikeMode likeMode,
            TableField<?, ?> path, List<?> values, List<Condition> children, String label, SubSelect<?, ?> subSelect,
            List<? extends ScalarField<?, ?>> operands, FilterOperator<?, ?> operator) {
        this.kind = kind;
        this.column = column;
        this.right = right;
        this.op = op;
        this.likeMode = likeMode;
        this.path = path;
        this.values = List.copyOf(values);
        this.children = List.copyOf(children);
        this.label = label;
        this.subSelect = subSelect;
        this.operands = List.copyOf(operands);
        this.operator = operator;
    }

    /** A condition on {@code column} with {@code values}, as passed: a comparison, a range, a set or a null test. */
    static Condition of(Kind kind, SelectField<?, ?> column, List<?> values) {
        return new Condition(kind, column, null, null, null, null, values, List.of(), null, null, List.of());
    }

    /** {@code LIKE} or {@code LIKE_IGNORE_CASE} on {@code column}, with {@code value} as passed. */
    static Condition like(Kind kind, SelectField<?, ?> column, String value, LikeMode mode) {
        return new Condition(kind, column, null, null, mode, null, List.of(value), List.of(), null, null, List.of());
    }

    /** {@code COMPARE} of {@code left} with {@code right}. */
    static Condition compare(SelectField<?, ?> left, Op op, SelectField<?, ?> right) {
        return new Condition(Kind.COMPARE, left, right, op, null, null, List.of(), List.of(), null, null, List.of());
    }

    /** {@code AND}, {@code OR} or {@code NOT} over {@code children}. */
    static Condition group(Kind kind, List<Condition> children) {
        return new Condition(kind, null, null, null, null, null, List.of(), children, null, null, List.of());
    }

    /** {@code EXISTS} or {@code NOT_EXISTS} a row of {@code path} meeting {@code children}. */
    static Condition exists(Kind kind, TableField<?, ?> path, List<Condition> children) {
        return new Condition(kind, null, null, null, null, path, List.of(), children, null, null, List.of());
    }

    /** {@code IN_SUBSELECT} or {@code NOT_IN_SUBSELECT} over {@code sub}. */
    static Condition inSubSelect(Kind kind, SelectField<?, ?> column, SubSelect<?, ?> sub) {
        return new Condition(kind, column, null, null, null, null, List.of(), List.of(), null,
                Objects.requireNonNull(sub, "sub"), List.of());
    }

    /** {@code EXISTS_SUBSELECT} or {@code NOT_EXISTS_SUBSELECT} over {@code sub}, with {@code children}. */
    static Condition existsSubSelect(Kind kind, SubSelect<?, ?> sub, List<Condition> children) {
        return new Condition(kind, null, null, null, null, null, List.of(), children, null,
                Objects.requireNonNull(sub, "sub"), List.of());
    }

    /** {@code IN_FIELDS} or {@code NOT_IN_FIELDS} of {@code column} against {@code operands}, which bind nothing. */
    static Condition inFields(Kind kind, SelectField<?, ?> column, List<? extends ScalarField<?, ?>> operands) {
        return new Condition(kind, column, null, null, null, null, List.of(), List.of(), null, null, operands);
    }

    /** {@code OPERATOR} on {@code column} with {@code value} as passed, unprepared and unconverted (R-INS-13). */
    static Condition operator(FilterOperator<?, ?> operator, SelectField<?, ?> column, Object value) {
        return new Condition(Kind.OPERATOR, column, null, null, null, null, List.of(value), List.of(), null, null,
                List.of(), Objects.requireNonNull(operator, "operator"));
    }

    /** {@code OPERATOR} of {@code left} against the field {@code right} (R-INS-13). */
    static Condition operator(FilterOperator<?, ?> operator, SelectField<?, ?> left, SelectField<?, ?> right) {
        return new Condition(Kind.OPERATOR, left, right, null, null, null, List.of(), List.of(), null, null,
                List.of(), Objects.requireNonNull(operator, "operator"));
    }

    /** {@code CUSTOM}, labelled with {@code label}, or {@code null} for none. */
    static Condition custom(String label) {
        return new Condition(Kind.CUSTOM, null, null, null, null, null, List.of(), List.of(), label, null, List.of());
    }

    /** What this condition records. */
    public Kind kind() {
        return kind;
    }

    /**
     * The column filtered on, or the left column of a {@code COMPARE} or a field-form {@code OPERATOR}; empty for a
     * group and {@code CUSTOM}.
     */
    public Optional<SelectField<?, ?>> column() {
        return Optional.ofNullable(column);
    }

    /** The right column of a {@code COMPARE}, or the right operand of a field-form {@code OPERATOR}. */
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

    /**
     * The values as passed, before any converter, in order, as a list that throws on mutation; an {@code OPERATOR}'s
     * array value is a (shallow) copy, so the recorded one cannot be changed through it (INV-9).
     */
    public List<Object> values() {
        return kind == Kind.OPERATOR && values.size() == 1 && values.get(0) != null && values.get(0).getClass().isArray()
                ? List.of(copyOfArray(values.get(0))) : values;
    }

    /**
     * {@code value}, or a copy of it when it is an array: one level, so an element that is itself an array or an
     * object is shared, as INV-9 states for inserts (R-INS-13).
     */
    static Object copyOfArray(Object value) {
        if (value == null || !value.getClass().isArray()) {
            return value;
        }
        if (value instanceof Object[] array) {
            return array.clone();
        }
        int length = java.lang.reflect.Array.getLength(value);
        Object copy = java.lang.reflect.Array.newInstance(value.getClass().getComponentType(), length);
        System.arraycopy(value, 0, copy, 0, length);
        return copy;
    }

    /** The operand fields of an {@code IN_FIELDS} or {@code NOT_IN_FIELDS}, in order; empty for any other kind. */
    @Incubating
    public List<ScalarField<?, ?>> operands() {
        return operands;
    }

    /** The operands of an {@code AND}, {@code OR}, {@code NOT}, {@code EXISTS} or {@code NOT_EXISTS}, in order. */
    public List<Condition> children() {
        return children;
    }

    /** The label of a labelled {@code CUSTOM}. */
    public Optional<String> label() {
        return Optional.ofNullable(label);
    }

    /** The sub-select of one of the four {@code *_SUBSELECT} kinds (R-INS-08). */
    @Incubating
    public Optional<SubSelect<?, ?>> subSelect() {
        return Optional.ofNullable(subSelect);
    }

    /** The operator of an {@code OPERATOR}; empty for every other kind (R-INS-13). */
    @Incubating
    public Optional<FilterOperator<?, ?>> operator() {
        return Optional.ofNullable(operator);
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
                && valuesEqual(other)
                && children.equals(other.children)
                && Objects.equals(label, other.label)
                && Objects.equals(subSelect, other.subSelect)
                && operands.equals(other.operands)
                && operator == other.operator; // identity: two operators of one name differ (R-INS-13)
    }

    @Override
    public int hashCode() {
        // Computed per call: a mutable value changed after the call changes the view (R-INS-04).
        return kind == Kind.CUSTOM
                ? Objects.hash(kind, label)
                : Objects.hash(kind, column, right, op, likeMode, pathKey(), valuesHash(), children, label, subSelect,
                        operands, System.identityHashCode(operator));
    }

    /** An {@code OPERATOR} value may be an array (a {@code String[]} for an array operator), compared by content. */
    private boolean valuesEqual(Condition other) {
        return kind == Kind.OPERATOR
                ? Arrays.deepEquals(values.toArray(), other.values.toArray())
                : values.equals(other.values);
    }

    private int valuesHash() {
        return kind == Kind.OPERATOR ? Arrays.deepHashCode(values.toArray()) : values.hashCode();
    }

    /** {@code path}'s identity: a {@code TableField} compares by its join key, not by instance (CC-IMM-04). */
    private JoinKey pathKey() {
        return path == null ? null : path.key();
    }

    /**
     * The kind, column, operator name, path, label and operands, each value shown as {@code ?}; the format is not API.
     */
    @Override
    public String toString() {
        var parts = new ArrayList<String>();
        if (column != null) {
            parts.add(column.toString());
        }
        if (op != null) {
            parts.add(op.name());
        }
        if (operator != null) {
            parts.add(operator.name());
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
        operands.forEach(operand -> parts.add(operand.toString()));
        if (subSelect != null) {
            parts.add(subSelect.toString());
        }
        children.forEach(child -> parts.add(child.toString()));
        return kind + "(" + String.join(", ", parts) + ")";
    }
}
