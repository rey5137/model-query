package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.WindowField;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QRankedOrder {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final OrderedColumnField<RankedOrder, OrderEntity, Long> ID = ColumnField.of(RankedOrder.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<RankedOrder, OrderEntity, String> CUSTOMER = ColumnField.of(RankedOrder.class,
            ROOT, "customer", String.class).named("customer");

    public static final WindowField<RankedOrder, Long> RANK = WindowField.of(RankedOrder.class,
            WindowFunction.ROW_NUMBER, Long.class, null, List.of(CUSTOMER),
            List.of(ColumnField.of(RankedOrder.class, ROOT, "createdAt", Instant.class).desc()),
            WindowFrame.NONE).named("rank");

    public static final WindowField<RankedOrder, BigDecimal> RUNNING = WindowField.of(RankedOrder.class,
            WindowFunction.SUM, BigDecimal.class, ColumnField.of(RankedOrder.class, ROOT, "total",
            BigDecimal.class), List.of(), List.of(ColumnField.of(RankedOrder.class, ROOT,
            "createdAt", Instant.class).asc()), WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW)
            .named("running");

    public static final SelectSet<RankedOrder> ALL = SelectSet.of(ID, CUSTOMER, RANK, RUNNING);

    public static final SelectSet<RankedOrder> DEFAULT = ALL;

    public static final PrimaryKey<RankedOrder, Long> KEY = PrimaryKey.of(ID);

    private QRankedOrder() {
    }

    public static SourceQuery.Builder<OrderEntity, RankedOrder, RankedOrder> source() {
        return SourceQuery.builder(RankedOrder.class, ROOT, ALL, KEY);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<RankedOrder> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<RankedOrder> INSTANCE = FieldIndex.builder(RankedOrder.class)
                .select(ID, CUSTOMER, RANK, RUNNING)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
