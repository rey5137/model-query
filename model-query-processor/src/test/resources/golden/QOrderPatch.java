package patch;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderPatch {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final TableField<OrderEntity, CustomerEntity> CUSTOMER_TABLE = TableField.<OrderEntity, CustomerEntity>join(ROOT,
            "customer", JoinType.LEFT);

    public static final ColumnField<OrderPatch, OrderEntity, Long> ID = ColumnField.of(OrderPatch.class,
            ROOT, "id", Long.class).named("id");

    public static final ColumnField<OrderPatch, OrderEntity, OrderStatus> STATUS = ColumnField.of(OrderPatch.class,
            ROOT, "status", OrderStatus.class, String.class, OrderStatus.Converter.INSTANCE)
            .named("status");

    public static final ColumnField<OrderPatch, OrderEntity, BigDecimal> TOTAL = ColumnField.of(OrderPatch.class,
            ROOT, "total", BigDecimal.class).named("total");

    public static final ColumnField<OrderPatch, OrderEntity, String> NOTE = ColumnField.of(OrderPatch.class,
            ROOT, "note", String.class).named("note");

    public static final ColumnField<OrderPatch, OrderEntity, Long> CUSTOMER_ID = ColumnField.of(OrderPatch.class,
            ROOT, "customer", Long.class).named("customerId");

    public static final ColumnField<OrderPatch, OrderEntity, String> CITY = ColumnField.of(OrderPatch.class,
            ROOT, "address.city", String.class).named("city");

    public static final ColumnField<OrderPatch, CustomerEntity, String> CUSTOMER_COUNTRY = ColumnField.of(OrderPatch.class,
            CUSTOMER_TABLE, "country", String.class);

    private QOrderPatch() {
    }

    @Incubating
    public static OrderPatchChanges changes() {
        return new OrderPatchChanges();
    }

    @Incubating
    public static ModelUpdate.Builder<OrderEntity, Long, OrderPatch> update(
            Changes<OrderPatch> changes) {
        return ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(changes);
    }

    @Incubating
    public static ModelDelete.Builder<OrderEntity, Long, OrderPatch> delete() {
        return ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID));
    }
}
