package shop;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.TableField;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderSummary {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final ColumnField<OrderSummary, OrderEntity, Long> ID = ColumnField.of(OrderSummary.class,
            ROOT, "id", Long.class);

    public static final ColumnField<OrderSummary, OrderEntity, String> STATUS = ColumnField.of(OrderSummary.class,
            ROOT, "status", String.class);

    public static final ColumnField<OrderSummary, OrderEntity, String> CITY = ColumnField.of(OrderSummary.class,
            ROOT, "address.city", String.class);

    public static final ColumnField<OrderSummary, OrderEntity, String> NOTES = ColumnField.of(OrderSummary.class,
            ROOT, "notes", String.class);

    public static final ColumnSet<OrderSummary> ALL = ColumnSet.of(ID, STATUS, CITY, NOTES);

    public static final ColumnSet<OrderSummary> DEFAULT = ALL.without(NOTES);

    public static final PrimaryKey<OrderSummary, Long> KEY = PrimaryKey.of(ID);

    public static final RowMapper<OrderSummary> MAPPER = QOrderSummary::map;

    private QOrderSummary() {
    }

    public static ModelQuery.Builder<OrderEntity, Long, OrderSummary> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static OrderSummary map(Row row) {
        return new OrderSummary(row.get(ID), row.get(STATUS), row.get(CITY), row.get(NOTES), null);
    }
}
