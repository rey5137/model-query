package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;

/**
 * Generated models whose nested {@code JoinBuyer} joins a source and an entity of its own, under one and two
 * {@code @Join} fields (processor/31 R-GEN-43, AC-GEN-41 to AC-GEN-45). {@code JoinBuyer} joins its label (the label
 * whose id is the customer's, so customers 1 to 10 have one) and a source of one row per customer.
 */
class GeneratedNestedJoinExecutionTest {

    /** Orders whose customer (946, 973) or referrer (273, 546, 819) has a label, and some with neither. */
    private static final List<Long> SAMPLE = List.of(1L, 2L, 3L, 273L, 546L, 819L, 946L, 973L);

    /** The source of customers 1 to 100 only, so a customer above it reads a missing source row. */
    private static SourceQuery<CustomerOrderCount> low() {
        return QCustomerOrderCount.source().where(f -> f.lte(QCustomerOrderCount.ID, 100L)).build();
    }

    private static SourceQuery<CustomerOrderCount> all() {
        return QCustomerOrderCount.source().build();
    }

    private static Object[] concat(Long id, Optional<JoinBuyer> customer, Optional<JoinBuyer> referrer) {
        var parts = new ArrayList<Object>();
        parts.add(id);
        for (Optional<JoinBuyer> buyer : List.of(customer, referrer)) {
            parts.add(buyer.map(JoinBuyer::id).orElse(null));
            parts.add(buyer.map(JoinBuyer::name).orElse(null));
            parts.add(buyer.map(JoinBuyer::rows).orElse(null));
            parts.add(buyer.map(JoinBuyer::labelName).orElse(null));
        }
        return parts.toArray();
    }

    private static final String ORDERS = """
            select o.id, c.id, c.name, cs.n, cl.name, r.id, r.name, rs.n, rl.name from orders o
            left join customers c on c.id = o.customer_id
            left join labels cl on cl.id = c.id
            left join (select id, count(id) n from customers where id <= 100 group by id) cs on cs.id = c.id
            left join customers r on r.id = o.referrer_id
            left join labels rl on rl.id = r.id
            left join (select id, count(id) n from customers group by id) rs on rs.id = r.id
            where o.id in (1, 2, 3, 273, 546, 819, 946, 973) order by o.id""";

    private static SelectSet<JoinBuyerOrder> orderColumns() {
        return SelectSet.of(QJoinBuyerOrder.ID,
                QJoinBuyerOrder.CUSTOMER_ID, QJoinBuyerOrder.CUSTOMER_NAME, QJoinBuyerOrder.CUSTOMER_ROWS,
                QJoinBuyerOrder.CUSTOMER_LABEL_NAME,
                QJoinBuyerOrder.REFERRER_ID, QJoinBuyerOrder.REFERRER_NAME, QJoinBuyerOrder.REFERRER_ROWS,
                QJoinBuyerOrder.REFERRER_LABEL_NAME);
    }

