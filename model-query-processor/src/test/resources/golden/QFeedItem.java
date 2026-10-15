package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
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

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<FeedItem> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<FeedItem> INSTANCE = FieldIndex.builder(FeedItem.class)
                .select(KIND, ID, TITLE, AT)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
