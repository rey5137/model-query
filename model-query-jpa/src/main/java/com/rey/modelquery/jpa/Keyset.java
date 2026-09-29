package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

/**
 * The keyset of an ungrouped query: its order closed by the primary key, the cursor read from a row, and the
 * predicate selecting the rows after a cursor. Immutable.
 *
 * @param <M> the model
 * @implSpec R-PAG-04, R-PAG-05, R-PAG-06, R-PRF-09
 */
final class Keyset<M> {

    /** One keyset column; {@code keyColumn} for a primary-key column, which R-PAG-03 already keeps non-NULL. */
    private record Key<M>(OrderField<M, ?> order, boolean keyColumn) {}

    private final ModelQuery<?, ?, M> query;
    private final List<Key<M>> keys;
    private final int ordered;

    private Keyset(ModelQuery<?, ?, M> query, List<Key<M>> keys) {
        this.query = query;
        this.keys = List.copyOf(keys);
        this.ordered = query.orderBy().size();
    }

    /**
     * The query's order, then every primary-key column it does not already order by, in the direction of its last
     * order column, so no two rows share a cursor (R-PAG-04).
     */
    static <M> Keyset<M> of(ModelQuery<?, ?, M> q, PrimaryKey<M, ?> key) {
        List<Key<M>> keys = new ArrayList<>();
        boolean ascending = true;
        for (OrderField<M, ?> order : q.orderBy()) {
            keys.add(new Key<>(order, key.columns().contains(order.column())));
            ascending = order.ascending();
        }
        for (ColumnField<M, ?, ?> column : key.columns()) {
            if (q.orderBy().stream().noneMatch(order -> order.column().equals(column))) {
                keys.add(new Key<>(new OrderField<>(column, ascending, NullPrecedence.DEFAULT), true));
            }
        }
        return new Keyset<>(q, keys);
    }

    /** Appends the primary-key tie-breakers to {@code built}'s order, which already holds the query's own. */
    void appendOrder(BuiltQuery<M> built, CriteriaBuilder cb) {
        if (keys.size() == ordered) {
            return;
        }
        List<Order> orders = new ArrayList<>(built.query().getOrderList());
        for (Key<M> key : keys.subList(ordered, keys.size())) {
            Expression<?> column = key.order().column().expression(built.joins());
            orders.add(key.order().ascending() ? cb.asc(column) : cb.desc(column));
        }
        built.query().orderBy(orders);
    }

    /**
     * The row's keyset values, read from the {@code Row} (R-COL-11).
     *
     * @throws ModelQueryExecutionException {@code MQ2202} when a column without explicit null precedence is NULL
     */
    Object[] cursor(Row row) {
        Object[] values = new Object[keys.size()];
        for (int i = 0; i < values.length; i++) {
            OrderField<M, ?> order = keys.get(i).order();
            values[i] = row.get(order.column());
            if (values[i] == null && order.nulls() == NullPrecedence.DEFAULT && !keys.get(i).keyColumn()) {
                // Where the database sorts this NULL is its own choice, so no predicate can page past it portably.
                throw new ModelQueryExecutionException(MqCode.MQ2202, query + ": keyset column "
                        + order.column().name() + " is null in an exported row; order it with nullsFirst() or "
                        + "nullsLast() so the next page can be found after it");
            }
        }
        return values;
    }

    /**
     * The rows after {@code cursor}: an OR over the keys, each branch holding the earlier keys equal to the cursor's
     * and its own key beyond the cursor's value (R-PAG-06, vendor/41 §5). NULL branches follow R-PRF-09.
     */
    Predicate after(Object[] cursor, JoinContext joins, CriteriaBuilder cb) {
        List<Predicate> branches = new ArrayList<>();
        List<Predicate> equal = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            Key<M> key = keys.get(i);
            Expression<?> column = key.order().column().expression(joins);
            Predicate beyond = beyond(key, column, cursor[i], cb);
            if (beyond != null) {
                List<Predicate> branch = new ArrayList<>(equal);
                branch.add(beyond);
                branches.add(branch.size() == 1 ? beyond : cb.and(branch.toArray(Predicate[]::new)));
            }
            equal.add(cursor[i] == null ? cb.isNull(column) : cb.equal(column, cursor[i]));
        }
        return cb.or(branches.toArray(Predicate[]::new));
    }

    /** The values of {@code key}'s column that sort after {@code value}, or {@code null} when none does. */
    private static Predicate beyond(Key<?> key, Expression<?> column, Object value, CriteriaBuilder cb) {
        NullPrecedence nulls = key.order().nulls();
        if (value == null) {
            // Only an explicit precedence reaches here (cursor() refuses the rest): under FIRST every value follows
            // a NULL, under LAST nothing does.
            return nulls == NullPrecedence.FIRST ? cb.isNotNull(column) : null;
        }
        Predicate past = key.order().ascending() ? greaterThan(cb, column, value) : lessThan(cb, column, value);
        // Under LAST every NULL follows every value. Under DEFAULT a NULL is refused when read, and this branch makes
        // sure it is read wherever the database sorts it, instead of being skipped silently (INV-5, D-30).
        boolean nullsFollow = nulls == NullPrecedence.LAST || nulls == NullPrecedence.DEFAULT && !key.keyColumn();
        return nullsFollow ? cb.or(past, cb.isNull(column)) : past;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate greaterThan(CriteriaBuilder cb, Expression<?> column, Object value) {
        return cb.greaterThan((Expression<Comparable>) column, (Comparable) value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate lessThan(CriteriaBuilder cb, Expression<?> column, Object value) {
        return cb.lessThan((Expression<Comparable>) column, (Comparable) value);
    }
}
