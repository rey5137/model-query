package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
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
import java.util.Optional;

/**
 * The keyset of an ungrouped query: its order closed by the primary key, the cursor read from a row, and the
 * predicate selecting the rows after a cursor. Immutable.
 *
 * @param <M> the model
 * @implSpec R-PAG-04, R-PAG-05, R-PAG-06, R-PRF-09, R-COL-13
 */
final class Keyset<M> {

    /**
     * One keyset column. {@code nulls} is where its NULLs sort: the explicit precedence, else for a non-key column
     * the provider's configured default or the database's default in its direction, and {@code DEFAULT} where
     * neither is known, or for a primary-key column, which R-PAG-03 already keeps non-NULL. {@code refuseNull} when
     * a NULL in it throws {@code MQ2202}.
     */
    private record Key<M>(OrderField<M, ?> order, NullPrecedence nulls, boolean refuseNull) {}

    private final ModelQuery<?, ?, M> query;
    private final List<Key<M>> keys;

    private Keyset(ModelQuery<?, ?, M> query, List<Key<M>> keys) {
        this.query = query;
        this.keys = List.copyOf(keys);
    }

    /**
     * The query's order, then every primary-key column it does not already order by, in the direction of its last
     * order column, so no two rows share a cursor (R-PAG-04). A {@code DEFAULT}-precedence column's NULLs sort where
     * {@code providerNulls} puts them in both directions when the provider is configured with a default null ordering,
     * else where the profile's {@code defaultOrdering} puts them in its direction; {@code nullKeys} says whether it
     * pages its NULLs there (R-PAG-05, R-COL-13, D-35, D-36).
     */
    static <M> Keyset<M> of(ModelQuery<?, ?, M> q, PrimaryKey<M, ?> key, NullOrdering defaultOrdering,
            Optional<NullPrecedence> providerNulls, KeysetNullKeys nullKeys) {
        List<Key<M>> keys = new ArrayList<>();
        boolean ascending = true;
        for (OrderField<M, ?> order : q.orderBy()) {
            keys.add(key(order, key.columns().contains(order.column()), defaultOrdering, providerNulls, nullKeys));
            ascending = order.ascending();
        }
        for (ColumnField<M, ?, ?> column : key.columns()) {
            if (q.orderBy().stream().noneMatch(order -> order.column().equals(column))) {
                keys.add(new Key<>(new OrderField<>(column, ascending, NullPrecedence.DEFAULT),
                        NullPrecedence.DEFAULT, false));
            }
        }
        return new Keyset<>(q, keys);
    }

    private static <M> Key<M> key(OrderField<M, ?> order, boolean keyColumn, NullOrdering defaultOrdering,
            Optional<NullPrecedence> providerNulls, KeysetNullKeys nullKeys) {
        if (keyColumn || order.nulls() != NullPrecedence.DEFAULT) {
            return new Key<>(order, order.nulls(), false);
        }
        // The provider's configured default sorts a bare order the same way in both directions; the database's
        // default is ascending, and descending order reverses it.
        NullPrecedence nulls = providerNulls.orElseGet(() -> defaultOrdering == NullOrdering.UNKNOWN
                ? NullPrecedence.DEFAULT
                : (defaultOrdering == NullOrdering.NULLS_FIRST) == order.ascending()
                        ? NullPrecedence.FIRST : NullPrecedence.LAST);
        return new Key<>(order, nulls, nullKeys == KeysetNullKeys.FAIL || nulls == NullPrecedence.DEFAULT);
    }

    /** Appends the primary-key tie-breakers to {@code built}'s order, which already holds the query's own. */
    void appendOrder(BuiltQuery<M> built, CriteriaBuilder cb) {
        int ordered = query.orderBy().size();
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
     * The row's keyset values, read from the {@code Row} as attribute values, before any converter (R-COL-11).
     *
     * @throws ModelQueryExecutionException {@code MQ2202} when a column without explicit null precedence is NULL,
     *     unless it pages its NULLs by the database's known default (R-PAG-05)
     */
    Object[] cursor(Row row) {
        Object[] values = new Object[keys.size()];
        for (int i = 0; i < values.length; i++) {
            Key<M> key = keys.get(i);
            OrderField<M, ?> order = key.order();
            values[i] = row.raw(order.column());
            if (values[i] == null && key.refuseNull()) {
                throw new ModelQueryExecutionException(MqCode.MQ2202, query + ": keyset column "
                        + order.column().name() + " is null in an exported row"
                        + (key.nulls() == NullPrecedence.DEFAULT ? " and the database's null ordering is unknown" : "")
                        + "; order it with nullsFirst() or nullsLast() so the next page can be found after it");
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
    @SuppressWarnings("rawtypes")
    private static Predicate beyond(Key<?> key, Expression<?> column, Object value, CriteriaBuilder cb) {
        NullPrecedence nulls = key.nulls();
        if (value == null) {
            // Only a known precedence reaches here (cursor() refuses the rest): under FIRST every value follows a
            // NULL, under LAST nothing does.
            return nulls == NullPrecedence.FIRST ? cb.isNotNull(column) : null;
        }
        Predicate past = key.order().ascending()
                ? cb.greaterThan(comparable(column), (Comparable) value)
                : cb.lessThan(comparable(column), (Comparable) value);
        // Under LAST every NULL follows every value. A column that refuses its NULLs keeps the branch whatever the
        // ordering, known or not: it makes sure a NULL is read, and so refused, wherever the database really sorts
        // it, instead of being skipped silently when the reported ordering is wrong (INV-5, D-30, D-35).
        boolean nullsFollow = nulls == NullPrecedence.LAST || key.refuseNull();
        return nullsFollow ? cb.or(past, cb.isNull(column)) : past;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Expression<Comparable> comparable(Expression<?> column) {
        return (Expression<Comparable>) column;
    }
}