    @TckTest
    void ac_gen_41_two_fields_of_one_nested_type_each_read_their_own_entity_and_source_join(TckDatabase db)
            throws SQLException {
        var query = QJoinBuyerOrder.query(low(), all()).select(orderColumns())
                .where(f -> f.in(QJoinBuyerOrder.ID, SAMPLE)).orderBy(QJoinBuyerOrder.ID.asc()).build();
        List<JoinBuyerOrder> rows = new ArrayList<>();
        orders(db, x -> rows.addAll(x.list(query, Limit.unlimited())));

        assertThat(rows.stream().map(o -> SourceJoinExecutionTest.text(concat(o.id(), o.customer(), o.referrer()))))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, ORDERS));
        // The sample holds each case: a label, a label miss, a source miss (customer above 100), an absent referrer.
        assertThat(rows).anySatisfy(o -> assertThat(o.customer().orElseThrow().labelName()).isNotNull())
                .anySatisfy(o -> assertThat(o.referrer())
                        .hasValueSatisfying(r -> assertThat(r.labelName()).isNotNull()))
                .anySatisfy(o -> assertThat(o.customer().orElseThrow().labelName()).isNull())
                .anySatisfy(o -> assertThat(o.customer().orElseThrow().rows()).isNull())
                .anySatisfy(o -> assertThat(o.referrer()).isEmpty());
    }

    @TckTest
    void ac_gen_42_two_levels_register_the_inner_joins_and_name_their_columns_by_path(TckDatabase db)
            throws SQLException {
        assertThat(QJoinBuyerItem.fields().resolve(List.of("order.customer.labelName", "order.customer.name"))
                .unknown()).isEmpty();
        var set = SelectSet.of(QJoinBuyerItem.ID, QJoinBuyerItem.ORDER_CUSTOMER_ID, QJoinBuyerItem.ORDER_CUSTOMER_NAME,
                QJoinBuyerItem.ORDER_CUSTOMER_ROWS, QJoinBuyerItem.ORDER_CUSTOMER_LABEL_NAME);
        var sorted = QJoinBuyerItem.query(all()).select(set)
                .where(f -> f.lte(QJoinBuyerItem.ID, 60L))
                .orderBy(QJoinBuyerItem.ORDER_CUSTOMER_NAME.desc(), QJoinBuyerItem.ID.asc()).build();
        var labelled = QJoinBuyerItem.query(all()).select(set)
                .where(f -> f.eq(QJoinBuyerItem.ORDER_CUSTOMER_LABEL_NAME, "label-03"))
                .orderBy(QJoinBuyerItem.ID.asc()).build();
        List<JoinBuyerItem> sortedRows = new ArrayList<>();
        List<JoinBuyerItem> labelledRows = new ArrayList<>();
        items(db, x -> {
            sortedRows.addAll(x.list(sorted, Limit.unlimited()));
            labelledRows.addAll(x.list(labelled, Limit.unlimited()));
        });

        String from = """
                from order_items i left join orders o on o.id = i.order_id
                left join customers c on c.id = o.customer_id left join labels l on l.id = c.id
                left join (select id, count(id) n from customers group by id) s on s.id = c.id""";
        assertThat(sortedRows.stream().map(GeneratedNestedJoinExecutionTest::item))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, "select i.id, c.id, c.name, s.n, l.name "
                        + from + " where i.id <= 60 order by c.name desc, i.id"));
        assertThat(labelledRows.stream().map(GeneratedNestedJoinExecutionTest::item))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, "select i.id, c.id, c.name, s.n, l.name "
                        + from + " where l.name = 'label-03' order by i.id"));
        assertThat(sortedRows).hasSizeGreaterThan(10);
        assertThat(labelledRows).isNotEmpty();
    }

    private static String item(JoinBuyerItem item) {
        Optional<JoinBuyer> buyer = item.order().flatMap(JoinBuyerHolder::customer);
        return SourceJoinExecutionTest.text(item.id(), buyer.map(JoinBuyer::id).orElse(null),
                buyer.map(JoinBuyer::name).orElse(null), buyer.map(JoinBuyer::rows).orElse(null),
                buyer.map(JoinBuyer::labelName).orElse(null));
    }

    @TckTest
    void ac_gen_43_a_column_set_of_only_nested_joined_columns_keeps_each_buyer_present(TckDatabase db)
            throws SQLException {
        var query = QJoinBuyerOrder.query(all(), all())
                .select(SelectSet.of(QJoinBuyerOrder.ID, QJoinBuyerOrder.CUSTOMER_LABEL_NAME,
                        QJoinBuyerOrder.CUSTOMER_ROWS, QJoinBuyerOrder.REFERRER_LABEL_NAME))
                .where(f -> f.in(QJoinBuyerOrder.ID, SAMPLE)).orderBy(QJoinBuyerOrder.ID.asc()).build();
        List<JoinBuyerOrder> rows = new ArrayList<>();
        orders(db, x -> rows.addAll(x.list(query, Limit.unlimited())));

        // Each field's key is selected for its joined columns: without it, every customer read as empty.
        assertThat(rows).allSatisfy(o -> assertThat(o.customer()).hasValueSatisfying(
                c -> assertThat(c.name()).isNull()))
                .anySatisfy(o -> assertThat(o.referrer()).isPresent())
                .anySatisfy(o -> assertThat(o.referrer()).isEmpty());
        assertThat(rows.stream().map(o -> SourceJoinExecutionTest.text(o.id(),
                o.customer().map(JoinBuyer::id).orElse(null), o.customer().map(JoinBuyer::labelName).orElse(null),
                o.customer().map(JoinBuyer::rows).orElse(null), o.referrer().map(JoinBuyer::id).orElse(null),
                o.referrer().map(JoinBuyer::labelName).orElse(null)))).containsExactlyElementsOf(
                SourceJoinExecutionTest.rows(db, """
                        select o.id, c.id, cl.name, cs.n, r.id, rl.name from orders o
                        left join customers c on c.id = o.customer_id
                        left join labels cl on cl.id = c.id
                        left join (select id, count(id) n from customers group by id) cs on cs.id = c.id
                        left join customers r on r.id = o.referrer_id
                        left join labels rl on rl.id = r.id
                        where o.id in (1, 2, 3, 273, 546, 819, 946, 973) order by o.id"""));
    }

    @TckTest
    void ac_gen_44_keyset_pages_over_the_outer_root_lose_no_row_with_nested_joins(TckDatabase db)
            throws SQLException {
        var keyset = QJoinBuyerOrder.query(all(), all()).select(orderColumns())
                .where(f -> f.lte(QJoinBuyerOrder.ID, 300L)).keyset().build();
        List<JoinBuyerOrder> listed = new ArrayList<>();
        List<JoinBuyerOrder> paged = new ArrayList<>();
        List<JoinBuyerOrder> exported = new ArrayList<>();
        orders(db, x -> {
            listed.addAll(x.list(keyset, Limit.unlimited()));
            KeysetSpec next = KeysetSpec.first(7);
            while (true) {
                var page = x.page(keyset, next);
                paged.addAll(page.content());
                if (!page.hasNext()) {
                    break;
                }
                next = KeysetSpec.after(page.nextCursor().orElseThrow(), 7);
            }
            x.export(keyset, ExportOptions.of(7), p -> p, exported::add);
        });

        assertThat(listed).hasSize(300).anySatisfy(o -> assertThat(o.customer()).isPresent());
        assertThat(listed.stream().map(o -> SourceJoinExecutionTest.text(concat(o.id(), o.customer(), o.referrer()))))
                .containsExactlyInAnyOrderElementsOf(SourceJoinExecutionTest.rows(db, ORDERS.replace(
                        "where o.id in (1, 2, 3, 273, 546, 819, 946, 973)", "where o.id <= 300")
                        .replace("where id <= 100 group", "group")));
        List<JoinBuyerOrder> byId = listed.stream().sorted(Comparator.comparing(JoinBuyerOrder::id)).toList();
        assertThat(paged).isEqualTo(byId);
        assertThat(exported).isEqualTo(byId);
    }

    @TckTest
    void ac_gen_46_a_nested_entity_join_anchored_on_its_source_column_reads_the_registered_source(TckDatabase db)
            throws SQLException {
        var query = QJoinTwinOrder.query(low())
                .select(SelectSet.of(QJoinTwinOrder.ID, QJoinTwinOrder.CUSTOMER_ID, QJoinTwinOrder.CUSTOMER_ROWS,
                        QJoinTwinOrder.CUSTOMER_TWIN_NAME))
                .where(f -> f.in(QJoinTwinOrder.ID, SAMPLE)).orderBy(QJoinTwinOrder.ID.asc()).build();
        List<JoinTwinOrder> rows = new ArrayList<>();
        orders(db, x -> rows.addAll(x.list(query, Limit.unlimited())));

        assertThat(rows.stream().map(o -> SourceJoinExecutionTest.text(o.id(),
                o.customer().map(JoinTwin::id).orElse(null), o.customer().map(JoinTwin::rows).orElse(null),
                o.customer().map(JoinTwin::twinName).orElse(null)))).containsExactlyElementsOf(
                SourceJoinExecutionTest.rows(db, """
                        select o.id, c.id, s.n, t.name from orders o
                        left join customers c on c.id = o.customer_id
                        left join (select id, count(id) n from customers where id <= 100 group by id) s
                            on s.id = c.id
                        left join customers t on t.id = s.id
                        where o.id in (1, 2, 3, 273, 546, 819, 946, 973) order by o.id"""));
        // The twin follows the source row: present where the source has the customer, empty where it does not.
        assertThat(rows).anySatisfy(o -> assertThat(o.customer().orElseThrow().twinName()).isNotNull())
                .anySatisfy(o -> assertThat(o.customer().orElseThrow().twinName()).isNull());
    }

    private static void orders(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults())));
            }
        });
    }

    private static void items(TckDatabase db, Consumer<ModelQueryExecutor<OrderItemEntity>> work) {
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderItemEntity.class,
                        ModelQueryConfig.defaults())));
            }
        });
    }
}
