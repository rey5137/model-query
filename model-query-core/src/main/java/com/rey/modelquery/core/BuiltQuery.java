package com.rey.modelquery.core;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.Objects;
import java.util.function.Function;

/**
 * One statement of a {@link ModelQuery} resolved against a {@code CriteriaBuilder}: the Criteria query, the
 * {@link JoinContext} it was built with, and what is needed to turn its tuples into models. Created per build and
 * never shared between threads (INV-9).
 *
 * @param <M> the model type
 * @implSpec R-QRY-01
 */
@Incubating
public final class BuiltQuery<M> {

    private final CriteriaQuery<Tuple> query;
    private final JoinContext joins;
    private final RowSelection selection;
    private final Function<Row, M> mapping;

    BuiltQuery(CriteriaQuery<Tuple> query, JoinContext joins, RowSelection selection, Function<Row, M> mapping) {
        this.query = query;
        this.joins = joins;
        this.selection = selection;
        this.mapping = mapping;
    }

    /** The Criteria query, selecting {@link Tuple}s. */
    public CriteriaQuery<Tuple> query() {
        return query;
    }

    /** The join context of this build, for adding predicates that reuse the query's joins. */
    public JoinContext joins() {
        return joins;
    }

    /** The row view over the tuples of {@link #query()}. */
    public RowSelection selection() {
        return selection;
    }

    /** Maps one tuple to a model: the {@code RowMapper}, then {@code afterMap} and any finisher. */
    public M map(Tuple tuple) {
        return mapping.apply(selection.row(Objects.requireNonNull(tuple, "tuple")));
    }
}
