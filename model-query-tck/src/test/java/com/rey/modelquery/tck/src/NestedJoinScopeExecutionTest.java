package com.rey.modelquery.tck.src;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.JoinScope;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.LabelEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hibernate.SessionFactory;

/**
 * A nested model's own entity and source joins read under two {@code @Join} fields of its type, through a checked
 * {@link JoinScope}, hand-written as M36.4's generated code will be: the RFC 0024 spike's Part B cells
 * (api/10 §5, R-COL-15, D-214, D-216, AC-COL-67 to AC-COL-70). {@code Buyer} joins its label (the label whose id is
 * the customer's, so customers 1 to 10 have one) and its order count; the order reads it as customer and referrer.
 */
class NestedJoinScopeExecutionTest {

    /** The source: one row per customer, the orders placing it. */
    static final class Spend {}

    record Buyer(Long id, String name, String label, Long orders) {}

    record OrderView(Long id, Optional<Buyer> customer, Optional<Buyer> referrer) {}

    private static final TableField<OrderEntity, OrderEntity> PLACED = TableField.root(OrderEntity.class);
    private static final ColumnField<Spend, ?, Long> S_CUSTOMER = ColumnField.of(Spend.class,
            TableField.join(PLACED, "customer", INNER), "id", Long.class).named("customerId");
    private static final AggregateField<Spend, Long> S_ORDERS = Agg.<Spend>count(PLACED).named("orders");

    // Buyer, the nested model: its own root, entity join and source join, as its generated QModel declares them.
    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Buyer, CustomerEntity, Long> B_ID =
            ColumnField.of(Buyer.class, CUSTOMERS, "id", Long.class).named("id");
    private static final ColumnField<Buyer, CustomerEntity, String> B_NAME =
            ColumnField.of(Buyer.class, CUSTOMERS, "name", String.class).named("name");
    private static final PrimaryKey<Buyer, Long> B_KEY = PrimaryKey.of(B_ID);
    private static final EntityJoin<Buyer, LabelEntity> B_LABEL = EntityJoin.of("label", LabelEntity.class,
            JoinKind.LEFT, List.of(EntityJoin.on(B_ID, "id")));
    private static final ColumnField<Buyer, LabelEntity, String> B_LABEL_NAME =
            ColumnField.of(Buyer.class, B_LABEL.root(), "name", String.class).named("label");
    private static final SourceJoin<Buyer, Spend> B_SPEND = SourceJoin.of("spend", Spend.class, JoinKind.LEFT,
            List.of(SourceJoin.on(B_ID, S_CUSTOMER)));
    private static final ColumnField<Buyer, Spend, Long> B_ORDERS =
            ColumnField.over(Buyer.class, B_SPEND.root(), S_ORDERS).named("orders");

    // OrderView: Buyer's joins registered once per @Join field under its prefix.
    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<OrderView, OrderEntity, Long> ID =
            ColumnField.of(OrderView.class, ORDERS, "id", Long.class).named("id");
    private static final Field CUSTOMER = new Field("customer");
    private static final Field REFERRER = new Field("referrer");

    /** One {@code @Join Optional<Buyer>} field's generated constants. */
    private static final class Field {
        final TableField<OrderEntity, CustomerEntity> table;
        final ColumnField<OrderView, CustomerEntity, Long> id;
        final ColumnField<OrderView, CustomerEntity, String> name;
        final EntityJoin<OrderView, LabelEntity> label;
        final SourceJoin<OrderView, Spend> spend;
        final ColumnField<OrderView, LabelEntity, String> labelName;
        final ColumnField<OrderView, Spend, Long> orders;
        final JoinScope scope;

