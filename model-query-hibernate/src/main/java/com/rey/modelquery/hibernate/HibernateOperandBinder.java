package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.OperandBinder;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import org.hibernate.query.sqm.NodeBuilder;
import org.hibernate.query.sqm.tree.SqmTypedNode;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.expression.JpaCriteriaParameter;
import org.hibernate.query.sqm.tree.expression.SqmAggregateFunction;
import org.hibernate.query.sqm.tree.expression.SqmDistinct;
import org.hibernate.query.sqm.tree.expression.SqmExpression;
import org.hibernate.query.sqm.tree.expression.SqmFunction;
import org.hibernate.type.BasicType;

/**
 * Hibernate's {@link OperandBinder}: a {@code JpaCriteriaParameter} whose anticipated type is the column's own basic
 * type ({@code ANTICIPATED_PATH}, D-167), so the value binds through the column's mapping (an
 * {@code @Enumerated(STRING)} enum as its name, a {@code @Convert} value through its converter, an array as the line
 * maps arrays) and stays {@code ?} under {@code hibernate.criteria.value_handling_mode=inline}. Never
 * {@code cb.parameter(class)}, which types nothing by the mapping, nor {@code SqmCriteriaNodeBuilder#value}, which
 * inlines under {@code inline}.
 *
 * <p>One adapter for the 6.6.x and 7.4.x lines, compiled against either; their two differences are found by
 * reflection: the constructor's {@code BindableType} ({@code org.hibernate.query} on 6.6, {@code org.hibernate.type}
 * on 7.4), and a path's node type, on 6.6 a path source unwrapped through {@code getSqmPathType()} and on 7.4 already
 * the basic type. Any other node type is refused, never bound by its class alone, so a line whose internals moved
 * fails loudly (D-170).
 *
 * <p>Only a path, or an aggregate over one, carries the column's mapping: any other expression's node type (a
 * function's, a {@code CASE}'s, a sub-query's) Hibernate may infer from a Java class alone, which would bind an enum by
 * ordinal. Such an expression is declined ({@link #typesBy}), and the engine binds only a mapping-independent value
 * over it (R-FLT-21, D-168, D-170). Immutable and thread-safe.
 *
 * @implSpec vendor/40 R-VND-24, api/12 R-FLT-21, D-167, D-170
 */
final class HibernateOperandBinder implements OperandBinder {

    /** {@code JpaCriteriaParameter(String name, BindableType type, boolean multiValued, NodeBuilder nodeBuilder)}. */
    private final Constructor<?> parameter;
    /** Whether a path's node type is a path source to unwrap, as on 6.6 (D-170). */
    private final boolean unwrapsPathSource;
    private final String version;

    private HibernateOperandBinder(Constructor<?> parameter, boolean unwrapsPathSource, String version) {
        this.parameter = parameter;
        this.unwrapsPathSource = unwrapsPathSource;
        this.version = version;
    }

    /**
     * The binder for Hibernate {@code version}, a 6.6.x or 7.4.x one, or empty when this line's
     * {@code JpaCriteriaParameter} has no {@code (String, BindableType, boolean, NodeBuilder)} constructor.
     */
    static Optional<HibernateOperandBinder> of(String version) {
        return Arrays.stream(JpaCriteriaParameter.class.getConstructors())
                .filter(c -> {
                    Class<?>[] types = c.getParameterTypes();
                    return types.length == 4 && types[0] == String.class
                            && "BindableType".equals(types[1].getSimpleName()) && types[2] == boolean.class
                            && types[3] == NodeBuilder.class;
                })
                .findFirst()
                .map(c -> new HibernateOperandBinder(c, version.startsWith("6."), version));
    }

    /** Whether {@code typedBy} is a path, or an aggregate over one, possibly {@code DISTINCT} (D-170). */
    @Override
    public boolean typesBy(Expression<?> typedBy) {
        if (typedBy instanceof SqmPath<?>) {
            return true;
        }
        if (!(typedBy instanceof SqmAggregateFunction<?> && typedBy instanceof SqmFunction<?> aggregate)
                || aggregate.getArguments().size() != 1) {
            return false;
        }
        SqmTypedNode<?> argument = aggregate.getArguments().get(0);
        SqmTypedNode<?> operand = argument instanceof SqmDistinct<?> distinct ? distinct.getExpression() : argument;
        return operand instanceof SqmPath<?>;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
        if (!(typedBy instanceof SqmExpression<?> expression) || !typesBy(typedBy)) {
            throw new IllegalStateException("Hibernate " + version + " types an operator's value by a path or an"
                    + " aggregate over one, not " + describe(typedBy));
        }
        Object bindType = bindType(expression.getNodeType());
        try {
            // A new parameter on every call: the engine sets one value on each per statement (R-VND-24).
            return (ParameterExpression<T>) parameter.newInstance(null, bindType, false, expression.nodeBuilder());
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The anticipated type for {@code nodeType}, the column's node type: a basic type this line's constructor takes,
     * reached through {@code getSqmPathType()} first on 6.6. Anything else throws, which the engine reports as
     * {@code MQ1502} naming the operator and the column (R-VND-24).
     */
    Object bindType(Object nodeType) {
        Object type = unwrapsPathSource ? pathType(nodeType) : nodeType;
        if (!(type instanceof BasicType<?>) || !parameter.getParameterTypes()[1].isInstance(type)) {
            throw new IllegalStateException("Hibernate " + version + " types an operator's value by a basic node type"
                    + (unwrapsPathSource ? " or a path source of one" : "") + ", not " + describe(nodeType)
                    + (type == nodeType ? "" : " of " + describe(type)));
        }
        return type;
    }

    /** {@code nodeType}'s {@code getSqmPathType()} where it has one (6.6's path source), else {@code nodeType}. */
    private static Object pathType(Object nodeType) {
        Method unwrap = nodeType == null ? null : Arrays.stream(nodeType.getClass().getMethods())
                .filter(m -> m.getName().equals("getSqmPathType") && m.getParameterCount() == 0)
                .findFirst()
                .orElse(null);
        if (unwrap == null) {
            return nodeType;
        }
        try {
            return unwrap.invoke(nodeType);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String describe(Object node) {
        return node == null ? "null" : node.getClass().getName();
    }
}
