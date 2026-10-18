package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderTotals {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final OrderedColumnField<OrderTotals, OrderEntity, Long> ID = ColumnField.of(OrderTotals.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<OrderTotals, OrderEntity, String> STATUS = ColumnField.of(OrderTotals.class,
            ROOT, "status", String.class).named("status");

    public static final OrderedColumnField<OrderTotals, OrderEntity, BigDecimal> TOTAL = ColumnField.of(OrderTotals.class,
            ROOT, "total", BigDecimal.class).named("total");

    public static final SelectSet<OrderTotals> ALL = SelectSet.of(ID, STATUS, TOTAL);

    public static final SelectSet<OrderTotals> DEFAULT = ALL;

    public static final PrimaryKey<OrderTotals, Long> KEY = PrimaryKey.of(ID);

    private QOrderTotals() {
    }

    public static SourceQuery.Builder<OrderEntity, OrderTotals, OrderTotals> source() {
        return SourceQuery.builder(OrderTotals.class, ROOT, ALL, KEY);
    }

    @Incubating
    public static ModelQuery.Builder<OrderTotals, Long, OrderTotals> query(
            SourceQuery<OrderTotals> source) {
        return ModelQuery.builder(Rows.ROOT, Rows.MAPPER, source).primaryKey(Rows.KEY);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<OrderTotals> fields() {
        return Index.INSTANCE;
    }

    @com.rey.modelquery.annotations.Incubating
    public static final class Rows {
        public static final com.rey.modelquery.core.TableField<shop.OrderTotals, shop.OrderTotals> ROOT = com.rey.modelquery.core.TableField.source(shop.OrderTotals.class);

        public static final com.rey.modelquery.core.OrderedColumnField<shop.OrderTotals, shop.OrderTotals, java.lang.Long> ID = com.rey.modelquery.core.ColumnField.over(shop.OrderTotals.class,
                ROOT, shop.QOrderTotals.ID).named("id");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.OrderTotals, shop.OrderTotals, java.lang.String> STATUS = com.rey.modelquery.core.ColumnField.over(shop.OrderTotals.class,
                ROOT, shop.QOrderTotals.STATUS).named("status");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.OrderTotals, shop.OrderTotals, java.math.BigDecimal> TOTAL = com.rey.modelquery.core.ColumnField.over(shop.OrderTotals.class,
                ROOT, shop.QOrderTotals.TOTAL).named("total");

        public static final com.rey.modelquery.core.SelectSet<shop.OrderTotals> ALL = com.rey.modelquery.core.SelectSet.of(ID,
                STATUS, TOTAL);

        public static final com.rey.modelquery.core.SelectSet<shop.OrderTotals> DEFAULT = ALL;

        public static final com.rey.modelquery.core.PrimaryKey<shop.OrderTotals, java.lang.Long> KEY = com.rey.modelquery.core.PrimaryKey.of(ID);

        public static final com.rey.modelquery.core.RowMapper<shop.OrderTotals> MAPPER = shop.QOrderTotals.Rows::map;

        private Rows() {
        }

        private static shop.OrderTotals map(com.rey.modelquery.core.Row row) {
            return new shop.OrderTotals(row.get(ID), row.get(STATUS), row.get(TOTAL));
        }
    }

    private static final class Index {
        static final FieldIndex<OrderTotals> INSTANCE = FieldIndex.builder(OrderTotals.class)
                .select(ID, STATUS, TOTAL)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
