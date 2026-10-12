package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.hibernate.SessionFactory;

/**
 * A Hibernate {@code @Immutable} entity over a SQL view, and one over a {@code @Subselect}, are ordinary roots: read,
 * filtered, offset-paged with a stable order and grouped through their query models (spec api/10 AC-COL-29).
 */
class ViewRootTest {

    /** One group of the view root's grouped query. */
    record StatusGroup(String status, Long orders, BigDecimal total) {}

    /** One group of the sub-select root's grouped query. */
    record CountryGroup(String country, Long customers) {}

    private static final TableField<ViewOrderEntity, ViewOrderEntity> VIEW_ORDERS =
            TableField.root(ViewOrderEntity.class);
    private static final ColumnField<StatusGroup, ViewOrderEntity, String> GROUP_STATUS =
            ColumnField.of(StatusGroup.class, VIEW_ORDERS, "status", String.class);
    private static final AggregateField<StatusGroup, Long> GROUP_ORDERS = Agg.count(VIEW_ORDERS);
    private static final AggregateField<StatusGroup, BigDecimal> GROUP_TOTAL =
            Agg.sum(ColumnField.of(StatusGroup.class, VIEW_ORDERS, "total", BigDecimal.class));

    private static final TableField<SubselectCustomerEntity, SubselectCustomerEntity> SUB_CUSTOMERS =
            TableField.root(SubselectCustomerEntity.class);
    private static final ColumnField<CountryGroup, SubselectCustomerEntity, String> GROUP_COUNTRY =
            ColumnField.of(CountryGroup.class, SUB_CUSTOMERS, "country", String.class);
    private static final AggregateField<CountryGroup, Long> GROUP_CUSTOMERS = Agg.count(SUB_CUSTOMERS);

    // ---- AC-COL-29: an @Immutable entity over a SQL view

    @TckTest
    void ac_col_29_an_immutable_entity_over_a_view_is_read_filtered_paged_and_grouped(TckDatabase db) {
        withExecutor(db, ViewOrderEntity.class, executor -> {
            // Read: every column of the first rows, the view's values as the table holds them.
            assertThat(viewRows(executor.list(viewOrders(f -> f.lte(QViewOrderRow.ID, 20L)), Limit.unlimited())))
                    .isEqualTo(text(nativeRows(db, "select id, customer_id, status, total from order_view "
                            + "where id <= 20 order by id")));

            // Filter on a view column.
            assertThat(viewRows(executor.list(
                    viewOrders(f -> f.lte(QViewOrderRow.ID, 200L).eq(QViewOrderRow.STATUS, "PAID")),
                    Limit.unlimited())))
                    .isEqualTo(text(nativeRows(db, "select id, customer_id, status, total from order_view "
                            + "where id <= 200 and status = 'PAID' order by id")));

            // Offset page in a stable order.
            Slice<ViewOrderRow> page = executor.page(viewPage(), PageSpec.ofOffset(3, 7), CountMode.NO_COUNT);
            assertThat(viewRows(page.content()))
                    .isEqualTo(text(nativeRows(db, "select id, customer_id, status, total from order_view "
                            + "order by id").subList(3, 10)));

            assertViewGroups(db, executor);
        });
    }

    private static ModelQuery<ViewOrderEntity, ?, ViewOrderRow> viewOrders(
            UnaryOperator<Filters<ViewOrderRow>> where) {
        return QViewOrderRow.query()
                .select(SelectSet.of(QViewOrderRow.ID, QViewOrderRow.CUSTOMER_ID, QViewOrderRow.STATUS,
                        QViewOrderRow.TOTAL))
                .where(where)
                .orderBy(QViewOrderRow.ID.asc())
                .build();
    }

    private static ModelQuery<ViewOrderEntity, ?, ViewOrderRow> viewPage() {
        return QViewOrderRow.query()
                .select(SelectSet.of(QViewOrderRow.ID, QViewOrderRow.CUSTOMER_ID, QViewOrderRow.STATUS,
                        QViewOrderRow.TOTAL))
                .primaryKey(PrimaryKey.of(QViewOrderRow.ID))
                .orderBy(QViewOrderRow.ID.asc())
                .build();
    }

    private static void assertViewGroups(TckDatabase db, ModelQueryExecutor<ViewOrderEntity> executor) {
        var query = ModelQuery.builder(VIEW_ORDERS,
                        row -> new StatusGroup(row.get(GROUP_STATUS), row.get(GROUP_ORDERS), row.get(GROUP_TOTAL)))
                .select(SelectSet.of(GROUP_STATUS, GROUP_ORDERS, GROUP_TOTAL))
                .groupBy(GROUP_STATUS)
                .orderBy(GROUP_STATUS.asc())
                .build();
        List<StatusGroup> groups = executor.list(query, Limit.unlimited());
        List<Object[]> expected = nativeRows(db,
                "select status, count(*), sum(total) from order_view group by status order by status");
        assertThat(groups).hasSameSizeAs(expected);
        for (int i = 0; i < groups.size(); i++) {
            assertThat(groups.get(i).status()).isEqualTo(expected.get(i)[0]);
            assertThat(groups.get(i).orders()).isEqualTo(((Number) expected.get(i)[1]).longValue());
            assertThat(groups.get(i).total()).isEqualByComparingTo((BigDecimal) expected.get(i)[2]);
        }
    }

