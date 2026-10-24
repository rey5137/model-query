package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a {@link ModelQuery} filters on: the top-level conditions of its {@code where}, {@code having} and join
 * filters, each ANDed, in the order they were added. Order stays {@link ModelQuery#orderBy()}.
 * Only a query builds one (D-101);
 * immutable, and equal for two queries built from equal calls. {@link #toString()} shows each value as {@code ?}.
 *
 * @implSpec api/16 §1, R-INS-04, R-INS-05, D-101
 */
@Incubating
public final class QueryConditions {

    private final List<Condition> where;
    private final List<Condition> having;
    private final Map<TableField<?, ?>, List<Condition>> joinFilters;

    QueryConditions(List<Condition> where, List<Condition> having, Map<TableField<?, ?>, List<Filter>> joinFilters) {
        this.where = List.copyOf(where);
        this.having = List.copyOf(having);
        var recorded = new LinkedHashMap<TableField<?, ?>, List<Condition>>();
        joinFilters.forEach((table, terms) -> recorded.put(table, ConditionGroup.conditions(terms)));
        this.joinFilters = Collections.unmodifiableMap(recorded);
    }

    /** The {@code where} conditions, ANDed, as a list that throws on mutation. */
    public List<Condition> where() {
        return where;
    }

    /** The {@code having} conditions, ANDed, as a list that throws on mutation; empty for an ungrouped query. */
    public List<Condition> having() {
        return having;
    }

    /** The filter in force for each join, keyed as its table is, with its conditions ANDed (R-COL-39). */
    public Map<TableField<?, ?>, List<Condition>> joinFilters() {
        return joinFilters;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof QueryConditions other && where.equals(other.where) && having.equals(other.having)
                && joinFilters.equals(other.joinFilters);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * where.hashCode() + having.hashCode()) + joinFilters.hashCode();
    }

    /** Each condition's kind and column, each value shown as {@code ?}; the format is not API. */
    @Override
    public String toString() {
        return "where " + where + ", having " + having + (joinFilters.isEmpty() ? "" : ", joinFilters " + joinFilters);
    }
}
