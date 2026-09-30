package shop;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.TableField;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderView {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final ColumnField<OrderView, OrderEntity, Long> ID = ColumnField.of(OrderView.class,
            ROOT, "id", Long.class);

    public static final ColumnField<OrderView, OrderEntity, String> STATUS = ColumnField.of(OrderView.class,
            ROOT, "status", String.class);

    public static final ColumnField<OrderView, OrderEntity, BigDecimal> TOTAL = ColumnField.of(OrderView.class,
            ROOT, "total", BigDecimal.class);

    public static final ColumnField<OrderView, OrderEntity, String> CITY = ColumnField.of(OrderView.class,
            ROOT, "address.city", String.class);

    public static final ColumnField<OrderView, OrderEntity, String> NOTES = ColumnField.of(OrderView.class,
            ROOT, "notes", String.class);

    public static final ColumnField<OrderView, OrderEntity, Boolean> PAID = ColumnField.of(OrderView.class,
            ROOT, "paid", Boolean.class);

    public static final ColumnSet<OrderView> ALL = ColumnSet.of(ID, STATUS, TOTAL, CITY, NOTES,
            PAID);

    public static final ColumnSet<OrderView> DEFAULT = ALL.without(NOTES);

    public static final PrimaryKey<OrderView, Long> KEY = PrimaryKey.of(ID);

    public static final RowMapper<OrderView> MAPPER = QOrderView::map;

    private QOrderView() {
    }

    public static ModelQuery.Builder<OrderEntity, Long, OrderView> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static OrderView map(Row row) {
        OrderView m = new OrderView();
        if (row.isSelected(ID)) {
            m.setId(row.get(ID));
        }
        if (row.isSelected(STATUS)) {
            m.setStatus(row.get(STATUS));
        }
        if (row.isSelected(TOTAL)) {
            m.setTotal(row.get(TOTAL));
        }
        if (row.isSelected(CITY)) {
            m.setCity(row.get(CITY));
        }
        if (row.isSelected(NOTES)) {
            m.setNotes(row.get(NOTES));
        }
        if (row.isSelected(PAID)) {
            m.setPaid(row.get(PAID));
        }
        return m;
    }
}
