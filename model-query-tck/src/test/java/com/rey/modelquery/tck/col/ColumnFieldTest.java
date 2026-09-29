package com.rey.modelquery.tck.col;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;

/** Hand-written columns and their type check (spec api/10 R-COL-07, R-COL-08). */
class ColumnFieldTest {

    static final class OrderView {}

    static final class CustomerView {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ROOT, "items", INNER);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ROOT, "customer", INNER);
    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMER_ROOT =
            TableField.root(CustomerEntity.class);

    private static final ColumnField<OrderView, OrderEntity, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, OrderEntity, String> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", String.class);
    private static final ColumnField<OrderView, OrderEntity, OrderStatus> STATUS_CODE =
            ColumnField.of(OrderView.class, ROOT, "statusCode", OrderStatus.class);
    private static final ColumnField<OrderView, OrderItemEntity, Integer> QUANTITY =
            ColumnField.of(OrderView.class, ITEMS, "quantity", Integer.class);
    private static final ColumnField<OrderView, OrderItemEntity, String> PRODUCT_CODE =
            ColumnField.of(OrderView.class, ITEMS, "productCode", String.class);
    private static final ColumnField<CustomerView, CustomerEntity, String> CUSTOMER_COUNTRY =
            ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "country", String.class);

    @TckTest
    void ac_col_04_a_mismatched_hand_written_column_throws_mq1001_at_first_resolution(TckDatabase db) {
        // Defining the constants is not a resolution, so none of these throws yet.
        var statusAsInteger = ColumnField.of(OrderView.class, ROOT, "status", Integer.class);
        var convertedAsStored = ColumnField.of(OrderView.class, ROOT, "statusCode", String.class);
        var quantityAsLong = ColumnField.of(OrderView.class, ITEMS, "quantity", Long.class);
        var nameAsInteger = ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "name", Integer.class)
                .withTable(OrderView.class, CUSTOMER);
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                JoinContext ctx = JoinContext.of(cb.createTupleQuery().from(OrderEntity.class), cb);
                assertMq1001(() -> statusAsInteger.path(ctx),
                        "OrderView.status: declared Integer, entity attribute OrderEntity.status is String");
                // Selecting it as a SelectField runs the same check.
                SelectField<OrderView, Integer> selected = statusAsInteger;
                assertMq1001(() -> selected.expression(ctx),
                        "OrderView.status: declared Integer, entity attribute OrderEntity.status is String");
                // A converted attribute is read as the converter's entity type, never the stored type.
                assertMq1001(() -> convertedAsStored.path(ctx),
                        "OrderView.statusCode: declared String, entity attribute OrderEntity.statusCode is "
                                + "OrderStatus");
                assertMq1001(() -> quantityAsLong.path(ctx),
                        "OrderView.quantity: declared Long, entity attribute OrderItemEntity.quantity is ");
                // A re-rooted column is checked against the join it now sits on.
                assertMq1001(() -> nameAsInteger.path(ctx),
                        "OrderView.name: declared Integer, entity attribute CustomerEntity.name is String");
            });
        }
    }

    @TckTest
    void ac_col_04_matching_columns_resolve_including_primitive_converted_and_re_rooted(TckDatabase db) {
        ColumnField<OrderView, CustomerEntity, String> country = CUSTOMER_COUNTRY.withTable(OrderView.class, CUSTOMER);
        assertThat(country).isEqualTo(ColumnField.of(OrderView.class, CUSTOMER, "country", String.class));
        assertThat(country).isNotEqualTo(CUSTOMER_COUNTRY);
        // A primitive declaration reports its wrapper, so a value read from a row always casts.
        ColumnField<OrderView, OrderItemEntity, Integer> quantityAsInt =
                ColumnField.of(OrderView.class, ITEMS, "quantity", int.class);
        assertThat(quantityAsInt.type()).isSameAs(Integer.class);
        assertThat(quantityAsInt).isEqualTo(QUANTITY);
        List<SelectField<OrderView, ?>> columns =
                List.of(ID, STATUS, STATUS_CODE, quantityAsInt, PRODUCT_CODE, country);
        SqlSnapshots.assertMatches(db, "col-04-typed-columns", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<Tuple> q = cb.createTupleQuery();
                    Root<OrderEntity> root = q.from(OrderEntity.class);
                    JoinContext ctx = JoinContext.of(root, cb);
                    List<Selection<?>> selections = columns.stream()
                            .<Selection<?>>map(c -> c.expression(ctx))
                            .toList();
                    q.multiselect(selections)
                            .where(cb.equal(ID.path(ctx), 2L))
                            .orderBy(cb.asc(ITEMS.resolve(ctx).get("id")));
                    List<Tuple> rows = em.createQuery(q).getResultList();
                    assertThat(rows).hasSize(4);
                    for (Tuple row : rows) {
                        for (int i = 0; i < columns.size(); i++) {
                            assertThat(row.get(selections.get(i))).isInstanceOf(columns.get(i).type());
                        }
                        assertThat(row.get(selections.get(2))).isEqualTo(OrderStatus.SHIPPED);
                        assertThat(row.get(selections.get(1))).isEqualTo("SHIPPED");
                    }
                });
            }
        });
    }

    private static void assertMq1001(ThrowingCallable resolution, String message) {
        assertThatThrownBy(resolution)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1001))
                .hasMessageContaining(message);
    }
}
