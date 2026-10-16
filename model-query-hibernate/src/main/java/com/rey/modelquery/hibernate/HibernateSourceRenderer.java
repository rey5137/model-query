package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.core.RecursiveCte;
import com.rey.modelquery.core.RecursiveSpec;
import com.rey.modelquery.core.SetOperation;
import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.core.WindowSpec;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaCteCriteriaAttribute;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.criteria.JpaSubQuery;
import org.hibernate.query.criteria.JpaWindow;

/**
 * Hibernate's {@link SourceRenderer}: {@code JpaCriteriaQuery#with} registers the source's body as the statement's
 * common table expression and {@code JpaCriteriaQuery#from(JpaCteCriteria)} reads it, identical on Hibernate 6.6 and
 * 7.4 (R-SRC-12, D-143). The CTE is registered on each statement {@code jpa} builds over a source, so the call is
 * per statement and carries no state.
 *
 * <p>A window is {@code createWindow()} with the partition, order and frame terms, handed to a function: the typed
 * {@code rowNumber}, {@code rank}, {@code denseRank}, {@code sum} and {@code firstValue} carry the function's result
 * type, and {@code windowFunction} renders the rest by name with the type {@code core} declares (R-SRC-04). The frame
 * is rendered only for a function that takes one, which H2 rejects.
 */
final class HibernateSourceRenderer implements SourceRenderer {

    static final HibernateSourceRenderer INSTANCE = new HibernateSourceRenderer();

