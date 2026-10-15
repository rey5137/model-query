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
public final class QRefundFeedItem {
    public static final TableField<RefundEntity, RefundEntity> ROOT = TableField.root(RefundEntity.class);

    public static final OrderedColumnField<RefundFeedItem, RefundEntity, String> KIND = ColumnField.of(RefundFeedItem.class,
            ROOT, "kind", String.class).named("kind");

    public static final OrderedColumnField<RefundFeedItem, RefundEntity, Long> ID = ColumnField.of(RefundFeedItem.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<RefundFeedItem, RefundEntity, String> TITLE = ColumnField.of(RefundFeedItem.class,
            ROOT, "reason", String.class).named("title");

    public static final OrderedColumnField<RefundFeedItem, RefundEntity, Instant> AT = ColumnField.of(RefundFeedItem.class,
            ROOT, "refundedAt", Instant.class).named("at");

    public static final SelectSet<RefundFeedItem> ALL = SelectSet.of(KIND, ID, TITLE, AT);

    public static final SelectSet<RefundFeedItem> DEFAULT = ALL;

    public static final PrimaryKey<RefundFeedItem, List<Object>> KEY = PrimaryKey.composite(KIND,
            ID);

    private QRefundFeedItem() {
    }

    public static SourceQuery.Builder<RefundEntity, RefundFeedItem, FeedItem> source() {
        return SourceQuery.builder(RefundFeedItem.class, ROOT, ALL, KEY).shape(FeedItem.class).discriminator(KIND, "REFUND");
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<RefundFeedItem> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<RefundFeedItem> INSTANCE = FieldIndex.builder(RefundFeedItem.class)
                .select(KIND, ID, TITLE, AT)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
