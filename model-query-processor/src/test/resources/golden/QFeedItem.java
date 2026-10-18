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
import java.time.Instant;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QFeedItem {
    public static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    public static final OrderedColumnField<FeedItem, OrderEntity, String> KIND = ColumnField.of(FeedItem.class,
            ROOT, "kind", String.class).named("kind");

    public static final OrderedColumnField<FeedItem, OrderEntity, Long> ID = ColumnField.of(FeedItem.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<FeedItem, OrderEntity, String> TITLE = ColumnField.of(FeedItem.class,
            ROOT, "title", String.class).named("title");

    public static final OrderedColumnField<FeedItem, OrderEntity, Instant> AT = ColumnField.of(FeedItem.class,
            ROOT, "at", Instant.class).named("at");

    public static final SelectSet<FeedItem> ALL = SelectSet.of(KIND, ID, TITLE, AT);

    public static final SelectSet<FeedItem> DEFAULT = ALL;

    public static final PrimaryKey<FeedItem, List<Object>> KEY = PrimaryKey.composite(KIND, ID);

    private QFeedItem() {
    }

    public static SourceQuery.Builder<OrderEntity, FeedItem, FeedItem> source() {
        return SourceQuery.builder(FeedItem.class, ROOT, ALL, KEY).discriminator(KIND, "ORDER");
    }

    @Incubating
    public static ModelQuery.Builder<FeedItem, List<Object>, FeedItem> query(
            SourceQuery<FeedItem> source) {
        return ModelQuery.builder(Rows.ROOT, Rows.MAPPER, source).primaryKey(Rows.KEY);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<FeedItem> fields() {
        return Index.INSTANCE;
    }

    @com.rey.modelquery.annotations.Incubating
    public static final class Rows {
        public static final com.rey.modelquery.core.TableField<shop.FeedItem, shop.FeedItem> ROOT = com.rey.modelquery.core.TableField.source(shop.FeedItem.class);

        public static final com.rey.modelquery.core.OrderedColumnField<shop.FeedItem, shop.FeedItem, java.lang.String> KIND = com.rey.modelquery.core.ColumnField.over(shop.FeedItem.class,
                ROOT, shop.QFeedItem.KIND).named("kind");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.FeedItem, shop.FeedItem, java.lang.Long> ID = com.rey.modelquery.core.ColumnField.over(shop.FeedItem.class,
                ROOT, shop.QFeedItem.ID).named("id");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.FeedItem, shop.FeedItem, java.lang.String> TITLE = com.rey.modelquery.core.ColumnField.over(shop.FeedItem.class,
                ROOT, shop.QFeedItem.TITLE).named("title");

        public static final com.rey.modelquery.core.OrderedColumnField<shop.FeedItem, shop.FeedItem, java.time.Instant> AT = com.rey.modelquery.core.ColumnField.over(shop.FeedItem.class,
                ROOT, shop.QFeedItem.AT).named("at");

        public static final com.rey.modelquery.core.SelectSet<shop.FeedItem> ALL = com.rey.modelquery.core.SelectSet.of(KIND,
                ID, TITLE, AT);

        public static final com.rey.modelquery.core.SelectSet<shop.FeedItem> DEFAULT = ALL;

        public static final com.rey.modelquery.core.PrimaryKey<shop.FeedItem, java.util.List<java.lang.Object>> KEY = com.rey.modelquery.core.PrimaryKey.composite(KIND,
                ID);

        public static final com.rey.modelquery.core.RowMapper<shop.FeedItem> MAPPER = shop.QFeedItem.Rows::map;

        private Rows() {
        }

        private static shop.FeedItem map(com.rey.modelquery.core.Row row) {
            return new shop.FeedItem(row.get(KIND), row.get(ID), row.get(TITLE), row.get(AT));
        }
    }

    private static final class Index {
        static final FieldIndex<FeedItem> INSTANCE = FieldIndex.builder(FeedItem.class)
                .select(KIND, ID, TITLE, AT)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
