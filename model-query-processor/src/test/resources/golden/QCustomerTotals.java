package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.WindowField;
import java.math.BigDecimal;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QCustomerTotals {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final OrderedColumnField<CustomerTotals, OrderEntity, String> CUSTOMER = ColumnField.of(CustomerTotals.class,
            ROOT, "customer", String.class).named("customer");

    public static final AggregateField<CustomerTotals, BigDecimal> TOTAL = Agg.sum(
            ColumnField.of(CustomerTotals.class, ROOT, "total", BigDecimal.class)).named("total");

    public static final AggregateField<CustomerTotals, Long> ORDERS = Agg.<CustomerTotals>count(ROOT)
            .named("orders");

    public static final WindowField<CustomerTotals, Long> PLACING = WindowField.of(CustomerTotals.class,
            WindowFunction.RANK, Long.class, null, List.of(), List.of(TOTAL.desc()),
            WindowFrame.NONE).named("placing");

    public static final WindowField<CustomerTotals, BigDecimal> GRAND = WindowField.of(CustomerTotals.class,
            WindowFunction.SUM, BigDecimal.class, TOTAL, List.of(), List.of(CUSTOMER.asc()),
            WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING).named("grand");

    public static final SelectSet<CustomerTotals> ALL = SelectSet.of(CUSTOMER, TOTAL, ORDERS,
            PLACING, GRAND);

    public static final SelectSet<CustomerTotals> DEFAULT = ALL;

    public static final SelectSet<CustomerTotals> GROUP_KEYS = SelectSet.of(CUSTOMER);

    public static final PrimaryKey<CustomerTotals, String> KEY = PrimaryKey.of(CUSTOMER);

    private QCustomerTotals() {
    }

    public static SourceQuery.Builder<OrderEntity, CustomerTotals, CustomerTotals> source() {
        return SourceQuery.builder(CustomerTotals.class, ROOT, ALL, KEY, GROUP_KEYS);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<CustomerTotals> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<CustomerTotals> INSTANCE = FieldIndex.builder(CustomerTotals.class)
                .select(CUSTOMER, TOTAL, ORDERS, PLACING, GRAND)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