        Field(String prefix) {
            table = TableField.<OrderEntity, CustomerEntity>join(ORDERS, prefix, LEFT).presentBy(B_KEY).named(prefix);
            id = B_ID.withTable(OrderView.class, table);
            name = B_NAME.withTable(OrderView.class, table);
            label = EntityJoin.of(prefix + "_label", LabelEntity.class, JoinKind.LEFT, List.of(EntityJoin.on(id, "id")))
                    .within(table);
            spend = SourceJoin.of(prefix + "_spend", Spend.class, JoinKind.LEFT, List.of(SourceJoin.on(id,
                    S_CUSTOMER))).within(table);
            labelName = B_LABEL_NAME.withTable(OrderView.class, label.root().named(prefix));
            orders = B_ORDERS.withTable(OrderView.class, spend.root().named(prefix));
            scope = JoinScope.of(table).entity(B_LABEL, label).source(B_SPEND, spend);
        }
    }

    /** Orders whose customer (946, 973) or referrer (273, 546, 819) has a label, and some with neither. */
    private static final List<Long> SAMPLE = List.of(1L, 2L, 3L, 273L, 546L, 819L, 946L, 973L);

    private static final String NATIVE = """
            select o.id, c.id, c.name, cl.name, cs.n, r.id, r.name, rl.name, rs.n from orders o
            left join customers c on c.id = o.customer_id
            left join labels cl on cl.id = c.id
            left join (select customer_id, count(*) n from orders group by customer_id) cs on cs.customer_id = c.id
            left join customers r on r.id = o.referrer_id
            left join labels rl on rl.id = r.id
            left join (select customer_id, count(*) n from orders group by customer_id) rs on rs.customer_id = r.id
            where o.id in (1, 2, 3, 273, 546, 819, 946, 973) order by o.id""";

    private static SourceQuery<Spend> spend() {
        return SourceQuery.builder(Spend.class, PLACED, SelectSet.of(S_CUSTOMER, S_ORDERS), PrimaryKey.of(S_CUSTOMER),
                SelectSet.of(S_CUSTOMER)).build();
    }

    private static Optional<Buyer> buyer(Row row) {
        Long id = row.get(B_ID);
        return id == null ? Optional.empty()
                : Optional.of(new Buyer(id, row.get(B_NAME), row.get(B_LABEL_NAME), row.get(B_ORDERS)));
    }

