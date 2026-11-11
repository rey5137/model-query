package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.ScalarField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SortSpec;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
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
 * A nested model read through a named joined-entity root with a presence key, hand-written as a generated
 * {@code @Join(entityJoin)} will be (R-COL-15, R-QRY-14, D-213, AC-COL-64 to AC-COL-66).
 */
class EntityJoinPresenceExecutionTest {

    record Buyer(Long id, String name, String email) {}

    record Order(Long id, Optional<Buyer> purchaser) {}

    record Slot(Long id, Integer sortInt, String sortText) {}

    record Line(Long id, Optional<Slot> slot) {}

    // The nested models' own columns and keys, declared on their own roots.
    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Buyer, CustomerEntity, Long> B_ID =
            ColumnField.of(Buyer.class, CUSTOMERS, "id", Long.class).named("id");
    private static final ColumnField<Buyer, CustomerEntity, String> B_NAME =
            ColumnField.of(Buyer.class, CUSTOMERS, "name", String.class).named("name");
    private static final ColumnField<Buyer, CustomerEntity, String> B_EMAIL =
            ColumnField.of(Buyer.class, CUSTOMERS, "email", String.class).named("email");
    private static final TableField<NullableSortEntity, NullableSortEntity> SLOTS =
            TableField.root(NullableSortEntity.class);
    private static final ColumnField<Slot, NullableSortEntity, Long> S_ID =
            ColumnField.of(Slot.class, SLOTS, "id", Long.class).named("id");
    private static final ColumnField<Slot, NullableSortEntity, Integer> S_INT =
            ColumnField.of(Slot.class, SLOTS, "sortInt", Integer.class).named("sortInt");
    private static final ColumnField<Slot, NullableSortEntity, String> S_TEXT =
            ColumnField.of(Slot.class, SLOTS, "sortText", String.class).named("sortText");

    // Orders and their referrer, joined by id as an entity join: two thirds of the orders have none.
    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Order, OrderEntity, Long> ID =
            ColumnField.of(Order.class, ORDERS, "id", Long.class).named("id");
    private static final ColumnField<Order, OrderEntity, Long> REFERRER =
            ColumnField.of(Order.class, ORDERS, "referrerId", Long.class);
    private static final EntityJoin<Order, CustomerEntity> LEFT_BUYER = buyer(JoinKind.LEFT);
    private static final EntityJoin<Order, CustomerEntity> INNER_BUYER = buyer(JoinKind.INNER);
    private static final TableField<CustomerEntity, CustomerEntity> BUYER_TABLE =
            LEFT_BUYER.root().presentBy(PrimaryKey.of(B_ID)).named("purchaser");
    private static final ColumnField<Order, CustomerEntity, String> NAME = B_NAME.withTable(Order.class, BUYER_TABLE);
    private static final ColumnField<Order, CustomerEntity, String> EMAIL =
            B_EMAIL.withTable(Order.class, BUYER_TABLE);

    // Lines joined to the nullable-sort rows by the order id: rows past 3000 miss, row 35 matches with NULLs.
    private static final ColumnField<Line, OrderEntity, Long> L_ID =
            ColumnField.of(Line.class, ORDERS, "id", Long.class).named("id");
    private static final EntityJoin<Line, NullableSortEntity> SLOT = EntityJoin.of("slot", NullableSortEntity.class,
            JoinKind.LEFT, List.of(EntityJoin.on(L_ID, "id")));
    private static final TableField<NullableSortEntity, NullableSortEntity> SLOT_TABLE =
            SLOT.root().presentBy(PrimaryKey.of(S_ID)).named("slot");

    private static EntityJoin<Order, CustomerEntity> buyer(JoinKind kind) {
        return EntityJoin.of("buyer", CustomerEntity.class, kind, List.of(EntityJoin.on(REFERRER, "id")));
    }

    private static Order order(Row row) {
        Row scoped = row.scoped(BUYER_TABLE);
        Long key = scoped.get(B_ID);
        return new Order(row.get(ID), key == null ? Optional.empty()
                : Optional.of(new Buyer(key, scoped.get(B_NAME), scoped.get(B_EMAIL))));
    }

