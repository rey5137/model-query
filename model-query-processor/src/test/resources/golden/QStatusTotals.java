package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
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

    public static final SelectSet<StatusTotals> ALL = SelectSet.of(STATUS, ORDERS);

    public static final SelectSet<StatusTotals> DEFAULT = ALL;

    public static final SelectSet<StatusTotals> GROUP_KEYS = SelectSet.of(STATUS);

    public static final PrimaryKey<StatusTotals, String> KEY = PrimaryKey.of(STATUS);

    private QStatusTotals() {
    }

    public static SourceQuery.Builder<OrderEntity, StatusTotals, StatusTotals> source() {
        return SourceQuery.builder(StatusTotals.class, ROOT, ALL, KEY, GROUP_KEYS);
    }

    @Incubating
    public static ModelQuery.Builder<StatusTotals, String, StatusTotals> query(
            SourceQuery<StatusTotals> source) {
        return ModelQuery.builder(Rows.ROOT, Rows.MAPPER, source).primaryKey(Rows.KEY);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<StatusTotals> fields() {
        return Index.INSTANCE;
    }

    @com.rey.modelquery.annotations.Incubating
    public static final class Rows {
        public static final com.rey.modelquery.core.TableField<shop.StatusTotals, shop.StatusTotals> ROOT = com.rey.modelquery.core.TableField.source(shop.StatusTotals.class);

        public static final com.rey.modelquery.core.OrderedColumnField<shop.StatusTotals, shop.StatusTotals, java.lang.String> STATUS = com.rey.modelquery.core.ColumnField.over(shop.StatusTotals.class,
                ROOT, shop.QStatusTotals.STATUS).named("status");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.StatusTotals, shop.StatusTotals, java.lang.Long> ORDERS = com.rey.modelquery.core.ColumnField.over(shop.StatusTotals.class,
                ROOT, shop.QStatusTotals.ORDERS).named("orders");

        public static final com.rey.modelquery.core.SelectSet<shop.StatusTotals> ALL = com.rey.modelquery.core.SelectSet.of(STATUS,
                ORDERS);

        public static final com.rey.modelquery.core.SelectSet<shop.StatusTotals> DEFAULT = ALL;

        public static final com.rey.modelquery.core.PrimaryKey<shop.StatusTotals, java.lang.String> KEY = com.rey.modelquery.core.PrimaryKey.of(STATUS);

        public static final com.rey.modelquery.core.RowMapper<shop.StatusTotals> MAPPER = shop.QStatusTotals.Rows::map;

        private Rows() {
        }

        private static shop.StatusTotals map(com.rey.modelquery.core.Row row) {
            return new shop.StatusTotals(row.get(STATUS), row.get(ORDERS));
        }
    }

    private static final class Index {
        static final FieldIndex<StatusTotals> INSTANCE = FieldIndex.builder(StatusTotals.class)
                .select(STATUS, ORDERS)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
