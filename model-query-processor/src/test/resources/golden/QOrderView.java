package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderView {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final OrderedColumnField<OrderView, OrderEntity, Long> ID = ColumnField.of(OrderView.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<OrderView, OrderEntity, String> STATUS = ColumnField.of(OrderView.class,
            ROOT, "status", String.class).named("status");

    public static final OrderedColumnField<OrderView, OrderEntity, BigDecimal> TOTAL = ColumnField.of(OrderView.class,
            ROOT, "total", BigDecimal.class).named("total");

    public static final OrderedColumnField<OrderView, OrderEntity, String> CITY = ColumnField.of(OrderView.class,
            ROOT, "address.city", String.class).named("city");

    public static final OrderedColumnField<OrderView, OrderEntity, String> NOTES = ColumnField.of(OrderView.class,
            ROOT, "notes", String.class).named("notes");

    public static final OrderedColumnField<OrderView, OrderEntity, Boolean> PAID = ColumnField.of(OrderView.class,
            ROOT, "paid", Boolean.class).named("paid");

    public static final SelectSet<OrderView> ALL = SelectSet.of(ID, STATUS, TOTAL, CITY, NOTES,
            PAID);

    public static final SelectSet<OrderView> DEFAULT = ALL.without(NOTES);

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

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<OrderView> fields() {
        return Index.INSTANCE;
    }

    @Incubating
    public static ModelDelete.Builder<OrderEntity, Long, OrderView> delete() {
        return ModelDelete.builder(ROOT).primaryKey(KEY);
    }

    private static final class Index {
        static final FieldIndex<OrderView> INSTANCE = FieldIndex.builder(OrderView.class)
                .select(ID, STATUS, TOTAL, CITY, NOTES, PAID)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
