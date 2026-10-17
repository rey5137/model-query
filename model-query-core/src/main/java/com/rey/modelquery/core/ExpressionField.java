package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    /**
     * The model field the expression fills, or {@code null}; not part of {@link #equals}, as {@code ColumnField.named}.
     */
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
    public Expression<C> expression(JoinContext ctx) {
        return render(ctx, false);
    }

    /**
     * As {@link #expression}, for the operand of a comparison or an IN, the one position a column-typed
     * {@link Expr#constant(ColumnField, Object) constant} may take (R-COL-27).
     */
    Expression<C> operand(JoinContext ctx) {
        return render(ctx, true);
    }

    @SuppressWarnings("unchecked")
    private Expression<C> render(JoinContext ctx, boolean operand) {
        Objects.requireNonNull(ctx, "ctx");
        if (ctx.aggregating() && correlated()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1510,
                    node.text() + ": a correlated value cannot be aggregated; select it through @Computed");
        }
        if (node instanceof TypedConst typed && !operand) {
            // Its literal is the stored value, typed by the attribute, not by the declared model type: read as a
            // model value it would not fit (R-COL-27).
            throw new ModelQueryDefinitionException(MqCode.MQ1508, typed.typedBy() + ": Expr.constant(" + typed.value()
                    + ") is operand-only, the operand of a comparison or an IN; it is no model column, projection, "
                    + "order, group key or function argument");
        }
        // Checked on the whole tree before the memo: an expression rendered first by the select would otherwise come
        // back from it without its nested sequence being seen (R-COL-28).
        if (ctx.sequencesRefused() && readsSequence(this)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1509, node.text() + " reads a sequence value, which "
                    + "advances on every evaluation, so it is no filter, having, order or group key operand");
        }
        return (Expression<C>) ctx.expression(this, () -> {
            Expression<?> rendered = node.render(ctx);
            if (node instanceof TypedConst) {
                // The declared type is the witness column's model type and the literal's is the stored value's, so the
                // two are not compared (R-COL-27).
                return rendered;
            }
            Class<?> actual = rendered.getJavaType();
            if (actual == null || !ColumnField.reads(type, actual)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1507, node.text() + ": declared "
                        + ColumnField.name(type, actual) + ", the provider resolves "
                        + (actual == null ? "no type" : ColumnField.name(actual, type))
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

    /**
     * The binds of {@code operand}: an expression's own, one for a raw value, none for a column.
     */
    private static int binds(Object operand) {
        if (operand instanceof ExpressionField<?, ?> expression) {
            return expression.binds();
        }
        return operand instanceof ScalarField<?, ?> ? 0 : 1;
    }

    /** Whether this expression reads {@code Expr.nextval} anywhere, CASE conditions included (R-COL-28). */
    boolean readsSequence() {
        return readsSequence(this);
    }

    /**
     * {@code MQ1509} for {@code text}, a sequence read where the database or the provider has none (R-COL-28): at
     * rendering, or before a write's first statement (R-WRT-53).
     */
    static ModelQueryDefinitionException noSequences(String text) {
        return new ModelQueryDefinitionException(MqCode.MQ1509, text + ": the database or the persistence provider has "
                + "no sequence support (VendorProfile#sequences, ProviderSupport#sequences)");
    }

    /** Whether {@code operand} is an expression reading {@code Expr.nextval} anywhere, CASE conditions included. */
    private static boolean readsSequence(Object operand) {
        return containsNode(operand, node -> node instanceof Nextval);
    }

    /** A correlated value at any depth of this expression (R-COL-33). */
    boolean correlated() {
        return containsNode(this, node -> node instanceof CorrelatedValue);
    }

    /** A correlated value in a grouped selection or order must lift only group keys (R-COL-33). */
    static void checkCorrelatedGroup(Object operand, List<? extends ScalarField<?, ?>> keys) {
        containsNode(operand, node -> {
            if (node instanceof CorrelatedValue correlated && !keys.containsAll(correlated.columns())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1314, correlated.text()
                        + ": a correlated value in a grouped selection or order must lift only group keys");
            }
            return false;
        });
    }

    static boolean containsCorrelated(Object operand) {
        return containsNode(operand, node -> node instanceof CorrelatedValue);
    }

    private static boolean containsNode(Object operand, java.util.function.Predicate<Node> test) {
        if (operand instanceof Condition condition) {
            return containsNode(condition.column().orElse(null), test)
                    || containsNode(condition.right().orElse(null), test)
                    || condition.operands().stream().anyMatch(value -> containsNode(value, test))
                    || condition.children().stream().anyMatch(child -> containsNode(child, test));
        }
        if (!(operand instanceof ExpressionField<?, ?> expression)) {
            return false;
        }
        Node node = expression.node;
        if (test.test(node)) {
            return true;
        }
        if (node instanceof Coalesce n) {
            return containsNode(n.first(), test) || containsNode(n.second(), test);
        }
        if (node instanceof NullIf n) {
            return containsNode(n.value(), test);
        }
        if (node instanceof Arith n) {
            return containsNode(n.first(), test) || containsNode(n.second(), test);
        }
        if (node instanceof Negate n) {
            return containsNode(n.value(), test);
        }
        if (node instanceof Concat n) {
            return containsNode(n.first(), test) || containsNode(n.second(), test);
        }
        if (node instanceof Fn n) {
            return n.args().stream().anyMatch(value -> containsNode(value, test));
        }
        if (node instanceof Case n) {
            return containsNode(n.otherwise(), test) || n.branches().stream().anyMatch(branch ->
                    containsNode(branch.result(), test)
                            || branch.conditions().stream().anyMatch(condition -> containsNode(condition, test)));
        }
        return false; // Const, TypedConst and Value read nothing
    }

    /** The bind values a CASE condition binds: its values, its operands' expressions and its children's (R-COL-18). */
    static int conditionBinds(Condition condition) {
        int total = condition.values().size();
        if (condition.column().orElse(null) instanceof ExpressionField<?, ?> left) {
            total += left.binds();
        }
        if (condition.right().orElse(null) instanceof ExpressionField<?, ?> right) {
            total += right.binds();
        }
        for (ScalarField<?, ?> operand : condition.operands()) {
            total += binds(operand);
        }
        for (Condition child : condition.children()) {
            total += conditionBinds(child);
        }
        if (condition.subSelect().isPresent()) {
            for (Condition inner : condition.subSelect().orElseThrow().conditions()) {
                total += conditionBinds(inner);
            }
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
            permits Coalesce, NullIf, Arith, Negate, Concat, Fn, Const, TypedConst, Value, Nextval, Case,
                    CorrelatedValue {

        Class<?> type();

        Expression<?> render(JoinContext ctx);

        List<ColumnField<?, ?, ?>> columns();

        /** The bind values one rendering of this node binds, nested expressions and CASE conditions included. */
        int binds();

        /** The canonical text, values shown as {@code ?} (R-INS-09). */
        String text();
    }

    record CorrelatedValue(SubSelect<?, ?> rows, List<Filter> correlation, List<Condition> conditions,
            Correlated.Terminal terminal, Class<?> type, List<OrderField<?, ?>> orders) implements Node {
        @Override
        public Expression<?> render(JoinContext ctx) {
            return ctx.correlatedValue(rows, correlation, terminal, type, orders);
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            var read = new LinkedHashSet<ColumnField<?, ?, ?>>();
            conditions.forEach(condition -> liftedColumns(condition, read));
            // Only the lifted columns belong to the enclosing query; the inner columns join inside the sub-query.
            return read.stream().filter(ColumnField::isLifted)
                    .<ColumnField<?, ?, ?>>map(column -> column.liftedFrom().orElseThrow())
                    .distinct().toList();
        }

        private static void liftedColumns(Condition condition, LinkedHashSet<ColumnField<?, ?, ?>> into) {
            condition.column().ifPresent(field -> Expr.operand(field, into));
            condition.right().ifPresent(field -> Expr.operand(field, into));
            condition.operands().forEach(field -> Expr.operand(field, into));
            // A nested sub-select lifts its own enclosing root, not this correlated column's outer root.
            if (condition.kind() != Condition.Kind.EXISTS_SUBSELECT
                    && condition.kind() != Condition.Kind.NOT_EXISTS_SUBSELECT) {
                condition.children().forEach(child -> liftedColumns(child, into));
            }
        }

        @Override
        public int binds() {
            return rows.conditions().stream().mapToInt(ExpressionField::conditionBinds).sum()
                    + conditions.stream().mapToInt(ExpressionField::conditionBinds).sum()
                    + orders.stream().mapToInt(order -> ExpressionField.binds(order.column())).sum()
                    + (terminal == Correlated.Terminal.FIRST ? 1 : 0);
        }

        @Override
        public String text() {
            return "correlated(" + terminal + ", " + rows + ", " + conditions
                    + (orders.isEmpty() ? "" : ", " + orders) + ")";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CorrelatedValue other && rows.equals(other.rows)
                    && equalConditions(rows.conditions(), other.rows.conditions())
                    && equalConditions(conditions, other.conditions) && terminal == other.terminal
                    && orders.equals(other.orders);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rows, conditionsHash(rows.conditions()), conditionsHash(conditions), terminal, orders);
        }

        private static boolean equalConditions(List<Condition> first, List<Condition> second) {
            if (first.size() != second.size()) {
                return false;
            }
            for (int i = 0; i < first.size(); i++) {
                Condition a = first.get(i);
                Condition b = second.get(i);
                // A label describes a custom predicate but cannot establish equal executable sub-queries.
                if (a.kind() == Condition.Kind.CUSTOM ? a != b : !a.equals(b)) {
                    return false;
                }
                if (!equalConditions(a.children(), b.children())) {
                    return false;
                }
                if (a.subSelect().isPresent() && !equalConditions(a.subSelect().orElseThrow().conditions(),
                        b.subSelect().orElseThrow().conditions())) {
                    return false;
                }
            }
            return true;
        }

        private static int conditionsHash(List<Condition> conditions) {
            int hash = 1;
            for (Condition condition : conditions) {
                int value = condition.kind() == Condition.Kind.CUSTOM
                        ? System.identityHashCode(condition) : condition.hashCode();
                value = 31 * value + conditionsHash(condition.children());
                value = 31 * value + condition.subSelect().map(sub -> conditionsHash(sub.conditions())).orElse(0);
                hash = 31 * hash + value;
            }
            return hash;
        }
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
     * SQL text of the definition, the provider's literal of the value {@code typedBy}'s attribute stores (R-COL-27).
     * {@code value} is the attribute value, after {@code typedBy}'s converter; {@code typedBy} is a type witness, part
     * of equality but of no column (R-COL-20).
     */
    record TypedConst(ColumnField<?, ?, ?> typedBy, Object value, Class<?> type) implements Node {
        @Override
        public Expression<?> render(JoinContext ctx) {
            Class<?> entity = typedBy.table().rootEntity();
            ConstantRenderer renderer = entity == null ? null : ctx.constantRenderer().orElse(null);
            if (renderer == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1508, typedBy + ": Expr.constant cannot render "
                        + value + " as a literal; " + (entity == null
                                ? "its type witness is a column of no root table's entity"
                                : "the persistence provider supplies no ConstantRenderer"));
            }
            Object stored = renderer.storedValue(entity, typedBy.name(), value).orElse(null);
            if (stored == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1508, typedBy + ": Expr.constant cannot render "
                        + value + " as a literal; its type witness must be a basic attribute of a root table's "
                        + "entity, not an embedded, dotted or joined column");
            }
            if (!(stored instanceof String || stored instanceof Number || stored instanceof Boolean)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1508, typedBy + ": Expr.constant cannot render "
                        + value + " as a literal; its stored value " + stored.getClass().getSimpleName()
                        + " is not a String, Number or Boolean");
            }
            return ctx.cb().literal(stored);
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return List.of(); // typedBy is a type witness, not a column the row reads (R-COL-27)
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
     * A bound value: a statement parameter the executor binds on every statement that renders it (R-COL-26). The
     * expression is typed by {@code value}'s class, of no column and no join; equality is by class and value
     * (R-COL-20).
     */
    record Value(Object value, Class<?> type) implements Node {
        @Override
        public Expression<?> render(JoinContext ctx) {
            return ctx.parameter(type, value);
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return List.of();
        }

        @Override
        public int binds() {
            return 1; // the value binds once per rendering (R-COL-26)
        }

        @Override
        public String text() {
            return "?"; // a value is shown as a bind, never its text (R-INS-09)
        }
    }

    /**
     * A sequence's next value, rendered by the provider's {@link SequenceRenderer} (R-COL-28). It binds nothing, reads
     * no column and adds no join; equality is by sequence name (R-COL-20).
     */
    record Nextval(String sequence) implements Node {
        @Override
        public Class<?> type() {
            return Long.class;
        }

        @Override
        public Expression<?> render(JoinContext ctx) {
            SequenceRenderer renderer = ctx.sequenceRenderer().orElseThrow(() -> noSequences(text()));
            return renderer.nextValue(ctx.cb(), sequence);
        }

        @Override
        public List<ColumnField<?, ?, ?>> columns() {
            return List.of();
        }

        @Override
        public int binds() {
            return 0;
        }

        @Override
        public String text() {
            return "nextval(" + sequence + ")";
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