    private static ModelQuery.Builder<OrderEntity, Long, Order> orders(EntityJoin<Order, CustomerEntity> join) {
        return ModelQuery.builder(ORDERS, EntityJoinPresenceExecutionTest::order).joinEntity(join)
                .select(SelectSet.of(ID, NAME, EMAIL)).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.gte(ID, 1L).lte(ID, 30L)).orderBy(ID.asc());
    }

    private static List<String> text(List<Order> orders) {
        return orders.stream().map(o -> SourceJoinExecutionTest.text(o.id(),
                o.purchaser().map(Buyer::id).orElse(null), o.purchaser().map(Buyer::name).orElse(null))).toList();
    }

    @TckTest
    void ac_col_66_a_left_miss_is_empty_and_inner_drops_it(TckDatabase db) throws SQLException {
        List<Order> left = new ArrayList<>();
        List<Order> inner = new ArrayList<>();
        withExecutor(db, x -> {
            left.addAll(x.list(orders(LEFT_BUYER).build(), Limit.unlimited()));
            inner.addAll(x.list(orders(INNER_BUYER).build(), Limit.unlimited()));
        });

        assertThat(text(left)).containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                select o.id, c.id, c.name from orders o left join customers c on c.id = o.referrer_id
                where o.id between 1 and 30 order by o.id"""));
        assertThat(left).hasSize(30).allSatisfy(o -> assertThat(o.purchaser().isPresent()).isEqualTo(o.id() % 3 == 0));
        assertThat(text(inner)).containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                select o.id, c.id, c.name from orders o join customers c on c.id = o.referrer_id
                where o.id between 1 and 30 order by o.id"""));
        assertThat(inner).hasSize(10).allSatisfy(o -> assertThat(o.purchaser()).isPresent());
    }

    @TckTest
    void ac_col_66_a_match_whose_read_columns_are_null_is_present(TckDatabase db) throws SQLException {
        List<Line> lines = new ArrayList<>();
        withExecutor(db, x -> lines.addAll(x.list(ModelQuery.builder(ORDERS, row -> {
            Row scoped = row.scoped(SLOT_TABLE);
            Long key = scoped.get(S_ID);
            return new Line(row.get(L_ID), key == null ? Optional.empty()
                    : Optional.of(new Slot(key, scoped.get(S_INT), scoped.get(S_TEXT))));
        }).joinEntity(SLOT).select(SelectSet.of(L_ID, S_INT.withTable(Line.class, SLOT_TABLE),
                S_TEXT.withTable(Line.class, SLOT_TABLE))).primaryKey(PrimaryKey.of(L_ID))
                .where(f -> f.in(L_ID, List.of(35L, 36L, 3001L))).orderBy(L_ID.asc()).build(), Limit.unlimited())));

        assertThat(lines.stream().map(l -> SourceJoinExecutionTest.text(l.id(), l.slot().map(Slot::id).orElse(null),
                l.slot().map(Slot::sortInt).orElse(null), l.slot().map(Slot::sortText).orElse(null))))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, s.id, s.sort_int, s.sort_text from orders o
                        left join nullable_sort_rows s on s.id = o.id
                        where o.id in (35, 36, 3001) order by o.id"""));
        // The rows hold each case: a match whose read columns are all NULL, a match with values, and a miss.
        assertThat(lines).anySatisfy(l -> assertThat(l.slot()).hasValueSatisfying(slot -> {
            assertThat(slot.sortInt()).isNull();
            assertThat(slot.sortText()).isNull();
        })).anySatisfy(l -> assertThat(l.slot()).hasValueSatisfying(slot -> assertThat(slot.sortInt()).isNotNull()))
                .anySatisfy(l -> assertThat(l.slot()).isEmpty());
    }

    @TckTest
    void ac_col_66_a_column_set_without_the_key_still_selects_it(TckDatabase db) throws SQLException {
        List<Order> rows = new ArrayList<>();
        withExecutor(db, x -> rows.addAll(x.list(orders(LEFT_BUYER).select(SelectSet.of(ID, NAME)).build(),
                Limit.unlimited())));

        assertThat(text(rows)).containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                select o.id, c.id, c.name from orders o left join customers c on c.id = o.referrer_id
                where o.id between 1 and 30 order by o.id"""));
        assertThat(rows).anySatisfy(o -> assertThat(o.purchaser()).isPresent())
                .allSatisfy(o -> o.purchaser().ifPresent(b -> assertThat(b.email()).isNull()));
    }

    @TckTest
    void ac_col_65_a_sort_and_a_filter_name_a_joined_root_column_by_its_field_path(TckDatabase db) {
        ScalarField<Order, String> byName = column(FieldIndex.builder(Order.class).select(ID, NAME, EMAIL).build()
                .filter("purchaser.name").orElseThrow());
        List<Order> sorted = new ArrayList<>();
        List<Order> filtered = new ArrayList<>();
        withExecutor(db, x -> {
            sorted.addAll(x.list(orders(INNER_BUYER).build()
                    .orderedBy(SortSpec.of(SortSpec.Key.desc("purchaser.name"))), Limit.unlimited()));
            filtered.addAll(x.list(orders(LEFT_BUYER)
                    .where(f -> f.gte(ID, 1L).lte(ID, 30L).eq(byName, "Customer 0034")).build(),
                    Limit.unlimited()));
        });

        assertThat(byName).isSameAs(NAME);
        assertThat(sorted).hasSize(10).extracting(o -> o.purchaser().orElseThrow().name())
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(filtered).containsExactly(
                new Order(3L, Optional.of(new Buyer(34L, "Customer 0034", "customer0034@example.test"))));
    }

    @SuppressWarnings("unchecked")
    private static ScalarField<Order, String> column(ScalarField<Order, ?> field) {
        return (ScalarField<Order, String>) field;
    }

    private static void withExecutor(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults())));
            }
        });
    }
}
