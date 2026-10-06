package ins;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderArchiveRow {
    public static final TableField<OrderArchiveEntity, OrderArchiveEntity> ROOT = TableField.root(OrderArchiveEntity.class);

    public static final OrderedColumnField<OrderArchiveRow, OrderArchiveEntity, Long> ORDER_ID = ColumnField.of(OrderArchiveRow.class,
            ROOT, "orderId", Long.class).named("orderId");

    public static final OrderedColumnField<OrderArchiveRow, OrderArchiveEntity, String> STATUS = ColumnField.of(OrderArchiveRow.class,
            ROOT, "status", String.class).named("status");

    public static final OrderedColumnField<OrderArchiveRow, OrderArchiveEntity, Boolean> PAID = ColumnField.of(OrderArchiveRow.class,
            ROOT, "paid", Boolean.class).named("paid");

    @Incubating
    public static final InsertColumns<OrderArchiveRow, OrderArchiveEntity> INSERT_COLUMNS = InsertColumns.<OrderArchiveRow, OrderArchiveEntity>of(ROOT)
            .addKey(ORDER_ID, OrderArchiveRow::getOrderId)
            .add(STATUS, OrderArchiveRow::getStatus)
            .add(PAID, OrderArchiveRow::isPaid);

    private QOrderArchiveRow() {
    }

    @Incubating
    public static <S> ModelInsert.SelectStart<OrderArchiveEntity, OrderArchiveRow> insertFrom(
            TableField<S, S> sourceRoot) {
        return ModelInsert.select(INSERT_COLUMNS, sourceRoot);
    }

    @Incubating
    public static ValuesInsert.Rows<OrderArchiveEntity, Long, OrderArchiveRow> insert(
            List<? extends OrderArchiveRow> rows) {
        return ValuesInsert.builder(INSERT_COLUMNS, Long.class, rows);
    }

    @Incubating
    public static ModelPersist<OrderArchiveEntity, Long, OrderArchiveRow> persist(
            OrderArchiveRow row) {
        return ModelPersist.of(INSERT_COLUMNS, Long.class, row);
    }
}
