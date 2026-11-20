package models;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
@Incubating
public final class QSpotRow {
    private static final TableField<Spot, Spot> ROOT = TableField.root(Spot.class);

    @Incubating
    public static final OrderedColumnField<SpotRow, Spot, String> CITY = ColumnField.of(SpotRow.class,
            ROOT, "city", String.class).named("city");

    @Incubating
    public static final OrderedColumnField<SpotRow, Spot, BigDecimal> LAT = ColumnField.of(SpotRow.class,
            ROOT, "geo.lat", BigDecimal.class).named("lat");

    @Incubating
    public static final ColumnField<SpotRow, Spot, String> CODE = ColumnField.of(SpotRow.class,
            ROOT, "code", String.class, Long.class, new CodeConverter()).named("code");

    @Incubating
    public static final SelectSet<SpotRow> ALL = SelectSet.of(CITY, LAT, CODE);

    @Incubating
    public static final SelectSet<SpotRow> DEFAULT = ALL;

    @Incubating
    public static final RowMapper<SpotRow> MAPPER = QSpotRow::map;

    private QSpotRow() {
    }

    private static SpotRow map(Row row) {
        return new SpotRow(row.get(CITY), row.get(LAT), row.get(CODE));
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<SpotRow> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<SpotRow> INSTANCE = FieldIndex.builder(SpotRow.class)
                .select(CITY, LAT, CODE)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
