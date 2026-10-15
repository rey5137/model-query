package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QRankedRow {
    public static final TableField<RankedOrder, RankedOrder> ROOT = TableField.source(RankedOrder.class);

    public static final OrderedColumnField<RankedRow, RankedOrder, Long> ID = ColumnField.over(RankedRow.class,
            ROOT, QRankedOrder.ID).named("id");

    public static final OrderedColumnField<RankedRow, RankedOrder, String> CUSTOMER = ColumnField.over(RankedRow.class,
            ROOT, QRankedOrder.CUSTOMER).named("customer");

    public static final OrderedColumnField<RankedRow, RankedOrder, Long> RANK = ColumnField.over(RankedRow.class,
            ROOT, QRankedOrder.RANK).named("rank");

    public static final OrderedColumnField<RankedRow, RankedOrder, BigDecimal> RUNNING = ColumnField.over(RankedRow.class,
            ROOT, QRankedOrder.RUNNING).named("running");

    public static final SelectSet<RankedRow> ALL = SelectSet.of(ID, CUSTOMER, RANK, RUNNING);

    public static final SelectSet<RankedRow> DEFAULT = ALL;

    public static final PrimaryKey<RankedRow, Long> KEY = PrimaryKey.of(ID);

    public static final RowMapper<RankedRow> MAPPER = QRankedRow::map;

    private QRankedRow() {
    }

    public static ModelQuery.Builder<RankedOrder, Long, RankedRow> query(
            SourceQuery<RankedOrder> source) {
        return ModelQuery.builder(ROOT, MAPPER, source).primaryKey(KEY);
    }

    private static RankedRow map(Row row) {
        return new RankedRow(row.get(ID), row.get(CUSTOMER), row.get(RANK), row.get(RUNNING));
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<RankedRow> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<RankedRow> INSTANCE = FieldIndex.builder(RankedRow.class)
                .select(ID, CUSTOMER, RANK, RUNNING)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
