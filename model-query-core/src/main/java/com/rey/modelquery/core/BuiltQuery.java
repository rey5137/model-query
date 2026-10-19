package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Selection;
import java.util.List;
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

    /**
     * The alias of the hidden root-id anchor a statement with a treated node selects last when its model selects no
     * untreated root id (R-COL-35): outside D-146's {@code mq_<property>} space, never in the row a mapper reads.
     */
    static final String ROOT_ID_ANCHOR = "mq_0id";

    /** The alias of a recursive source's depth check, a column of the statement's selection (R-SRC-07). */
    public static final String RECURSION_DEPTH = "mqRecursionDepth";

    /** The alias of a recursive source's cycle check, a column of the statement's selection (R-SRC-07). */
    public static final String RECURSION_CYCLE = "mqRecursionCycle";

    /** The alias of a tree source's duplicate-key check, read after depth and cycle (R-SRC-14). */
    @Incubating
    public static final String RECURSION_DUPLICATE_KEY = "mqRecursionDuplicateKey";

    private final CriteriaQuery<Tuple> query;
    private final JoinContext joins;
    private final RowSelection selection;
    private final Function<Row, M> mapping;
    /** The parent's key read on the query's root, in a {@code through} child query; else {@code null}. */
    private final Expression<?> parentKey;
    /** The checks of a recursive source, aliased; empty for a query that reads no recursive source (R-SRC-07). */
    private final List<Selection<?>> recursionChecks;
    /** The source's {@code maxDepth}, or 0 for a query that reads no recursive source (R-SRC-07). */
    private final int maxDepth;
    private final List<String> sourceKeyColumns;

    BuiltQuery(CriteriaQuery<Tuple> query, JoinContext joins, RowSelection selection, Function<Row, M> mapping) {
        this(query, joins, selection, mapping, null, List.of(), 0, List.of());
    }

    BuiltQuery(CriteriaQuery<Tuple> query, JoinContext joins, RowSelection selection, Function<Row, M> mapping,
            Expression<?> parentKey, List<Selection<?>> recursionChecks, int maxDepth, List<String> sourceKeyColumns) {
        this.query = query;
        this.joins = joins;
        this.selection = selection;
        this.mapping = mapping;
        this.parentKey = parentKey;
        this.recursionChecks = List.copyOf(recursionChecks);
        this.maxDepth = maxDepth;
        this.sourceKeyColumns = List.copyOf(sourceKeyColumns);
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

    /**
     * The checks of a recursive source, to append to a statement this query is the model of — a statement that
     * replaces the selection, as the count does; empty when the source does not recurse (R-SRC-07). Each is a scalar
     * sub-select over the source's CTE, read from the statement's first row by its alias
     * ({@link #RECURSION_DEPTH}, {@link #RECURSION_CYCLE}, and for a tree {@link #RECURSION_DUPLICATE_KEY}).
     */
    @EngineFacing
    public List<Selection<?>> recursionChecks() {
        return recursionChecks;
    }

    /** The source key's columns, in key order, for {@code MQ2703}; empty without a source (R-SRC-14). */
    @EngineFacing
    @Incubating
    public List<String> sourceKeyColumns() {
        return sourceKeyColumns;
    }

    /** The deepest level the source returns, the seeds being level 0; 0 when the source does not recurse (R-SRC-07). */
    @EngineFacing
    public int sourceMaxDepth() {
        return maxDepth;
    }

    /** Maps one tuple to a model: the {@code RowMapper}, then {@code afterMap} and any finisher. */
    public M map(Tuple tuple) {
        return map(selection.row(Objects.requireNonNull(tuple, "tuple")));
    }

    /** Maps one row, built from a tuple of {@link #query()}, to a model, as {@link #map(Tuple)}. */
    public M map(Row row) {
        return mapping.apply(row);
    }
}
