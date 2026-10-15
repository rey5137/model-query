package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QStatusTotals {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final OrderedColumnField<StatusTotals, OrderEntity, String> STATUS = ColumnField.of(StatusTotals.class,
            ROOT, "status", String.class).named("status");

    public static final AggregateField<StatusTotals, Long> ORDERS = Agg.<StatusTotals>count(ROOT)
            .named("orders");

    public static final SelectSet<StatusTotals> ALL = SelectSet.of(STATUS);

    public static final SelectSet<StatusTotals> DEFAULT = ALL;

    public static final SelectSet<StatusTotals> GROUP_KEYS = SelectSet.of(STATUS);

    public static final PrimaryKey<StatusTotals, String> KEY = PrimaryKey.of(STATUS);

    private QStatusTotals() {
    }

    public static SourceQuery.Builder<OrderEntity, StatusTotals, StatusTotals> source() {
        return SourceQuery.builder(StatusTotals.class, ROOT, ALL, KEY, GROUP_KEYS);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<StatusTotals> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<StatusTotals> INSTANCE = FieldIndex.builder(StatusTotals.class)
                .select(STATUS, ORDERS)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
