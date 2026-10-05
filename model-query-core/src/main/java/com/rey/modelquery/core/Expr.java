package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Factories for {@link ExpressionField}s: a typed value the database computes, each one JPA
 * {@link jakarta.persistence.criteria.CriteriaBuilder} construct (R-COL-17). It mirrors {@link Agg}: it builds the
 * field, and {@code ExpressionField} is the field type.
 *
 * <p>A value given to a factory binds, typed by its operand, and {@link #constant} is SQL definition text (R-COL-18).
 * A column with a {@link ColumnConverter} is {@code MQ1501}; a null, array, {@code Date}, {@code Calendar}, enum or
 * entity value is {@code MQ1502}; a {@code dividedBy} over two integral operands is {@code MQ1503}; a CASE condition
 * with no filter is {@code MQ1504}, and one using {@code add}, {@code exists} or a sub-select is {@code MQ1505}; a
 * function name that is not a plain identifier or is a built-in aggregate is {@code MQ1506} (R-COL-17).
 *
 * @implSpec api/10 §3a, R-COL-17, R-COL-18, D-115
 */
@Incubating
public final class Expr {

    /** A plain SQL identifier: a letter or underscore, then letters, digits or underscores. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** The aggregate function names {@code function(...)} refuses (R-COL-17, api/13). */
    private static final Set<String> AGGREGATES =
            Set.of("count", "countdistinct", "sum", "sumaslong", "avg", "min", "max");
    /** The integral types {@code dividedBy} refuses, whose division truncates on some vendors (R-COL-17). */
    private static final Set<Class<?>> INTEGRAL =
            Set.of(Integer.class, Long.class, Short.class, Byte.class, BigInteger.class);

    private Expr() {}

    /** {@code coalesce(first, second)}: {@code first} when it is not NULL, else {@code second}. */
    public static <M, C> ExpressionField<M, C> coalesce(ScalarField<M, C> first, ScalarField<M, C> second) {
        checkFields(first);
        checkFields(second);
        return new ExpressionField<>(new ExpressionField.Coalesce(first, second, ColumnField.boxed(first.type())), first.type(),
                null);
    }

    /** {@code coalesce(first, fallback)}: {@code first} when it is not NULL, else the bound {@code fallback}. */
    public static <M, C> ExpressionField<M, C> coalesce(ScalarField<M, C> first, C fallback) {
        checkFields(first);
        checkValue(fallback);
        return new ExpressionField<>(new ExpressionField.Coalesce(first, fallback, ColumnField.boxed(first.type())), first.type(),
                null);
    }

    /** {@code nullIf(value, sentinel)}: NULL when {@code value} equals the bound {@code sentinel}, else {@code value}. */
    public static <M, C> ExpressionField<M, C> nullIf(ScalarField<M, C> value, C sentinel) {
        checkFields(value);
        checkValue(sentinel);
        return new ExpressionField<>(new ExpressionField.NullIf(value, sentinel, ColumnField.boxed(value.type())), value.type(),
                null);
    }

    /** {@code a + b}, of the operands' type, which must be one numeric type. */
    public static <M, C extends Number> ExpressionField<M, C> plus(ScalarField<M, C> a, ScalarField<M, C> b) {
        return arithmetic(ExpressionField.ArithKind.SUM, a, b, null);
    }

    /** {@code a + b}, with {@code b} a bound value of the operands' type. */
    public static <M, C extends Number> ExpressionField<M, C> plus(ScalarField<M, C> a, C b) {
        return arithmetic(ExpressionField.ArithKind.SUM, a, b, null);
    }

    /** {@code a + b}, declaring the result type when the operands differ (checked as {@code MQ1507}). */
    public static <M, C extends Number> ExpressionField<M, C> plus(
            ScalarField<M, ? extends Number> a, ScalarField<M, ? extends Number> b, Class<C> type) {
        return arithmetic(ExpressionField.ArithKind.SUM, a, b, type);
    }

    /** {@code a - b}, of the operands' type, which must be one numeric type. */
    public static <M, C extends Number> ExpressionField<M, C> minus(ScalarField<M, C> a, ScalarField<M, C> b) {
        return arithmetic(ExpressionField.ArithKind.DIFF, a, b, null);
    }

    /** {@code a - b}, with {@code b} a bound value of the operands' type. */
    public static <M, C extends Number> ExpressionField<M, C> minus(ScalarField<M, C> a, C b) {
        return arithmetic(ExpressionField.ArithKind.DIFF, a, b, null);
    }

    /** {@code a - b}, declaring the result type when the operands differ (checked as {@code MQ1507}). */
    public static <M, C extends Number> ExpressionField<M, C> minus(
            ScalarField<M, ? extends Number> a, ScalarField<M, ? extends Number> b, Class<C> type) {
        return arithmetic(ExpressionField.ArithKind.DIFF, a, b, type);
    }

    /** {@code a * b}, of the operands' type, which must be one numeric type. */
    public static <M, C extends Number> ExpressionField<M, C> times(ScalarField<M, C> a, ScalarField<M, C> b) {
        return arithmetic(ExpressionField.ArithKind.PROD, a, b, null);
    }

    /** {@code a * b}, with {@code b} a bound value of the operands' type. */
    public static <M, C extends Number> ExpressionField<M, C> times(ScalarField<M, C> a, C b) {
        return arithmetic(ExpressionField.ArithKind.PROD, a, b, null);
    }

    /** {@code a * b}, declaring the result type when the operands differ (checked as {@code MQ1507}). */
    public static <M, C extends Number> ExpressionField<M, C> times(
            ScalarField<M, ? extends Number> a, ScalarField<M, ? extends Number> b, Class<C> type) {
        return arithmetic(ExpressionField.ArithKind.PROD, a, b, type);
    }

    /**
     * {@code a / b}, of the operands' type.
     *
     * @throws ModelQueryDefinitionException {@code MQ1503} when both operands are integral, whose division truncates
     *     on PostgreSQL and H2 and not on MySQL
     */
    public static <M, C extends Number> ExpressionField<M, C> dividedBy(ScalarField<M, C> a, ScalarField<M, C> b) {
        dividedByCheck(a, b);
        return arithmetic(ExpressionField.ArithKind.QUOT, a, b, null);
    }

    /**
     * {@code a / b}, declaring the result type when the operands differ.
     *
     * @throws ModelQueryDefinitionException {@code MQ1503} when both operands are integral
     */
    public static <M, C extends Number> ExpressionField<M, C> dividedBy(
            ScalarField<M, ? extends Number> a, ScalarField<M, ? extends Number> b, Class<C> type) {
        dividedByCheck(a, b);
        return arithmetic(ExpressionField.ArithKind.QUOT, a, b, type);
    }

    /** {@code -value}. */
    public static <M, C extends Number> ExpressionField<M, C> negate(ScalarField<M, C> value) {
        checkFields(value);
        return new ExpressionField<>(new ExpressionField.Negate(value, ColumnField.boxed(value.type())), value.type(), null);
    }

    /** {@code concat(a, b)}: NULL when any operand is NULL (R-COL-17). */
    public static <M> ExpressionField<M, String> concat(ScalarField<M, String> a, ScalarField<M, String> b) {
        checkFields(a);
        checkFields(b);
        return new ExpressionField<>(new ExpressionField.Concat(a, b, String.class), String.class, null);
    }

    /** {@code concat(a, b)}, with {@code b} a bound {@code String} value. */
    public static <M> ExpressionField<M, String> concat(ScalarField<M, String> a, String b) {
        checkFields(a);
        checkValue(b);
        return new ExpressionField<>(new ExpressionField.Concat(a, b, String.class), String.class, null);
    }

    /** {@code concat(a, b)}, with {@code a} a bound {@code String} value. */
    public static <M> ExpressionField<M, String> concat(String a, ScalarField<M, String> b) {
        checkValue(a);
        checkFields(b);
        return new ExpressionField<>(new ExpressionField.Concat(a, b, String.class), String.class, null);
    }

    /**
     * {@code name(args...)}: vendor SQL, deterministic to order a paged or exported query (R-PAG-25).
     *
     * @throws ModelQueryDefinitionException {@code MQ1506} when {@code name} is not a plain SQL identifier or is a
     *     built-in aggregate
     */
    @SafeVarargs
    public static <M, C> ExpressionField<M, C> function(String name, Class<C> type, ScalarField<M, ?>... args) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(args, "args");
        if (!IDENTIFIER.matcher(name).matches() || AGGREGATES.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            throw new ModelQueryDefinitionException(MqCode.MQ1506, "function(\"" + name + "\"): a function name must "
                    + "be a plain SQL identifier that is not a built-in aggregate");
        }
        var operands = new ArrayList<ScalarField<?, ?>>(args.length);
        for (ScalarField<M, ?> arg : args) {
            ScalarField<M, ?> checked = Objects.requireNonNull(arg, "args element");
            checkFields(checked);
            operands.add(checked);
        }
        return new ExpressionField<>(new ExpressionField.Fn(name, List.copyOf(operands), ColumnField.boxed(type)),
                (Class<C>) ColumnField.boxed(type), null);
    }

    /** {@code value} as SQL definition text, rendered by the provider's literal formatter; never a request value. */
    public static <M> ExpressionField<M, String> constant(String value) {
        return new ExpressionField<>(new ExpressionField.Const(Objects.requireNonNull(value, "value"), String.class),
                String.class, null);
    }

    /** {@code value} as SQL definition text (R-COL-18). */
    public static <M> ExpressionField<M, Integer> constant(int value) {
        return new ExpressionField<>(new ExpressionField.Const(value, Integer.class), Integer.class, null);
    }

    /** {@code value} as SQL definition text (R-COL-18). */
    public static <M> ExpressionField<M, Long> constant(long value) {
        return new ExpressionField<>(new ExpressionField.Const(value, Long.class), Long.class, null);
    }

    /** {@code value} as SQL definition text (R-COL-18). */
    public static <M> ExpressionField<M, Boolean> constant(boolean value) {
        return new ExpressionField<>(new ExpressionField.Const(value, Boolean.class), Boolean.class, null);
    }

    /**
     * A CASE builder whose {@code when(...)} chains at least once; a CASE with no WHEN does not compile. {@code type}
     * is boxed, so {@code cases(M.class, int.class)} is the {@code Integer.class} CASE.
     */
    @SuppressWarnings("unchecked")
    public static <M, R> Cases<M, R> cases(Class<M> model, Class<R> type) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(type, "type");
        return new Cases<>((Class<R>) ColumnField.boxed(type));
    }

    // ---- helpers shared with ExpressionField's nodes

    /** Every column read by {@code operands}, deduplicated, in order (R-COL-19). */
    static List<ColumnField<?, ?, ?>> columns(Object... operands) {
        var columns = new LinkedHashSet<ColumnField<?, ?, ?>>();
        for (Object operand : operands) {
            operand(operand, columns);
        }
        return List.copyOf(columns);
    }

    static void operand(Object operand, java.util.Collection<ColumnField<?, ?, ?>> into) {
        if (operand instanceof ColumnField<?, ?, ?> column) {
            into.add(column);
        } else if (operand instanceof ExpressionField<?, ?> field) {
            into.addAll(field.columns());
        }
    }

    /** The columns a recorded condition reads, at any depth. */
    static void conditions(Condition condition, java.util.Collection<ColumnField<?, ?, ?>> into) {
        condition.column().ifPresent(field -> operand(field, into));
        condition.right().ifPresent(field -> operand(field, into));
        condition.children().forEach(child -> conditions(child, into));
    }

    /** The operand's canonical text, a value shown as {@code ?} (R-INS-09). */
    static String text(Object operand) {
        return operand instanceof ScalarField<?, ?> field ? field.name() : "?";
    }

    // ---- validation

    /** {@code MQ1501} for a column with a {@code ColumnConverter} anywhere in {@code field}'s operands. */
    private static void checkFields(ScalarField<?, ?> field) {
        Objects.requireNonNull(field, "field");
        if (field instanceof ColumnField<?, ?, ?> column && column.isConverted()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1501, column + ": an expression reads a column with a "
                    + "ColumnConverter, which the database computes over without applying the converter; aggregate "
                    + "or filter the attribute instead");
        }
    }

    /** {@code MQ1502} for a null or a value of a type a factory cannot bind. */
    private static void checkValue(Object value) {
        if (value == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, "an expression value is null; a factory binds a "
                    + "non-null value, and Expr.constant is definition text");
        }
        Class<?> type = value.getClass();
        if (type.isArray() || value instanceof Date || value instanceof Calendar || value instanceof Enum<?>
                || type.isAnnotationPresent(jakarta.persistence.Entity.class)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, "an expression cannot bind a value of type "
                    + type.getSimpleName() + "; bind a String, number, boolean or temporal type, or Expr.constant "
                    + "for definition text");
        }
    }

    private static void dividedByCheck(ScalarField<?, ?> a, ScalarField<?, ?> b) {
        checkFields(a);
        checkFields(b);
        if (INTEGRAL.contains(ColumnField.boxed(a.type())) && INTEGRAL.contains(ColumnField.boxed(b.type()))) {
            throw new ModelQueryDefinitionException(MqCode.MQ1503, "dividedBy(" + a + ", " + b + "): an integral "
                    + "division truncates on PostgreSQL and H2 and not on MySQL; divide decimals, or multiply by a "
                    + "decimal first");
        }
    }

    private static <M, C extends Number> ExpressionField<M, C> arithmetic(
            ExpressionField.ArithKind kind, ScalarField<?, ? extends Number> a, Object b, Class<C> type) {
        Objects.requireNonNull(a, "a");
        checkFields(a);
        if (b instanceof ScalarField<?, ?> field) {
            checkFields(field);
        } else {
            checkValue(b);
        }
        @SuppressWarnings("unchecked")
        Class<C> result = type != null ? (Class<C>) ColumnField.boxed(type) : (Class<C>) ColumnField.boxed(a.type());
        return new ExpressionField<>(new ExpressionField.Arith(kind, a, b, result), result, null);
    }

    /** A CASE builder: only {@code when(...)} is exposed, so a CASE with no WHEN does not compile (R-COL-17). */
    public static final class Cases<M, R> {

        private final Class<R> type;

        private Cases(Class<R> type) {
            this.type = type;
        }

        /** The first WHEN, with a field result. */
        public When<M, R> when(UnaryOperator<Filters<M>> condition, ScalarField<M, R> result) {
            return new When<>(type, List.of(branch(condition, result)));
        }

        /** The first WHEN, with a bound value result. */
        public When<M, R> when(UnaryOperator<Filters<M>> condition, R result) {
            return new When<>(type, List.of(branch(condition, result)));
        }
    }

    /** A CASE with at least one WHEN, to chain further WHENs and finish with {@code otherwise} or {@code orNull}. */
    public static final class When<M, R> {

        private final Class<R> type;
        private final List<ExpressionField.CaseBranch> branches;

        private When(Class<R> type, List<ExpressionField.CaseBranch> branches) {
            this.type = type;
            this.branches = branches;
        }

        /** Another WHEN, with a field result. */
        public When<M, R> when(UnaryOperator<Filters<M>> condition, ScalarField<M, R> result) {
            return new When<>(type, append(condition, result));
        }

        /** Another WHEN, with a bound value result. */
        public When<M, R> when(UnaryOperator<Filters<M>> condition, R result) {
            return new When<>(type, append(condition, result));
        }

        /** The CASE, with a field as its {@code ELSE}. */
        public ExpressionField<M, R> otherwise(ScalarField<M, R> result) {
            checkFields(result);
            return build(result, false);
        }

        /** The CASE, with a bound value as its {@code ELSE}. */
        public ExpressionField<M, R> otherwise(R result) {
            checkValue(result);
            return build(result, false);
        }

        /** The CASE, with NULL as its {@code ELSE}. */
        public ExpressionField<M, R> orNull() {
            return build(null, true);
        }

        private List<ExpressionField.CaseBranch> append(UnaryOperator<Filters<M>> condition, Object result) {
            var all = new ArrayList<>(branches);
            all.add(branch(condition, result));
            return List.copyOf(all);
        }

        private ExpressionField<M, R> build(Object otherwise, boolean otherwiseNull) {
            return new ExpressionField<>(new ExpressionField.Case(branches, otherwise, otherwiseNull, type), type,
                    null);
        }
    }

    /** Records and validates one WHEN: {@code MQ1504} with no filter, {@code MQ1505} for a forbidden operator. */
    private static <M> ExpressionField.CaseBranch branch(UnaryOperator<Filters<M>> condition, Object result) {
        List<Filter> filters = FilterGroup.collect(Objects.requireNonNull(condition, "condition"));
        List<Condition> conditions = ConditionGroup.conditions(filters);
        if (conditions.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1504, "cases(...).when(...): the condition left no filter,"
                    + " so it would always match; add a filter or drop the branch");
        }
        for (Condition recorded : conditions) {
            checkCaseCondition(recorded);
        }
        if (result instanceof ScalarField<?, ?> field) {
            checkFields(field);
        } else {
            checkValue(result);
        }
        return new ExpressionField.CaseBranch(filters, conditions, result);
    }

    /** {@code MQ1505} for {@code add}, {@code exists} or a sub-select inside a CASE condition (R-COL-17). */
    private static void checkCaseCondition(Condition condition) {
        switch (condition.kind()) {
            case CUSTOM, EXISTS, NOT_EXISTS, IN_SUBSELECT, NOT_IN_SUBSELECT, EXISTS_SUBSELECT, NOT_EXISTS_SUBSELECT ->
                    throw new ModelQueryDefinitionException(MqCode.MQ1505, "cases(...).when(...): a CASE condition "
                            + "cannot use add(...), exists(...) or a sub-select; use a plain column filter");
            default -> condition.children().forEach(Expr::checkCaseCondition);
        }
    }
}
