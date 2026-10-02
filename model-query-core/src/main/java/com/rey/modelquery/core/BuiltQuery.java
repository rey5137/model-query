package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
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
@EngineFacing
@Incubating
public final class BuiltQuery<M> {

    /** The alias of a {@code through} child query's parent key, selected after the model's columns (D-100). */
    static final String PARENT_KEY = "parent_key";

    private final CriteriaQuery<Tuple> query;
    private final JoinContext joins;
    private final RowSelection selection;
    private final Function<Row, M> mapping;
    /** The parent's key read on the query's root, in a {@code through} child query; else {@code null}. */
    private final Expression<?> parentKey;

    BuiltQuery(CriteriaQuery<Tuple> query, JoinContext joins, RowSelection selection, Function<Row, M> mapping) {
        this(query, joins, selection, mapping, null);
    }

    BuiltQuery(CriteriaQuery<Tuple> query, JoinContext joins, RowSelection selection, Function<Row, M> mapping,
            Expression<?> parentKey) {
        this.query = query;
        this.joins = joins;
        this.selection = selection;
        this.mapping = mapping;
        this.parentKey = parentKey;
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

    /** The parent key of a {@code through} child query, or {@code null}. */
    Expression<?> parentKey() {
        return parentKey;
    }

    /** The parent key {@code tuple} holds, of a {@code through} child query. */
    Object parentKey(Tuple tuple) {
        return Objects.requireNonNull(tuple, "tuple").get(PARENT_KEY);
    }

    /** Maps one tuple to a model: the {@code RowMapper}, then {@code afterMap} and any finisher. */
    public M map(Tuple tuple) {
        return mapping.apply(selection.row(Objects.requireNonNull(tuple, "tuple")));
    }
}
