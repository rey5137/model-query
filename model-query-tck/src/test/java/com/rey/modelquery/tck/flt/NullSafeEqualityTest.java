package com.rey.modelquery.tck.flt;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.hibernate.SessionFactory;

/**
 * Null-safe equality on every target: the value form, the two-operand form in {@code where}, under {@code not} and in a
 * correlated {@code exists}, and the native form where the vendor has it (spec api/12 R-FLT-29, vendor/40 R-VND-29,
 * R-VND-30). A third of the orders have a referrer, so {@code referrer_id} is NULL on the rest.
 */
class NullSafeEqualityTest {

    record O(Long id) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER = TableField.join(ORDERS, "referrer", LEFT);

    private static final ColumnField<O, OrderEntity, Long> ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    private static final ColumnField<O, OrderEntity, Long> REFERRER_ID =
            ColumnField.of(O.class, ORDERS, "referrerId", Long.class);
    private static final ColumnField<O, CustomerEntity, Long> CUSTOMER_KEY =
            ColumnField.of(O.class, CUSTOMER, "id", Long.class);
    private static final ColumnField<O, CustomerEntity, Long> REFERRER_KEY =
            ColumnField.of(O.class, REFERRER, "id", Long.class);

    private static final ModelQuery.Builder<OrderEntity, Object, O> QUERY =
            ModelQuery.builder(ORDERS, row -> new O(row.get(ID))).select(SelectSet.of(ID)).orderBy(ID.asc());

    /** Every order's id, for a sub-select over the outer root's own entity (R-FLT-15). */
    private static final SubSelect<O, Long> ORDER_IDS = SubSelect.of(ID);

    // ---- AC-FLT-37

    @TckTest
    void ac_flt_37_a_value_resolves_to_is_null_or_an_equality_that_is_never_null(TckDatabase db) {
        long referrer = scalar(db, "select min(referrer_id) from orders");
        String same = "referrer_id = " + referrer;
        String other = "referrer_id is null or referrer_id <> " + referrer;
        Long none = null;
        List<Case> cases = List.of(
                new Case(f -> f.notDistinctFrom(REFERRER_ID, none), "referrer_id is null"),
                new Case(f -> f.distinctFrom(REFERRER_ID, none), "referrer_id is not null"),
                new Case(f -> f.notDistinctFrom(REFERRER_ID, referrer), same),
                new Case(f -> f.distinctFrom(REFERRER_ID, referrer), other),
                // Under not(...) a NULL column stays two-valued, where not(eq(...)) would drop it (R-FLT-05, INV-5).
                new Case(f -> f.not(g -> g.notDistinctFrom(REFERRER_ID, referrer)), other),
                new Case(f -> f.not(g -> g.distinctFrom(REFERRER_ID, referrer)), same),
                new Case(f -> f.not(g -> g.notDistinctFrom(REFERRER_ID, none)), "referrer_id is not null"),
                new Case(f -> f.not(g -> g.distinctFrom(REFERRER_ID, none)), "referrer_id is null"));

        List<String> sql = SqlSnapshots.assertMatches(db, "flt-37-null-safe-value", ds -> executor(ds, executor ->
                cases.forEach(c -> ids(executor, c.where()))));
        executor(db, executor -> cases.forEach(c -> assertThat(ids(executor, c.where()))
                .as(c.oracle()).isEqualTo(oracle(db, "select id from orders where " + c.oracle() + " order by id"))));

        assertThat(oracle(db, "select id from orders where " + same)).isNotEmpty();
        assertThat(oracle(db, "select id from orders where referrer_id is null")).isNotEmpty();
        // A value operand never renders the vendor's native operator: its is-not-null form keeps the index.
        assertThat(String.join("\n", sql).toLowerCase(Locale.ROOT)).doesNotContain("distinct from")
                .doesNotContain("<=>");
    }

    // ---- AC-FLT-38

