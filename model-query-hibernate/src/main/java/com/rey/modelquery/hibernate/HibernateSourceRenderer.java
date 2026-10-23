package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.core.RecursiveSpec;
import com.rey.modelquery.core.SetOperation;
import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.core.WindowSpec;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaCteCriteriaAttribute;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.criteria.JpaSelectCriteria;
import org.hibernate.query.criteria.JpaSubQuery;
import org.hibernate.query.criteria.JpaWindow;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * Hibernate's {@link SourceRenderer}: {@code JpaCriteriaQuery#with} registers the source's body as the statement's
 * common table expression and {@code JpaSelectCriteria#from(JpaCteCriteria)} reads it, identical on Hibernate 6.6 and
 * 7.4 (R-SRC-12, R-SRC-18, D-143, D-150). Every CTE in a chain is registered on the top statement and carries no
 * cross-statement state.
 *
 * <p>A joined source is {@code JpaFrom#join(JpaCteCriteria, SqmJoinType)} with the engine's {@code on} applied to the
 * join it returns (R-SRC-21, R-SRC-22). That join is a {@code JpaJoinedFrom} on 6.6, which is no Criteria
 * {@code Join}, and a {@code JpaJoin} on 7.4, where {@code JpaJoinedFrom} does not exist (RFC 0020 A.5): both methods
 * are looked up once by reflection, so one class links on either line, and a line without them refuses a joined
 * source with {@code MQ4014} while every other source still renders.
 *
 * <p>A window is {@code createWindow()} with the partition, order and frame terms, handed to a function: the typed
 * {@code rowNumber}, {@code rank}, {@code denseRank}, {@code sum} and {@code firstValue} carry the function's result
 * type, and {@code windowFunction} renders the rest by name with the type {@code core} declares (R-SRC-04). The frame
 * is rendered only for a function that takes one, which H2 rejects.
 */
final class HibernateSourceRenderer implements SourceRenderer {

    static final HibernateSourceRenderer INSTANCE = new HibernateSourceRenderer(CteJoin.of(JpaFrom.class));

    /** How this line joins a CTE, or empty when it cannot: a joined source is then {@code MQ4014}, no other source. */
    private final Optional<CteJoin> cteJoin;

    private record HibernateCteHandle(JpaCteCriteria<Tuple> cte) implements CteHandle {}

    /**
     * {@code join(JpaCteCriteria, SqmJoinType)} on a {@code JpaFrom} type, whose return type differs per line (RFC 0020
     * A.5), and {@code on(Predicate...)} of the type it returns.
     */
    record CteJoin(Method join, Method on) {

        /** Both methods on {@code from}, or empty when either is missing, so a missing one fails no class init. */
        static Optional<CteJoin> of(Class<?> from) {
            try {
                Method join = from.getMethod("join", JpaCteCriteria.class, SqmJoinType.class);
                return Optional.of(new CteJoin(join, join.getReturnType().getMethod("on", Predicate[].class)));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }
    }

    HibernateSourceRenderer(Optional<CteJoin> cteJoin) {
        this.cteJoin = cteJoin;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CteHandle register(CriteriaQuery<?> top, CriteriaQuery<Tuple> body) {
        // jpa builds the top statement with the provider's own CriteriaBuilder, so it is Hibernate's query type.
        JpaCriteriaQuery<Tuple> query = (JpaCriteriaQuery<Tuple>) top;
        return new HibernateCteHandle(query.with(body));
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecursiveHandle registerRecursive(CriteriaQuery<?> top, RecursiveSpec spec) {
        return registerRecursive((JpaCriteriaQuery<Tuple>) top, spec);
    }

    @Override
    public Root<Tuple> from(AbstractQuery<?> query, CteHandle inner) {
        if (!(inner instanceof HibernateCteHandle handle)) {
            throw new IllegalArgumentException("Expected a Hibernate CteHandle, got: " + inner);
        }
        return ((JpaSelectCriteria<?>) query).from(handle.cte());
    }

    @Override
    @SuppressWarnings("unchecked")
    public From<?, Tuple> join(From<?, ?> outer, CteHandle source, JoinKind kind,
            Function<From<?, Tuple>, Predicate> on) {
        if (cteJoin.isEmpty()) {
            return SourceRenderer.super.join(outer, source, kind, on); // MQ4014, naming joined sources
        }
        if (!(source instanceof HibernateCteHandle handle)) {
            throw new IllegalArgumentException("Expected a Hibernate CteHandle, got: " + source);
        }
        SqmJoinType joinType = switch (kind) {
            case INNER -> SqmJoinType.INNER;
            case LEFT -> SqmJoinType.LEFT;
        };
        // jpa builds the statement with the provider's own CriteriaBuilder, so every From is Hibernate's.
        CteJoin methods = cteJoin.get();
        JpaFrom<?, Tuple> joined = (JpaFrom<?, Tuple>) invoke(methods.join(), outer, handle.cte(), joinType);
        invoke(methods.on(), joined, (Object) new Predicate[] {on.apply(joined)});
        return joined;
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(method + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(method + " is not accessible", e);
        }
    }

    /**
     * The recursive CTE of {@code query}, registered as {@code with recursive} over {@code spec}'s seed and term,
     * with the cycle clause marking the row that closes a cycle, and the checks as scalar sub-selects over the
     * same CTE (R-SRC-07, R-SRC-18). Everything here is identical on Hibernate 6.6 and 7.4 (RFC 0013 spike, items 3
     * and 10; RFC 0015 probe 6).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private RecursiveHandle registerRecursive(JpaCriteriaQuery<Tuple> query, RecursiveSpec spec) {
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
        return new RecursiveHandle(new HibernateCteHandle(cte), depthKey, cycleKey, duplicateKey);
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