    private HibernateSourceRenderer() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
        // jpa builds the outer statement with the provider's own CriteriaBuilder, so it is Hibernate's query type.
        JpaCriteriaQuery<Tuple> query = (JpaCriteriaQuery<Tuple>) outer;
        JpaCteCriteria<Tuple> cte = query.with(body);
        return query.from(cte);
    }

    @Override
    public RecursiveCte recursive(CriteriaQuery<?> outer, RecursiveSpec spec) {
        return recursive((JpaCriteriaQuery<Tuple>) outer, spec);
    }

    /**
     * The recursive CTE of {@code query}, registered as {@code with recursive} over {@code spec}'s seed and term,
     * with the cycle clause marking the row that closes a cycle, and the checks as scalar sub-selects over the
     * same CTE (R-SRC-07). Everything here is identical on Hibernate 6.6 and 7.4 (RFC 0013 spike, items 3 and 10).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private RecursiveCte recursive(JpaCriteriaQuery<Tuple> query, RecursiveSpec spec) {
        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) spec.cb();
        JpaCteCriteria<Tuple> cte = query.withRecursiveUnionAll((JpaCriteriaQuery<Tuple>) spec.seed(), criteria -> {
            JpaCriteriaQuery<Tuple> term = (JpaCriteriaQuery<Tuple>) spec.cb().createTupleQuery();
            return (JpaCriteriaQuery<Tuple>) spec.term().term(term, term.from(criteria));
        });
        // The cycle clause marks the row that closes a cycle on the CTE's key columns, so the outer filter and the
        // cycle check both see it; the marked row is still emitted (RFC 0013 §5).
        cte.cycle(spec.markAlias(), spec.keyAliases().stream()
                .map(alias -> cte.getType().getAttribute(alias))
                .toArray(JpaCteCriteriaAttribute[]::new));
        // Scalar sub-selects over the same CTE, read from the outer statement's first row: the smallest key past
        // maxDepth, the smallest marked key, and, for a tree, the first duplicated whole key (R-SRC-14).
        JpaSubQuery depth = (JpaSubQuery) query.subquery(spec.keyType());
        JpaRoot<Tuple> deepest = (JpaRoot<Tuple>) depth.from(cte);
        Expression<?> depthKey = firstKey(depth, deepest, spec, cb,
                cb.gt(deepest.<Integer>get(spec.levelAlias()), spec.maxDepth()));
        JpaSubQuery cycle = (JpaSubQuery) query.subquery(spec.keyType());
        JpaRoot<Tuple> closed = (JpaRoot<Tuple>) cycle.from(cte);
        Expression<?> cycleKey = firstKey(cycle, closed, spec, cb,
                cb.isTrue(closed.<Boolean>get(spec.markAlias())));
        Expression<?> duplicateKey = null;
        if (spec.tree()) {
            JpaSubQuery duplicate = (JpaSubQuery) query.subquery(spec.keyType());
            JpaRoot<Tuple> node = (JpaRoot<Tuple>) duplicate.from(cte);
            List<Expression<?>> keys = spec.keyAliases().stream().<Expression<?>>map(node::get).toList();
            duplicate.select(keys.get(0)).groupBy(keys).having(cb.gt(cb.count(cb.literal(1)), 1));
            duplicate.orderBy(keys.stream().map(cb::asc).toList());
            duplicate.fetch(1);
            duplicateKey = duplicate;
        }
        return new RecursiveCte(query.from(cte), depthKey, cycleKey, duplicateKey);
    }

    /** {@code sub} selecting the smallest key of the CTE rows {@code where} matches, as the sub-query itself. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Expression<?> firstKey(JpaSubQuery sub, JpaRoot<Tuple> cte, RecursiveSpec spec,
            HibernateCriteriaBuilder cb, Predicate where) {
        sub.select((Expression) cb.min(cte.get(spec.keyAliases().get(0)))).where(where);
        return (Expression<?>) sub;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Expression<?> window(CriteriaBuilder cb, WindowSpec spec) {
        // jpa builds the source's body with the provider's own CriteriaBuilder, so it is Hibernate's builder.
        HibernateCriteriaBuilder builder = (HibernateCriteriaBuilder) cb;
        JpaWindow window = window(builder, spec);
        Expression<?> argument = spec.argument();
        // Unchecked: sum reads any Number and declares its own result type; the argument is a basic attribute.
        return switch (spec.function()) {
            case ROW_NUMBER -> builder.rowNumber(window);
            case RANK -> builder.rank(window);
            case DENSE_RANK -> builder.denseRank(window);
            case SUM -> builder.sum((Expression<Number>) argument, window);
            case FIRST_VALUE -> builder.firstValue(argument, window);
            case LAG -> byName(builder, "lag", argument, window);
            case LEAD -> byName(builder, "lead", argument, window);
            case MIN -> byName(builder, "min", argument, window);
            case MAX -> byName(builder, "max", argument, window);
            case LAST_VALUE -> byName(builder, "last_value", argument, window);
            case AVG -> builder.windowFunction("avg", Double.class, window, argument);
            case COUNT -> builder.windowFunction("count", Long.class, window, argument);
        };
    }

    @Override
    public CriteriaQuery<Tuple> combine(CriteriaBuilder cb, SetOperation operation, CriteriaQuery<Tuple> left,
            CriteriaQuery<Tuple> right) {
        // The (boolean, CriteriaQuery...) overloads are identical on Hibernate 6.6 and 7.4 and return the provider's
        // query type on both lines, so the combined statement can carry the source's CTE (R-SRC-05, D-143).
        HibernateCriteriaBuilder builder = (HibernateCriteriaBuilder) cb;
        return switch (operation) {
            case UNION -> builder.union(false, left, right);
            case UNION_ALL -> builder.union(true, left, right);
            case INTERSECT -> builder.intersect(false, left, right);
            case EXCEPT -> builder.except(false, left, right);
        };
    }

    /**
     * A window function neither line has a typed method for: its SQL name, the argument's own type, and the argument
     * (R-SRC-04).
     */
    private static Expression<?> byName(HibernateCriteriaBuilder builder, String name, Expression<?> argument,
            JpaWindow window) {
        return builder.windowFunction(name, argument.getJavaType(), window, argument);
    }

    /** {@code spec}'s window: the partition terms, the order terms and the frame (R-SRC-04). */
    private static JpaWindow window(HibernateCriteriaBuilder builder, WindowSpec spec) {
        JpaWindow window = builder.createWindow();
        if (!spec.partitionBy().isEmpty()) {
            window = window.partitionBy(spec.partitionBy().toArray(new Expression<?>[0]));
        }
        if (!spec.orderBy().isEmpty()) {
            window = window.orderBy(spec.orderBy().toArray(new Order[0]));
        }
        // A frame is rendered for a function that takes one: ROW_NUMBER, RANK and DENSE_RANK take none, and H2
        // rejects a frame on LAG and LEAD (RFC 0013 spike, item 4).
        if (takesFrame(spec)) {
            return frame(builder, window, spec.frame());
        }
        return window;
    }

    /** Whether {@code spec}'s function takes a frame: every frame {@code core} renders belongs to one that does. */
    private static boolean takesFrame(WindowSpec spec) {
        return switch (spec.function()) {
            case ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD -> false;
            case SUM, AVG, MIN, MAX, COUNT, FIRST_VALUE, LAST_VALUE -> spec.frame() != WindowFrame.NONE;
        };
    }

    /** {@code frame} on {@code window}: the two bounds, as {@code ROWS} or {@code RANGE} (R-PROC-36). */
    private static JpaWindow frame(HibernateCriteriaBuilder builder, JpaWindow window, WindowFrame frame) {
        return switch (frame) {
            case ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW ->
                    window.frameRows(builder.frameUnboundedPreceding(), builder.frameCurrentRow());
            case ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING ->
                    window.frameRows(builder.frameUnboundedPreceding(), builder.frameUnboundedFollowing());
            case ROWS_CURRENT_ROW_TO_UNBOUNDED_FOLLOWING ->
                    window.frameRows(builder.frameCurrentRow(), builder.frameUnboundedFollowing());
            case RANGE_UNBOUNDED_PRECEDING_TO_CURRENT_ROW ->
                    window.frameRange(builder.frameUnboundedPreceding(), builder.frameCurrentRow());
            case RANGE_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING ->
                    window.frameRange(builder.frameUnboundedPreceding(), builder.frameUnboundedFollowing());
            case NONE -> window;
        };
    }
}
