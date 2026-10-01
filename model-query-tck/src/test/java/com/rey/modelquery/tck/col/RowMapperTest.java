package com.rey.modelquery.tck.col;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.RowSelection;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;

/** Rows read by column, mapped to a class and to a record (spec api/10 R-COL-10, R-COL-11). */
class RowMapperTest {

    static final class OrderView {}

    static final class CustomerView {}

    /** A mutable class model, filled through a {@link com.rey.modelquery.core.SetterMapper}. */
    static final class ClassModel {
        Long itemId;
        String status;
        OrderStatus statusCode;
        BigDecimal total;
        LocalDateTime placedAt;
        boolean vip;
        String customerName;
        LocalDateTime customerCreatedAt;
        Integer quantity;
        BigDecimal unitPrice;
        Long notSelected = -1L;
    }

    record RecordModel(Long itemId, String status, OrderStatus statusCode, BigDecimal total, LocalDateTime placedAt,
            boolean vip, String customerName, LocalDateTime customerCreatedAt, Integer quantity, BigDecimal unitPrice,
            Long notSelected) {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ROOT, "customer", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ROOT, "items", INNER);
    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMER_ROOT =
            TableField.root(CustomerEntity.class);

    private static final ColumnField<ClassModel, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ClassModel.class, ITEMS, "id", Long.class);
    private static final ColumnField<ClassModel, OrderEntity, String> STATUS =
            ColumnField.of(ClassModel.class, ROOT, "status", String.class);
    private static final ColumnField<ClassModel, OrderEntity, OrderStatus> STATUS_CODE =
            ColumnField.of(ClassModel.class, ROOT, "statusCode", OrderStatus.class);
    private static final ColumnField<ClassModel, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(ClassModel.class, ROOT, "total", BigDecimal.class);
    private static final ColumnField<ClassModel, OrderEntity, LocalDateTime> PLACED_AT =
            ColumnField.of(ClassModel.class, ROOT, "placedAt", LocalDateTime.class);
    // Declared with the primitive type: a column reads it as its wrapper.
    private static final ColumnField<ClassModel, CustomerEntity, Boolean> VIP =
            ColumnField.of(ClassModel.class, CUSTOMER, "vip", boolean.class);
    private static final ColumnField<ClassModel, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(ClassModel.class, CUSTOMER, "name", String.class);
    private static final ColumnField<ClassModel, CustomerEntity, LocalDateTime> CUSTOMER_CREATED_AT =
            ColumnField.of(ClassModel.class, CUSTOMER, "createdAt", LocalDateTime.class);
    private static final ColumnField<ClassModel, OrderItemEntity, Integer> QUANTITY =
            ColumnField.of(ClassModel.class, ITEMS, "quantity", int.class);
    private static final ColumnField<ClassModel, OrderItemEntity, BigDecimal> UNIT_PRICE =
            ColumnField.of(ClassModel.class, ITEMS, "unitPrice", BigDecimal.class);
    // A column that is defined but never selected.
    private static final ColumnField<ClassModel, OrderEntity, Long> ORDER_ID =
            ColumnField.of(ClassModel.class, ROOT, "id", Long.class);

    private static final List<SelectField<ClassModel, ?>> SELECTED = List.of(ITEM_ID, STATUS, STATUS_CODE, TOTAL,
            PLACED_AT, VIP, CUSTOMER_NAME, CUSTOMER_CREATED_AT, QUANTITY, UNIT_PRICE);

    private static final RowMapper<ClassModel> CLASS_MAPPER = RowMapper.setters(ClassModel::new)
            .bind(ITEM_ID, (m, v) -> m.itemId = v)
            .bind(STATUS, (m, v) -> m.status = v)
            .bind(STATUS_CODE, (m, v) -> m.statusCode = v)
            .bind(TOTAL, (m, v) -> m.total = v)
            .bind(PLACED_AT, (m, v) -> m.placedAt = v)
            .bind(VIP, (m, v) -> m.vip = v)
            .bind(CUSTOMER_NAME, (m, v) -> m.customerName = v)
            .bind(CUSTOMER_CREATED_AT, (m, v) -> m.customerCreatedAt = v)
            .bind(QUANTITY, (m, v) -> m.quantity = v)
            .bind(UNIT_PRICE, (m, v) -> m.unitPrice = v);

    private static final RowMapper<RecordModel> RECORD_MAPPER = row -> new RecordModel(row.get(ITEM_ID),
            row.get(STATUS), row.get(STATUS_CODE), row.get(TOTAL), row.get(PLACED_AT), row.get(VIP),
            row.get(CUSTOMER_NAME), row.get(CUSTOMER_CREATED_AT), row.get(QUANTITY), row.get(UNIT_PRICE),
            row.get(ORDER_ID));

    private static final String EXPECTED_SQL = "SELECT i.id, o.status, o.total, o.placed_at, c.vip, c.name, "
            + "c.created_at, i.quantity, i.unit_price FROM orders o JOIN customers c ON c.id = o.customer_id "
            + "JOIN order_items i ON i.order_id = o.id WHERE o.id <= 4 ORDER BY i.id";

    @TckTest
    void ac_col_06_every_column_type_round_trips_through_row_get_for_a_class_and_a_record_model(TckDatabase db) {
        List<Row> rows = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                CriteriaQuery<Tuple> q = cb.createTupleQuery();
                Root<OrderEntity> root = q.from(OrderEntity.class);
                JoinContext ctx = JoinContext.of(root, cb);
                RowSelection selection = RowSelection.of(SELECTED);
                q.multiselect(selection.selections(ctx))
                        .where(cb.le(root.<Long>get("id"), 4L))
                        .orderBy(cb.asc(ITEM_ID.path(ctx)));
                em.createQuery(q).getResultList().forEach(t -> rows.add(selection.row(t)));
            });
        }
        List<Expected> expected = expected(db);
        assertThat(rows).hasSize(expected.size()).isNotEmpty();
        assertThat(rows.stream().map(CLASS_MAPPER::map).toList())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expected.stream().map(e -> e.toClass()).toList());
        assertThat(rows.stream().map(RECORD_MAPPER::map).toList()).containsExactlyElementsOf(
                expected.stream().map(Expected::toRecord).toList());
    }

    @TckTest
    void ac_col_06_a_column_that_was_not_selected_reads_null_and_a_scoped_row_reads_a_nested_models_columns(
            TckDatabase db) {
        ColumnField<CustomerView, CustomerEntity, String> nestedName =
                ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "name", String.class);
        ColumnField<CustomerView, CustomerEntity, Boolean> nestedVip =
                ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "vip", Boolean.class);
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                CriteriaQuery<Tuple> q = cb.createTupleQuery();
                Root<OrderEntity> root = q.from(OrderEntity.class);
                JoinContext ctx = JoinContext.of(root, cb);
                RowSelection selection = RowSelection.of(List.of(STATUS, CUSTOMER_NAME));
                q.multiselect(selection.selections(ctx)).where(cb.equal(root.get("id"), 1L));
                Row row = selection.row(em.createQuery(q).getSingleResult());

                assertThat(row.isSelected(STATUS)).isTrue();
                assertThat(row.isSelected(ORDER_ID)).isFalse();
                assertThat(row.get(ORDER_ID)).isNull();
                assertThat(row.get(STATUS)).isNotNull();

                Row customer = row.scoped(CUSTOMER);
                assertThat(customer.isSelected(nestedName)).isTrue();
                assertThat(customer.get(nestedName)).isEqualTo(row.get(CUSTOMER_NAME));
                // Selected under another join, or not at all: not visible in this scope.
                assertThat(customer.isSelected(nestedVip)).isFalse();
                assertThat(customer.get(nestedVip)).isNull();
                assertThat(row.scoped(ITEMS).get(nestedName)).isNull();
            });
        }
    }

    @TckTest
    void ac_col_06_a_scoped_row_reads_columns_on_joins_below_its_scope_and_scopes_compose(TckDatabase db) {
        // Rooted at an item: its order, and that order's customer, are the nested models.
        TableField<OrderItemEntity, OrderItemEntity> itemRoot = TableField.root(OrderItemEntity.class);
        TableField<OrderItemEntity, OrderEntity> itemOrder = TableField.join(itemRoot, "order", INNER);
        TableField<OrderEntity, CustomerEntity> itemOrderCustomer = TableField.join(itemOrder, "customer", INNER);
        ColumnField<OrderView, OrderEntity, Long> orderId =
                ColumnField.of(OrderView.class, itemOrder, "id", Long.class);
        ColumnField<OrderView, CustomerEntity, Long> customerId =
                ColumnField.of(OrderView.class, itemOrderCustomer, "id", Long.class);
        ColumnField<OrderView, CustomerEntity, String> customerName =
                ColumnField.of(OrderView.class, itemOrderCustomer, "name", String.class);
        // The nested order model, declared on its own root, with a column on the join below it.
        TableField<OrderEntity, CustomerEntity> nestedCustomer = TableField.join(ROOT, "customer", INNER);
        ColumnField<OrderView, OrderEntity, Long> nestedOrderId =
                ColumnField.of(OrderView.class, ROOT, "id", Long.class);
        ColumnField<OrderView, CustomerEntity, Long> nestedCustomerId =
                ColumnField.of(OrderView.class, nestedCustomer, "id", Long.class);
        // Two levels down: the customer model, declared on its own root.
        ColumnField<CustomerView, CustomerEntity, Long> innerId =
                ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "id", Long.class);
        ColumnField<CustomerView, CustomerEntity, String> innerName =
                ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "name", String.class);
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                CriteriaQuery<Tuple> q = cb.createTupleQuery();
                Root<OrderItemEntity> root = q.from(OrderItemEntity.class);
                JoinContext ctx = JoinContext.of(root, cb);
                RowSelection selection = RowSelection.of(List.of(orderId, customerId, customerName));
                // Item 1 is on order 1, whose customer is 38: the two ids differ.
                q.multiselect(selection.selections(ctx)).where(cb.equal(root.get("id"), 1L));
                Row row = selection.row(em.createQuery(q).getSingleResult());
                assertThat(row.get(orderId)).isEqualTo(1L);
                assertThat(row.get(customerId)).isEqualTo(38L);

                Row order = row.scoped(itemOrder);
                assertThat(order.get(nestedOrderId)).isEqualTo(1L);
                // The customer's id, not the order's: the whole path is re-rooted, not just its last attribute.
                assertThat(order.isSelected(nestedCustomerId)).isTrue();
                assertThat(order.get(nestedCustomerId)).isEqualTo(38L);

                Row customer = order.scoped(nestedCustomer);
                assertThat(customer.get(innerId)).isEqualTo(38L);
                assertThat(customer.get(innerName)).isEqualTo(row.get(customerName)).isNotNull();
                // A customer column the query did not select is not found under any other join.
                assertThat(customer.isSelected(ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "createdAt",
                        LocalDateTime.class))).isFalse();
            });
        }
    }

    private record Expected(long itemId, String status, BigDecimal total, LocalDateTime placedAt, boolean vip,
            String customerName, LocalDateTime customerCreatedAt, int quantity, BigDecimal unitPrice) {

        ClassModel toClass() {
            ClassModel m = new ClassModel();
            m.itemId = itemId;
            m.status = status;
            m.statusCode = OrderStatus.valueOf(status);
            m.total = total;
            m.placedAt = placedAt;
            m.vip = vip;
            m.customerName = customerName;
            m.customerCreatedAt = customerCreatedAt;
            m.quantity = quantity;
            m.unitPrice = unitPrice;
            return m;
        }

        RecordModel toRecord() {
            return new RecordModel(itemId, status, OrderStatus.valueOf(status), total, placedAt, vip, customerName,
                    customerCreatedAt, quantity, unitPrice, null);
        }
    }

    /** The same rows read straight through JDBC, the reference for what each type should come back as. */
    private static List<Expected> expected(TckDatabase db) {
        return jdbc(db, EXPECTED_SQL, rs -> new Expected(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getObject(4, LocalDateTime.class), rs.getBoolean(5), rs.getString(6),
                rs.getObject(7, LocalDateTime.class), rs.getInt(8), rs.getBigDecimal(9)));
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private static <T> List<T> jdbc(TckDatabase db, String sql, RowReader<T> reader) {
        List<T> result = new ArrayList<>();
        try (Connection c = db.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                result.add(reader.read(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }
}
