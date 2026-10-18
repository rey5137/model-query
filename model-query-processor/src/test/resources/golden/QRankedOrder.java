package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
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

    @Incubating
    public static ModelQuery.Builder<RankedOrder, Long, RankedOrder> query(
            SourceQuery<RankedOrder> source) {
        return ModelQuery.builder(Rows.ROOT, Rows.MAPPER, source).primaryKey(Rows.KEY);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<RankedOrder> fields() {
        return Index.INSTANCE;
    }

    @com.rey.modelquery.annotations.Incubating
    public static final class Rows {
        public static final com.rey.modelquery.core.TableField<shop.RankedOrder, shop.RankedOrder> ROOT = com.rey.modelquery.core.TableField.source(shop.RankedOrder.class);

        public static final com.rey.modelquery.core.OrderedColumnField<shop.RankedOrder, shop.RankedOrder, java.lang.Long> ID = com.rey.modelquery.core.ColumnField.over(shop.RankedOrder.class,
                ROOT, shop.QRankedOrder.ID).named("id");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.RankedOrder, shop.RankedOrder, java.lang.String> CUSTOMER = com.rey.modelquery.core.ColumnField.over(shop.RankedOrder.class,
                ROOT, shop.QRankedOrder.CUSTOMER).named("customer");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.RankedOrder, shop.RankedOrder, java.lang.Long> RANK = com.rey.modelquery.core.ColumnField.over(shop.RankedOrder.class,
                ROOT, shop.QRankedOrder.RANK).named("rank");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.RankedOrder, shop.RankedOrder, java.math.BigDecimal> RUNNING = com.rey.modelquery.core.ColumnField.over(shop.RankedOrder.class,
                ROOT, shop.QRankedOrder.RUNNING).named("running");

        public static final com.rey.modelquery.core.SelectSet<shop.RankedOrder> ALL = com.rey.modelquery.core.SelectSet.of(ID,
                CUSTOMER, RANK, RUNNING);

        public static final com.rey.modelquery.core.SelectSet<shop.RankedOrder> DEFAULT = ALL;

        public static final com.rey.modelquery.core.PrimaryKey<shop.RankedOrder, java.lang.Long> KEY = com.rey.modelquery.core.PrimaryKey.of(ID);

        public static final com.rey.modelquery.core.RowMapper<shop.RankedOrder> MAPPER = shop.QRankedOrder.Rows::map;

        private Rows() {
        }

        private static shop.RankedOrder map(com.rey.modelquery.core.Row row) {
            return new shop.RankedOrder(row.get(ID), row.get(CUSTOMER), row.get(RANK),
                    row.get(RUNNING));
        }
    }

    private static final class Index {
        static final FieldIndex<RankedOrder> INSTANCE = FieldIndex.builder(RankedOrder.class)
                .select(ID, CUSTOMER, RANK, RUNNING)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
