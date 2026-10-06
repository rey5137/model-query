package ins;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import java.math.BigDecimal;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QNewOrder {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final OrderedColumnField<NewOrder, OrderEntity, String> EXTERNAL_REF = ColumnField.of(NewOrder.class,
            ROOT, "externalRef", String.class).named("externalRef");

    public static final ColumnField<NewOrder, OrderEntity, OrderStatus> STATUS = ColumnField.of(NewOrder.class,
            ROOT, "status", OrderStatus.class, String.class, OrderStatus.Converter.INSTANCE)
            .named("status");

    public static final OrderedColumnField<NewOrder, OrderEntity, BigDecimal> TOTAL = ColumnField.of(NewOrder.class,
            ROOT, "total", BigDecimal.class).named("total");

    public static final OrderedColumnField<NewOrder, OrderEntity, Long> CUSTOMER_ID = ColumnField.of(NewOrder.class,
            ROOT, "customer", Long.class).named("customerId");

    public static final OrderedColumnField<NewOrder, OrderEntity, String> CITY = ColumnField.of(NewOrder.class,
            ROOT, "address.city", String.class).named("city");

    @Incubating
    public static final InsertColumns<NewOrder, OrderEntity> INSERT_COLUMNS = InsertColumns.<NewOrder, OrderEntity>of(ROOT)
            .add(EXTERNAL_REF, NewOrder::externalRef)
            .add(STATUS, NewOrder::status)
            .add(TOTAL, NewOrder::total)
            .add(CUSTOMER_ID, NewOrder::customerId)
            .add(CITY, NewOrder::city);

    private QNewOrder() {
    }

    @Incubating
    public static <S> ModelInsert.SelectStart<OrderEntity, NewOrder> insertFrom(
            TableField<S, S> sourceRoot) {
        return ModelInsert.select(INSERT_COLUMNS, sourceRoot);
    }

    @Incubating
    public static ValuesInsert.Rows<OrderEntity, Long, NewOrder> insert(
            List<? extends NewOrder> rows) {
        return ValuesInsert.builder(INSERT_COLUMNS, Long.class, rows);
    }

    @Incubating
    public static ModelPersist<OrderEntity, Long, NewOrder> persist(NewOrder row) {
        return ModelPersist.of(INSERT_COLUMNS, Long.class, row);
    }
}