    @TckTest
    void ac_flt_38_two_operands_match_null_to_null_and_one_sided_null_is_distinct(TckDatabase db) {
        // The LEFT-joined referrer's key is NULL exactly where referrer_id is, and equal otherwise: every row is
        // not distinct, none is distinct, though a plain referrer_id = r.id keeps a third of them.
        List<Case> sameKey = List.of(
                new Case(f -> f.notDistinctFromColumn(REFERRER_ID, REFERRER_KEY), "true"),
                new Case(f -> f.distinctFromColumn(REFERRER_ID, REFERRER_KEY), "false"),
                new Case(f -> f.not(g -> g.notDistinctFromColumn(REFERRER_ID, REFERRER_KEY)), "false"),
                new Case(f -> f.not(g -> g.distinctFromColumn(REFERRER_ID, REFERRER_KEY)), "true"));
        // The customer key is never NULL, so a NULL referrer_id is distinct from it.
        String equal = "o.referrer_id = c.id";
        String different = "o.referrer_id is null or o.referrer_id <> c.id";
        List<Case> oneSided = List.of(
                new Case(f -> f.notDistinctFromColumn(REFERRER_ID, CUSTOMER_KEY), equal),
                new Case(f -> f.distinctFromColumn(REFERRER_ID, CUSTOMER_KEY), different),
                new Case(f -> f.not(g -> g.notDistinctFromColumn(REFERRER_ID, CUSTOMER_KEY)), different),
                new Case(f -> f.not(g -> g.distinctFromColumn(REFERRER_ID, CUSTOMER_KEY)), equal));
        // The orders sharing a referrer with another order, two NULL referrers sharing none.
        String shares = "exists (select 1 from orders p where p.id <> o.id and (p.referrer_id = o.referrer_id "
                + "or (p.referrer_id is null and o.referrer_id is null)))";
        String alone = "not " + shares;
        List<Case> correlated = List.of(
                new Case(f -> f.exists(ORDER_IDS, (s, outer) -> s.compare(ID, Op.NE, outer.column(ID))
                        .notDistinctFromColumn(REFERRER_ID, outer.column(REFERRER_ID))), shares),
                new Case(f -> f.notExists(ORDER_IDS, (s, outer) -> s.compare(ID, Op.NE, outer.column(ID))
                        .notDistinctFromColumn(REFERRER_ID, outer.column(REFERRER_ID))), alone),
                new Case(f -> f.exists(ORDER_IDS, (s, outer) -> s.compare(ID, Op.NE, outer.column(ID))
                        .distinctFromColumn(REFERRER_ID, outer.column(REFERRER_ID))),
                        // not (a = b or both NULL) is NULL for one NULL side, so the oracle names the NULL cases
                        "exists (select 1 from orders p where p.id <> o.id and ((p.referrer_id <> o.referrer_id) "
                                + "or (p.referrer_id is null and o.referrer_id is not null) "
                                + "or (p.referrer_id is not null and o.referrer_id is null)))"),
                new Case(f -> f.not(g -> g.exists(ORDER_IDS, (s, outer) -> s.compare(ID, Op.NE, outer.column(ID))
                        .notDistinctFromColumn(REFERRER_ID, outer.column(REFERRER_ID)))), alone));

        SqlSnapshots.assertMatches(db, "flt-38-null-safe-columns", ds -> executor(ds, executor -> {
            sameKey.forEach(c -> ids(executor, c.where()));
            oneSided.forEach(c -> ids(executor, c.where()));
        }));
        SqlSnapshots.assertMatches(db, "flt-38-null-safe-exists", ds -> executor(ds, executor ->
                correlated.forEach(c -> ids(executor, c.where()))));

        executor(db, executor -> {
            for (Case c : sameKey) {
                assertThat(ids(executor, c.where())).as(c.oracle())
                        .isEqualTo(oracle(db, "select id from orders where " + c.oracle() + " order by id"));
            }
            for (Case c : oneSided) {
                assertThat(ids(executor, c.where())).as(c.oracle()).isEqualTo(oracle(db, "select o.id from orders o "
                        + "join customers c on c.id = o.customer_id where " + c.oracle() + " order by o.id"));
            }
            for (Case c : correlated) {
                assertThat(ids(executor, c.where())).as(c.oracle())
                        .isEqualTo(oracle(db, "select o.id from orders o where " + c.oracle() + " order by o.id"));
            }
        });
        // The cases narrow the fixture, so a broken operator cannot pass by keeping every row or none.
        assertThat(oracle(db, "select o.id from orders o where " + shares)).isNotEmpty();
        assertThat(oracle(db, "select o.id from orders o where " + alone)).isNotEmpty();
    }

    // ---- AC-FLT-39

    @TckTest
    void ac_flt_39_an_empty_optional_still_skips_the_filter(TckDatabase db) {
        executor(db, executor -> {
            List<Long> all = ids(executor, f -> f);
            assertThat(all).isNotEmpty();
            assertThat(ids(executor, f -> f.notDistinctFrom(REFERRER_ID, java.util.Optional.<Long>empty())))
                    .isEqualTo(all);
            assertThat(ids(executor, f -> f.distinctFrom(REFERRER_ID, java.util.Optional.<Long>empty())))
                    .isEqualTo(all);
            assertThat(ids(executor, f -> f.notDistinctFrom(REFERRER_ID, java.util.Optional.of(0L)))).isEmpty();
        });
    }

    // ---- support

    private record Case(UnaryOperator<Filters<O>> where, String oracle) {}

    private static List<Long> ids(ModelQueryExecutor<OrderEntity> executor, UnaryOperator<Filters<O>> where) {
        return executor.list(QUERY.where(where).build(), Limit.unlimited()).stream().map(O::id).toList();
    }

    private static void executor(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static void executor(javax.sql.DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static long scalar(TckDatabase db, String sql) {
        return oracle(db, sql).get(0);
    }

    /** The first column of {@code sql}'s rows. */
    private static List<Long> oracle(TckDatabase db, String sql) {
        var result = new ArrayList<Long>();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getLong(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return result;
    }
}