    private static List<String> viewRows(List<ViewOrderRow> rows) {
        return text(rows.stream()
                .map(row -> new Object[] {row.id(), row.customerId(), row.status(), row.total()}).toList());
    }

    // ---- AC-COL-29: an @Immutable @Subselect entity

    @TckTest
    void ac_col_29_an_immutable_entity_over_a_subselect_is_read_filtered_paged_and_grouped(TckDatabase db) {
        withExecutor(db, SubselectCustomerEntity.class, executor -> {
            assertThat(customerRows(executor.list(
                    customers(f -> f.lte(QSubselectCustomerRow.ID, 30L)), Limit.unlimited())))
                    .isEqualTo(text(nativeRows(db, "select id, name, country from customers where id <= 30 "
                            + "order by id")));

            assertThat(customerRows(executor.list(
                    customers(f -> f.eq(QSubselectCustomerRow.COUNTRY, "JP")), Limit.unlimited())))
                    .isEqualTo(text(nativeRows(db, "select id, name, country from customers where country = 'JP' "
                            + "order by id")));

            Slice<SubselectCustomerRow> page =
                    executor.page(customerPage(), PageSpec.ofOffset(4, 6), CountMode.NO_COUNT);
            assertThat(customerRows(page.content()))
                    .isEqualTo(text(nativeRows(db, "select id, name, country from customers order by id")
                            .subList(4, 10)));

            assertCustomerGroups(db, executor);
        });
    }

    private static ModelQuery<SubselectCustomerEntity, ?, SubselectCustomerRow> customers(
            UnaryOperator<Filters<SubselectCustomerRow>> where) {
        return QSubselectCustomerRow.query()
                .select(SelectSet.of(QSubselectCustomerRow.ID, QSubselectCustomerRow.NAME,
                        QSubselectCustomerRow.COUNTRY))
                .where(where)
                .orderBy(QSubselectCustomerRow.ID.asc())
                .build();
    }

    private static ModelQuery<SubselectCustomerEntity, ?, SubselectCustomerRow> customerPage() {
        return QSubselectCustomerRow.query()
                .select(SelectSet.of(QSubselectCustomerRow.ID, QSubselectCustomerRow.NAME,
                        QSubselectCustomerRow.COUNTRY))
                .primaryKey(PrimaryKey.of(QSubselectCustomerRow.ID))
                .orderBy(QSubselectCustomerRow.ID.asc())
                .build();
    }

    private static void assertCustomerGroups(TckDatabase db, ModelQueryExecutor<SubselectCustomerEntity> executor) {
        var query = ModelQuery.builder(SUB_CUSTOMERS,
                        row -> new CountryGroup(row.get(GROUP_COUNTRY), row.get(GROUP_CUSTOMERS)))
                .select(SelectSet.of(GROUP_COUNTRY, GROUP_CUSTOMERS))
                .groupBy(GROUP_COUNTRY)
                .orderBy(GROUP_COUNTRY.asc())
                .build();
        List<CountryGroup> groups = executor.list(query, Limit.unlimited());
        List<Object[]> expected = nativeRows(db,
                "select country, count(*) from customers group by country order by country");
        assertThat(groups).hasSameSizeAs(expected);
        for (int i = 0; i < groups.size(); i++) {
            assertThat(groups.get(i).country()).isEqualTo(expected.get(i)[0]);
            assertThat(groups.get(i).customers()).isEqualTo(((Number) expected.get(i)[1]).longValue());
        }
    }

    private static List<String> customerRows(List<SubselectCustomerRow> rows) {
        return text(rows.stream()
                .map(row -> new Object[] {row.id(), row.name(), row.country()}).toList());
    }

    // ---- support

    /** The rows' values as one text form, so a model row and its native counterpart compare identically. */
    private static List<String> text(List<Object[]> rows) {
        return rows.stream().map(ViewRootTest::rowText).toList();
    }

    private static String rowText(Object[] row) {
        var parts = new ArrayList<String>(row.length);
        for (Object value : row) {
            parts.add(value instanceof BigDecimal decimal ? decimal.toPlainString()
                    : value instanceof Number number ? Long.toString(number.longValue())
                    : String.valueOf(value));
        }
        return String.join("|", parts);
    }

    private static List<Object[]> nativeRows(TckDatabase db, String sql) {
        List<Object[]> out = new ArrayList<>();
        inSession(db, em -> out.addAll(em.createNativeQuery(sql).getResultList()));
        return out;
    }

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
