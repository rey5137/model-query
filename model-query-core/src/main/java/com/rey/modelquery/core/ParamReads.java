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
    /**
     * Each param read anything but bare on one side of a {@code compare} against a column in a {@code where}
     * condition under {@code AND} and {@code OR} alone, with the site that first read it so (R-FCH-18).
     */
    private final Map<QueryParam<?>, String> otherReads = new LinkedHashMap<>();
    /** Each param read bare in such a {@code compare}, with the operators as {@code column op param} (R-FCH-18). */
    private final Map<QueryParam<?>, Map<Op, String>> compares = new LinkedHashMap<>();
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
        requireBindable("bind(" + param + (value == null ? ", null)" : ", ...)"), param, value);
        var bound = new LinkedHashMap<>(bindings);
        bound.put(param, value);
        return Collections.unmodifiableMap(bound);
    }

    /**
     * {@code MQ1502}, naming {@code site}, when {@code value} is {@code null}, not an instance of {@code param}'s type,
     * or of a type no expression binds (R-COL-18, R-COL-40).
     */
    static void requireBindable(String site, QueryParam<?> param, Object value) {
        if (value == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, site + ": a param binds a non-null value; an "
                    + "expression has no absent form, so bind a default");
        }
        if (!param.type().isInstance(value) || Expr.unbindable(value)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, site + ": the value is a "
                    + value.getClass().getSimpleName() + ", which " + (param.type().isInstance(value)
                            ? "an expression cannot bind" : "is not a " + param.type().getSimpleName()));
        }
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
        condition(condition, false);
    }

    /**
     * As {@link #condition(Condition)}, for a condition of the query's {@code where}, whose bare compares against a
     * column {@link #checkKeyGroups} tells from other reads.
     */
    void whereCondition(Condition condition) {
        condition(condition, true);
    }

    /** {@code direct}: the condition sits in the {@code where} under {@code AND} and {@code OR} alone. */
    private void condition(Condition condition, boolean direct) {
        at(String.valueOf(condition), () -> {
            if (direct && condition.kind() == Condition.Kind.COMPARE && compare(condition)) {
                return;
            }
            condition.column().ifPresent(this::field);
            condition.right().ifPresent(this::field);
            condition.operands().forEach(this::field);
            // A NOT turns a bound around and an EXISTS bounds another row, so only AND and OR keep a compare direct.
            boolean keep = direct && (condition.kind() == Condition.Kind.AND || condition.kind() == Condition.Kind.OR);
            condition.children().forEach(child -> condition(child, keep));
            condition.subSelect().ifPresent(this::subSelect);
        });
    }

    /** Records {@code compare}, when it compares a bare param with a column, as {@code column op param}. */
    private boolean compare(Condition compare) {
        SelectField<?, ?> left = compare.column().orElseThrow();
        SelectField<?, ?> right = compare.right().orElseThrow();
        Op op = compare.op().orElseThrow();
        QueryParam<?> param;
        if (left instanceof ColumnField<?, ?, ?> && right instanceof ExpressionField<?, ?> expression
                && expression.bareParam() != null) {
            param = expression.bareParam();
        } else if (right instanceof ColumnField<?, ?, ?> && left instanceof ExpressionField<?, ?> expression
                && expression.bareParam() != null) {
            param = expression.bareParam();
            op = switch (op) {
                case LT -> Op.GT;
                case LTE -> Op.GTE;
                case GT -> Op.LT;
                case GTE -> Op.LTE;
                default -> op;
            };
        } else {
            return false;
        }
        reads.putIfAbsent(param, site);
        compares.computeIfAbsent(param, read -> new LinkedHashMap<>()).putIfAbsent(op, site);
        return true;
    }

    void correlated(ExpressionField.CorrelatedValue value) {
        subSelect(value.rows());
        value.conditions().forEach(this::condition);
        value.orders().forEach(order -> field(order.column()));
    }

    void param(QueryParam<?> param) {
        reads.putIfAbsent(param, site);
        otherReads.putIfAbsent(param, site);
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

    /**
     * {@code MQ1708}, naming {@code owner}, when a param {@code groups} binds is also in {@code bindings} or is read by
     * nothing, or, on a child whose key isn't unique per row ({@code toMany}) or under {@code groupWhen}, is read other
     * than as the child allows: on {@code toMany}, only as a column's lower bound or, without {@code groupWhen}, with
     * {@code EQ}; under {@code groupWhen}, only as a {@code GT}, {@code GTE}, {@code LT} or {@code LTE} bound of a
     * column (R-FCH-18, R-EXE-18, D-206, D-208). A query's own key groups, whose key is unique per row, pass
     * {@code toMany} false. A read counts only bare on one side of a {@code compare} of the {@code where},
     * under {@code AND} and {@code OR} alone; any other read is refused under either restriction.
     */
    void checkKeyGroups(String owner, KeyGroups<?, ?> groups, Map<QueryParam<?>, Object> bindings, boolean toMany) {
        boolean ruled = groups.rule().isPresent();
        String why = (toMany ? "; on a to-many child, a group param may be read only as a column's "
                + "lower bound (GT or GTE with the param on the right, LT or LTE with it on the left)"
                + (ruled ? "" : " or with EQ") + ", since an upper bound would cut a parent's list" : "")
                + (ruled ? "; under groupWhen, a group param may be read only as a GT, GTE, LT or LTE bound of a "
                        + "column, since one catch-all value can't stand for every group of an EQ" : "");
        for (QueryParam<?> param : groups.params()) {
            if (bindings.containsKey(param)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1708, owner + ": the param " + param + " is bound "
                        + "both by bind(...) and by the key groups; bind it once");
            }
            if (!reads.containsKey(param)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1708, owner + ": the key groups bind the param "
                        + param + ", which nothing reads; a group-bound param exists only to filter, so read it in "
                        + "the where(...)");
            }
            if (!toMany && !ruled) {
                continue;
            }
            String other = otherReads.get(param);
            if (other != null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1708, owner + ": " + other + " reads the group param "
                        + param + " other than bare on one side of a compare(...) against a column" + why);
            }
            compares.getOrDefault(param, Map.of()).forEach((op, at) -> {
                boolean allowed = op == Op.GT || op == Op.GTE || (op == Op.EQ ? toMany && !ruled
                        : (op == Op.LT || op == Op.LTE) && !toMany);
                if (!allowed) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1708, owner + ": " + at + " reads the group "
                            + "param " + param + " as a column " + op + " param" + why);
                }
            });
        }
    }

    /** {@code MQ1512} when a write that reads no query reads a param, which nothing binds (R-COL-40). */
    void refuseInWrite() {
        reads.forEach((param, at) -> {
            throw new ModelQueryDefinitionException(MqCode.MQ1512, owner + ": " + at + " reads the param " + param
                    + ", and a write binds no param; pass the value to the write instead");
        });
    }
}
