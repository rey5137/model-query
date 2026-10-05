package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A typed value the database computes over one vocabulary's columns: what {@link Expr}'s factories build (R-COL-17).
 * It is a {@link ScalarField}, so it is a filter operand, a group key and an {@link Expr} argument, and it is equal by
 * structure: two expressions built from equal calls are one selection and one {@code Row} key (R-COL-20). Immutable,
 * so a constant serves any number of concurrent queries (INV-9).
 *
 * <p>{@link #expression(JoinContext)} returns one Criteria node per {@link JoinContext} for equal expressions, so a
 * provider that references select items by identity renders {@code GROUP BY} and {@code ORDER BY} as references
 * (R-COL-19). Values given to a factory bind; {@link Expr#constant} is definition text (R-COL-18).
 *
 * @param <M> the column vocabulary the expression is written against
 * @param <C> the value's Java type
 * @implSpec api/10 §3a, R-COL-16 to R-COL-20, D-115
 */
@Incubating
public final class ExpressionField<M, C> implements ScalarField<M, C> {

    private final Node node;
    private final Class<C> type;
    /** The model field the expression fills, or {@code null}; not part of {@link #equals}, as {@code ColumnField.named}. */
    private final String property;

    ExpressionField(Node node, Class<C> type, String property) {
        this.node = node;
        this.type = type;
        this.property = property;
    }

    @Override
    public Class<C> type() {
        return type;
    }

    /** The property when {@link #named}, else the canonical text of the structure with values shown as {@code ?}. */
    @Override
    public String name() {
        return property != null ? property : node.text();
    }

    /**
     * The Criteria node for this expression, resolved through {@code ctx}: one node per context for equal expressions
     * (R-COL-19).
     *
     * @throws ModelQueryDefinitionException {@code MQ1507} when the provider resolves a type other than {@link #type()}
     */
    @Override
    @SuppressWarnings("unchecked")
    public Expression<C> expression(JoinContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return (Expression<C>) ctx.expression(this, () -> {
            Expression<?> rendered = node.render(ctx);
            Class<?> actual = rendered.getJavaType();
            if (actual == null || ColumnField.boxed(actual) != type) {
                throw new ModelQueryDefinitionException(MqCode.MQ1507, node.text() + ": declared "
                        + type.getSimpleName() + ", the provider resolves "
                        + (actual == null ? "no type" : actual.getSimpleName())
                        + "; declare the type the database returns, or cast the operands");
            }
            return rendered;
        });
    }

    /** This expression under {@code property} as its name; not part of {@link #equals} (D-55, R-COL-20). */
    public ExpressionField<M, C> named(String property) {
        return new ExpressionField<>(node, type, Objects.requireNonNull(property, "property"));
    }

    /** The name of the model field the expression fills, as given to {@link #named}; empty with none. */
    public Optional<String> property() {
        return Optional.ofNullable(property);
    }

    /** Every column read in the row, CASE conditions included (R-COL-19). */
    @EngineFacing
    public List<ColumnField<M, ?, ?>> columns() {
        @SuppressWarnings("unchecked")
        List<ColumnField<M, ?, ?>> columns = (List<ColumnField<M, ?, ?>>) (List<?>) node.columns();
        return columns;
    }

    /**
     * The bind values one rendering of this expression binds, its node's nested expressions and CASE conditions
     * included (R-COL-18). A memoised node rendered again repeats them, so the bind-limit check adds this once per
     * extra rendering (R-COL-19).
     */
    int binds() {
        return node.binds();
    }

    /** The bind values one rendering of {@code operand} binds: an expression's own, one for a raw value, none for a column. */
    private static int binds(Object operand) {
        if (operand instanceof ExpressionField<?, ?> expression) {
            return expression.binds();
        }
        return operand instanceof ScalarField<?, ?> ? 0 : 1;
    }

    /** The bind values a CASE condition binds: its values, its operands' expressions and its children's (R-COL-18). */
    private static int conditionBinds(Condition condition) {
        int total = condition.values().size();
        if (condition.column().orElse(null) instanceof ExpressionField<?, ?> left) {
            total += left.binds();
        }
        if (condition.right().orElse(null) instanceof ExpressionField<?, ?> right) {
            total += right.binds();
        }
        for (Condition child : condition.children()) {
            total += conditionBinds(child);
        }
        return total;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExpressionField<?, ?> other && node.equals(other.node);
    }

    @Override
    public int hashCode() {
        return node.hashCode();
    }

    @Override
    public String toString() {
        return node.text();
    }

    /** The structure behind an expression: equal nodes are equal expressions (R-COL-20). */
    sealed interface Node
            permits Coalesce, NullIf, Arith, Negate, Concat, Fn, Const, Case {

        Class<?> type();

        Expression<?> render(JoinContext ctx);

        List<ColumnField<?, ?, ?>> columns();

        /** The bind values one rendering of this node binds, nested expressions and CASE conditions included. */
        int binds();

        /** The canonical text, values shown as {@code ?} (R-INS-09). */
        String text();
    }

    /** A bound value: {@code MQ1502} covers null, arrays, {@code Date}, {@code Calendar}, enums and entities. */
    record Coalesce(ScalarField<?, ?> first, Object second, Class<?> type) implements Node {
        @Override
        public Expression<?> render(JoinContext ctx) {
            CriteriaBuilder cb = ctx.cb();
            Expression<Object> a = raw(first.expression(ctx));
            return second instanceof ScalarField<?, ?> field
                    ? cb.coalesce(a, raw(field.expression(ctx)))
                    : cb.coalesce(a, second);
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return Expr.columns(first, second);
        }

        @Override
        public int binds() {
            return ExpressionField.binds(first) + ExpressionField.binds(second);
        }

        @Override
        public String text() {
            return "coalesce(" + Expr.text(first) + ", " + Expr.text(second) + ")";
        }
    }

    record NullIf(ScalarField<?, ?> value, Object sentinel, Class<?> type) implements Node {
        @Override
        public Expression<?> render(JoinContext ctx) {
            return ctx.cb().nullif(raw(value.expression(ctx)), sentinel);
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return Expr.columns(value);
        }

        @Override
        public int binds() {
            return ExpressionField.binds(value) + 1; // the sentinel always binds
        }

        @Override
        public String text() {
            return "nullIf(" + Expr.text(value) + ", ?)";
        }
    }

    /** {@code +}, {@code -}, {@code *} or {@code /}. */
    enum ArithKind {
        SUM(" + "),
        DIFF(" - "),
        PROD(" * "),
        QUOT(" / ");

        private final String symbol;

        ArithKind(String symbol) {
            this.symbol = symbol;
        }
    }

    record Arith(ArithKind kind, ScalarField<?, ?> first, Object second, Class<?> type) implements Node {
        @Override
        @SuppressWarnings("unchecked")
        public Expression<?> render(JoinContext ctx) {
            CriteriaBuilder cb = ctx.cb();
            Expression<Number> a = raw(first.expression(ctx));
            if (second instanceof ScalarField<?, ?> field) {
                Expression<Number> b = raw(field.expression(ctx));
                return switch (kind) {
                    case SUM -> cb.sum(a, b);
                    case DIFF -> cb.diff(a, b);
                    case PROD -> cb.prod(a, b);
                    case QUOT -> cb.quot(a, b);
                };
            }
            Number b = (Number) second;
            return switch (kind) {
                case SUM -> cb.sum(a, b);
                case DIFF -> cb.diff(a, b);
                case PROD -> cb.prod(a, b);
                case QUOT -> cb.quot(a, b);
            };
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return Expr.columns(first, second);
        }

        @Override
        public int binds() {
            return ExpressionField.binds(first) + ExpressionField.binds(second);
        }

        @Override
        public String text() {
            return "(" + Expr.text(first) + kind.symbol + Expr.text(second) + ")";
        }
    }

    record Negate(ScalarField<?, ?> value, Class<?> type) implements Node {
        @Override
        public Expression<?> render(JoinContext ctx) {
            return ctx.cb().neg(raw(value.expression(ctx)));
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return Expr.columns(value);
        }

        @Override
        public int binds() {
            return ExpressionField.binds(value);
        }

        @Override
        public String text() {
            return "-(" + Expr.text(value) + ")";
        }
    }

    /** {@code concat}: NULL when any operand is NULL (R-COL-17). An operand is a field or a {@code String} value. */
    record Concat(Object first, Object second, Class<?> type) implements Node {
        @Override
        public Expression<?> render(JoinContext ctx) {
            CriteriaBuilder cb = ctx.cb();
            if (first instanceof ScalarField<?, ?> a && second instanceof ScalarField<?, ?> b) {
                return cb.concat(raw(a.expression(ctx)), raw(b.expression(ctx)));
            }
            if (first instanceof ScalarField<?, ?> a) {
                return cb.concat(raw(a.expression(ctx)), (String) second);
            }
            return cb.concat((String) first, raw(((ScalarField<?, ?>) second).expression(ctx)));
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return Expr.columns(first, second);
        }

        @Override
        public int binds() {
            return ExpressionField.binds(first) + ExpressionField.binds(second);
        }

        @Override
        public String text() {
            return "concat(" + Expr.text(first) + ", " + Expr.text(second) + ")";
        }
    }

    record Fn(String name, List<ScalarField<?, ?>> args, Class<?> type) implements Node {
        @Override
        @SuppressWarnings("unchecked")
        public Expression<?> render(JoinContext ctx) {
            Expression<?>[] rendered = new Expression<?>[args.size()];
            for (int i = 0; i < args.size(); i++) {
                rendered[i] = args.get(i).expression(ctx);
            }
            return ctx.cb().function(name, (Class<Object>) type, rendered);
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return Expr.columns(args.toArray());
        }

        @Override
        public int binds() {
            int total = 0;
            for (ScalarField<?, ?> arg : args) {
                total += ExpressionField.binds(arg);
            }
            return total;
        }

        @Override
        public String text() {
            var parts = new ArrayList<String>(args.size());
            args.forEach(arg -> parts.add(Expr.text(arg)));
            return name + "(" + String.join(", ", parts) + ")";
        }
    }

    /** SQL text of the definition, rendered by the provider's literal formatter; never a request value (R-COL-18). */
    record Const(Object value, Class<?> type) implements Node {
        @Override
        public Expression<?> render(JoinContext ctx) {
            return ctx.cb().literal(value);
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return List.of();
        }

        @Override
        public int binds() {
            return 0; // definition text, not a value (R-COL-18)
        }

        @Override
        public String text() {
            return String.valueOf(value);
        }
    }

    /**
     * A CASE over a condition and a result per branch, with an {@code otherwise} or a NULL. {@code filters} render the
     * condition; {@code conditions} are its structure, which equality compares (R-COL-20).
     */
    record CaseBranch(List<Filter> filters, List<Condition> conditions, Object result) {
        @Override
        public boolean equals(Object o) {
            return o instanceof CaseBranch other && conditions.equals(other.conditions)
                    && Objects.equals(result, other.result);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conditions, result);
        }
    }

    record Case(List<CaseBranch> branches, Object otherwise, boolean otherwiseNull, Class<?> type) implements Node {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Expression<?> render(JoinContext ctx) {
            CriteriaBuilder cb = ctx.cb();
            CriteriaBuilder.Case caseExpr = cb.selectCase();
            for (CaseBranch branch : branches) {
                Predicate when = and(cb, ConditionGroup.toPredicates(branch.filters(), ctx));
                if (branch.result() instanceof ScalarField<?, ?> field) {
                    caseExpr.when(when, (Expression) field.expression(ctx));
                } else {
                    caseExpr.when(when, branch.result());
                }
            }
            if (otherwise instanceof ScalarField<?, ?> field) {
                caseExpr.otherwise((Expression) field.expression(ctx));
            } else if (otherwiseNull) {
                caseExpr.otherwise(cb.nullLiteral(type));
            } else {
                caseExpr.otherwise(otherwise);
            }
            return caseExpr;
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            var columns = new ArrayList<ColumnField<?, ?, ?>>();
            for (CaseBranch branch : branches) {
                branch.conditions().forEach(condition -> Expr.conditions(condition, columns));
                Expr.operand(branch.result(), columns);
            }
            Expr.operand(otherwise, columns);
            return List.copyOf(columns);
        }

        @Override
        public int binds() {
            int total = 0;
            for (CaseBranch branch : branches) {
                for (Condition condition : branch.conditions()) {
                    total += conditionBinds(condition);
                }
                total += ExpressionField.binds(branch.result());
            }
            return otherwiseNull ? total : total + ExpressionField.binds(otherwise);
        }

        @Override
        public String text() {
            var out = new StringBuilder("case");
            for (CaseBranch branch : branches) {
                out.append(" when ").append(conditionText(branch.conditions())).append(" then ")
                        .append(Expr.text(branch.result()));
            }
            if (otherwiseNull) {
                out.append(" else null");
            } else {
                out.append(" else ").append(Expr.text(otherwise));
            }
            return out.append(" end").toString();
        }

        private static String conditionText(List<Condition> conditions) {
            var parts = new ArrayList<String>(conditions.size());
            conditions.forEach(condition -> parts.add(condition.toString()));
            return String.join(" and ", parts);
        }

        private static Predicate and(CriteriaBuilder cb, List<Predicate> parts) {
            return parts.isEmpty() ? cb.conjunction()
                    : parts.size() == 1 ? parts.get(0) : cb.and(parts.toArray(Predicate[]::new));
        }
    }

    @SuppressWarnings("unchecked")
    static <T> Expression<T> raw(Expression<?> expression) {
        return (Expression<T>) expression;
    }
}
