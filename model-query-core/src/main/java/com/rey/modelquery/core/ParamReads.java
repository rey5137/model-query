package com.rey.modelquery.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The {@link QueryParam}s a query or a write reads, at any depth of its fields and conditions, each with the first
 * site that reads it, for the {@code MQ1512} checks of a build (R-COL-40, D-198). A source's body is its own scope and
 * is not walked from the query that reads the source.
 *
 * @implSpec api/10 R-COL-40
 */
final class ParamReads {

    private final String owner;
    /** Each param read, by identity (a param does not override {@code equals}), with the site that first read it. */
    private final Map<QueryParam<?>, String> reads = new LinkedHashMap<>();
    /** The top-level field or condition being walked, which a message names. */
    private String site;

    ParamReads(String owner) {
        this.owner = owner;
    }

    /**
     * {@code bindings} with {@code param} bound to {@code value}, replacing an earlier binding of it (R-COL-40).
     *
     * @throws ModelQueryDefinitionException {@code MQ1502} when {@code value} is {@code null}, not an instance of the
     *     param's type, or of a type no expression binds (R-COL-18)
     */
    static <C> Map<QueryParam<?>, Object> bind(Map<QueryParam<?>, Object> bindings, QueryParam<C> param, C value) {
        Objects.requireNonNull(param, "param");
        if (value == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, "bind(" + param + ", null): a param binds a "
                    + "non-null value; an expression has no absent form, so bind a default");
        }
        if (!param.type().isInstance(value) || Expr.unbindable(value)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, "bind(" + param + ", ...): the value is a "
                    + value.getClass().getSimpleName() + ", which " + (param.type().isInstance(value)
                            ? "an expression cannot bind" : "is not a " + param.type().getSimpleName()));
        }
        var bound = new LinkedHashMap<>(bindings);
        bound.put(param, value);
        return Collections.unmodifiableMap(bound);
    }


    void field(SelectField<?, ?> field) {
        if (field == null) {
            return;
        }
        at(field.name(), () -> {
            if (field instanceof ExpressionField<?, ?> expression) {
                expression.readParams(this);
            } else if (field instanceof AggregateField<?, ?> aggregate) {
                aggregate.readParams(this);
            } else if (field instanceof WindowField<?, ?> window) {
                window.readFields().forEach(this::field);
            }
        });
    }

    void condition(Condition condition) {
        at(String.valueOf(condition), () -> {
            condition.column().ifPresent(this::field);
            condition.right().ifPresent(this::field);
            condition.operands().forEach(this::field);
            condition.children().forEach(this::condition);
            condition.subSelect().ifPresent(this::subSelect);
        });
    }

    void correlated(ExpressionField.CorrelatedValue value) {
        subSelect(value.rows());
        value.conditions().forEach(this::condition);
        value.orders().forEach(order -> field(order.column()));
    }

    void param(QueryParam<?> param) {
        reads.putIfAbsent(param, site);
    }

    private void subSelect(SubSelect<?, ?> rows) {
        field(rows.column());
        rows.conditions().forEach(this::condition);
    }

    private void at(String label, Runnable walk) {
        if (site != null) {
            walk.run();
            return;
        }
        site = label;
        try {
            walk.run();
        } finally {
            site = null;
        }
    }

    /**
     * {@code MQ1512} when two params read share a name, or a param read is not in {@code bindings}: checked at
     * {@code build()}, before any statement (R-COL-40).
     */
    void checkBound(Map<QueryParam<?>, Object> bindings) {
        var names = new HashMap<String, QueryParam<?>>();
        reads.forEach((param, at) -> {
            QueryParam<?> same = names.putIfAbsent(param.name(), param);
            if (same != null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1512, owner + ": reads two params named '"
                        + param.name() + "' (" + same + " and " + param + ", in " + at + "); a param is equal by "
                        + "identity, so give each its own name or read one constant");
            }
            if (!bindings.containsKey(param)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1512, owner + ": " + at + " reads the param "
                        + param + ", which the query does not bind; bind it with bind(param, value)");
            }
        });
    }

    /** {@code MQ1512} when a write that reads no query reads a param, which nothing binds (R-COL-40). */
    void refuseInWrite() {
        reads.forEach((param, at) -> {
            throw new ModelQueryDefinitionException(MqCode.MQ1512, owner + ": " + at + " reads the param " + param
                    + ", and a write binds no param; pass the value to the write instead");
        });
    }
}