    /** The orders mapped with {@code scope} giving each field's scoped row, as the generated mapper does. */
    private static ModelQuery.Builder<OrderEntity, Long, OrderView> orders(Function<Field, Function<Row, Row>> scope) {
        return ModelQuery.builder(ORDERS, row -> new OrderView(row.get(ID),
                        buyer(scope.apply(CUSTOMER).apply(row)), buyer(scope.apply(REFERRER).apply(row))))
                .joinEntity(CUSTOMER.label).joinSource(CUSTOMER.spend, spend())
                .joinEntity(REFERRER.label).joinSource(REFERRER.spend, spend())
                .select(SelectSet.of(ID, CUSTOMER.id, CUSTOMER.name, CUSTOMER.labelName, CUSTOMER.orders,
                        REFERRER.id, REFERRER.name, REFERRER.labelName, REFERRER.orders))
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.in(ID, SAMPLE)).orderBy(ID.asc());
    }

    private static List<String> text(List<OrderView> orders) {
        return orders.stream().map(o -> SourceJoinExecutionTest.text(o.id(),
                o.customer().map(Buyer::id).orElse(null), o.customer().map(Buyer::name).orElse(null),
                o.customer().map(Buyer::label).orElse(null), o.customer().map(Buyer::orders).orElse(null),
                o.referrer().map(Buyer::id).orElse(null), o.referrer().map(Buyer::name).orElse(null),
                o.referrer().map(Buyer::label).orElse(null), o.referrer().map(Buyer::orders).orElse(null))).toList();
    }

    @TckTest
    void ac_col_68_two_fields_of_one_nested_type_each_read_their_own_joins(TckDatabase db) throws SQLException {
        List<OrderView> rows = new ArrayList<>();
        withExecutor(db, x -> rows.addAll(x.list(orders(field -> row -> row.scoped(field.scope)).build(),
                Limit.unlimited())));

        assertThat(text(rows)).containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, NATIVE));
        // The sample holds each case: a customer's label, a referrer's label, a label miss, an absent referrer.
        assertThat(rows).anySatisfy(o -> assertThat(o.customer().orElseThrow().label()).isNotNull())
                .anySatisfy(o -> assertThat(o.referrer()).hasValueSatisfying(r -> assertThat(r.label()).isNotNull()))
                .anySatisfy(o -> assertThat(o.customer().orElseThrow().label()).isNull())
                .anySatisfy(o -> assertThat(o.referrer()).isEmpty());
    }

    @TckTest
    void ac_col_70_a_column_set_of_only_nested_joined_columns_keeps_each_buyer_present(TckDatabase db)
            throws SQLException {
        List<OrderView> rows = new ArrayList<>();
        withExecutor(db, x -> rows.addAll(x.list(orders(field -> row -> row.scoped(field.scope))
                .select(SelectSet.of(ID, CUSTOMER.labelName, CUSTOMER.orders, REFERRER.labelName)).build(),
                Limit.unlimited())));

        // Each field's key is selected for its joined columns: without it, every customer read as empty.
        assertThat(rows.stream().map(o -> SourceJoinExecutionTest.text(o.id(),
                o.customer().map(Buyer::id).orElse(null), o.customer().map(Buyer::label).orElse(null),
                o.customer().map(Buyer::orders).orElse(null), o.referrer().map(Buyer::id).orElse(null),
                o.referrer().map(Buyer::label).orElse(null)))).containsExactlyElementsOf(
                SourceJoinExecutionTest.rows(db, """
                        select o.id, c.id, cl.name, cs.n, r.id, rl.name from orders o
                        left join customers c on c.id = o.customer_id
                        left join labels cl on cl.id = c.id
                        left join (select customer_id, count(*) n from orders group by customer_id) cs
                            on cs.customer_id = c.id
                        left join customers r on r.id = o.referrer_id
                        left join labels rl on rl.id = r.id
                        where o.id in (1, 2, 3, 273, 546, 819, 946, 973) order by o.id"""));
        assertThat(rows).allSatisfy(o -> assertThat(o.customer()).hasValueSatisfying(
                c -> assertThat(c.name()).isNull())).anySatisfy(o -> assertThat(o.referrer()).isPresent())
                .anySatisfy(o -> assertThat(o.referrer()).isEmpty());
    }

    @TckTest
    void ac_col_67_todays_plain_scope_fails_loudly_instead_of_reading_the_customers_name(TckDatabase db) {
        // Before D-216 this read each customer's own name as its label: 'Customer 0003' for order 946's 'label-03'.
        assertThatThrownBy(() -> withExecutor(db, x -> x.list(orders(field -> row -> row.scoped(field.table))
                .build(), Limit.unlimited())))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1107))
                .hasMessageContaining("Buyer.name: read through JoinScope[join 'customer' (LEFT)], which maps no "
                        + "outer join for its joined root joined entity 'label' (LabelEntity)");
    }

    @TckTest
    void ac_col_68_a_scope_missing_an_entry_fails_loudly_when_read(TckDatabase db) {
        JoinScope noLabel = JoinScope.of(CUSTOMER.table).source(B_SPEND, CUSTOMER.spend);
        assertThatThrownBy(() -> withExecutor(db, x -> x.list(orders(field -> row -> row.scoped(
                field == CUSTOMER ? noLabel : field.scope)).build(), Limit.unlimited())))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1107))
                .hasMessageContaining("joined entity 'label' (LabelEntity)");
    }

    @TckTest
    void ac_col_69_a_swapped_prefix_or_another_kind_is_refused_when_the_scope_is_built(TckDatabase db) {
        assertThatThrownBy(() -> JoinScope.of(CUSTOMER.table).entity(B_LABEL, REFERRER.label))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1107))
                .hasMessageContaining("the outer join is registered within join 'referrer' (LEFT)");
        assertThatThrownBy(() -> JoinScope.of(CUSTOMER.table).source(B_SPEND, REFERRER.spend))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1107));
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
