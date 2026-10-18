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
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QRows {
    public static final TableField<Item, Item> ROOT = TableField.root(Item.class);

    public static final OrderedColumnField<shop.Rows, Item, Long> ID = ColumnField.of(shop.Rows.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<shop.Rows, Item, String> NAME = ColumnField.of(shop.Rows.class,
            ROOT, "name", String.class).named("name");

    public static final WindowField<shop.Rows, Long> RANK = WindowField.of(shop.Rows.class,
            WindowFunction.ROW_NUMBER, Long.class, null, List.of(), List.of(ID.asc()),
            WindowFrame.NONE).named("rank");

    public static final SelectSet<shop.Rows> ALL = SelectSet.of(ID, NAME, RANK);

    public static final SelectSet<shop.Rows> DEFAULT = ALL.without(NAME);

    public static final PrimaryKey<shop.Rows, Long> KEY = PrimaryKey.of(ID);

    private QRows() {
    }

    public static SourceQuery.Builder<Item, shop.Rows, shop.Rows> source() {
        return SourceQuery.builder(shop.Rows.class, ROOT, ALL, KEY);
    }

    @Incubating
    public static ModelQuery.Builder<shop.Rows, Long, shop.Rows> query(
            SourceQuery<shop.Rows> source) {
        return ModelQuery.builder(Rows.ROOT, Rows.MAPPER, source).primaryKey(Rows.KEY);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<shop.Rows> fields() {
        return Index.INSTANCE;
    }

    @com.rey.modelquery.annotations.Incubating
    public static final class Rows {
        public static final com.rey.modelquery.core.TableField<shop.Rows, shop.Rows> ROOT = com.rey.modelquery.core.TableField.source(shop.Rows.class);

        public static final com.rey.modelquery.core.OrderedColumnField<shop.Rows, shop.Rows, java.lang.Long> ID = com.rey.modelquery.core.ColumnField.over(shop.Rows.class,
                ROOT, shop.QRows.ID).named("id");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.Rows, shop.Rows, java.lang.String> NAME = com.rey.modelquery.core.ColumnField.over(shop.Rows.class,
                ROOT, shop.QRows.NAME).named("name");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.Rows, shop.Rows, java.lang.Long> RANK = com.rey.modelquery.core.ColumnField.over(shop.Rows.class,
                ROOT, shop.QRows.RANK).named("rank");

        public static final com.rey.modelquery.core.SelectSet<shop.Rows> ALL = com.rey.modelquery.core.SelectSet.of(ID,
                NAME, RANK);

        public static final com.rey.modelquery.core.SelectSet<shop.Rows> DEFAULT = ALL.without(NAME);

        public static final com.rey.modelquery.core.PrimaryKey<shop.Rows, java.lang.Long> KEY = com.rey.modelquery.core.PrimaryKey.of(ID);

        public static final com.rey.modelquery.core.RowMapper<shop.Rows> MAPPER = shop.QRows.Rows::map;

        private Rows() {
        }

        private static shop.Rows map(com.rey.modelquery.core.Row row) {
            return new shop.Rows(row.get(ID), row.get(NAME), row.get(RANK));
        }
    }

    private static final class Index {
        static final FieldIndex<shop.Rows> INSTANCE = FieldIndex.builder(shop.Rows.class)
                .select(ID, NAME, RANK)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
