package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;

/**
 * What a {@link ModelQuery} filters on: the top-level conditions of its {@code where} and of its {@code having}, each
 * ANDed, in the order they were added. Order stays {@link ModelQuery#orderBy()}. Only a query builds one (D-101);
 * immutable, and equal for two queries built from equal calls. {@link #toString()} shows each value as {@code ?}.
 *
 * @implSpec api/16 §1, R-INS-04, R-INS-05, D-101
 */
@Incubating
public final class QueryConditions {

    private final List<Condition> where;
    private final List<Condition> having;

    QueryConditions(List<Condition> where, List<Condition> having) {
        this.where = List.copyOf(where);
        this.having = List.copyOf(having);
    }

    /** The {@code where} conditions, ANDed, as a list that throws on mutation. */
    public List<Condition> where() {
        return where;
    }

    /** The {@code having} conditions, ANDed, as a list that throws on mutation; empty for an ungrouped query. */
    public List<Condition> having() {
        return having;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof QueryConditions other && where.equals(other.where) && having.equals(other.having);
    }

    @Override
    public int hashCode() {
        return 31 * where.hashCode() + having.hashCode();
    }

    /** Each condition's kind and column, each value shown as {@code ?}; the format is not API. */
    @Override
    public String toString() {
        return "where " + where + ", having " + having;
    }
}
