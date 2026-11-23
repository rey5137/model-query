package models;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.MapChildField;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.criteria.JoinType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QTaggedMapCard {
    public static final TableField<TaggedEntity, TaggedEntity> ROOT = TableField.root(TaggedEntity.class);

    public static final TableField<TaggedEntity, TaggedEntity> SIBLINGS_TABLE = TableField.<TaggedEntity, TaggedEntity>join(ROOT,
            "siblings", JoinType.LEFT);

    public static final OrderedColumnField<TaggedMapCard, TaggedEntity, Long> ID = ColumnField.of(TaggedMapCard.class,
            ROOT, "id", Long.class).named("id");

    public static final SelectSet<TaggedMapCard> ALL = SelectSet.of(ID);

    public static final SelectSet<TaggedMapCard> DEFAULT = ALL;

    public static final PrimaryKey<TaggedMapCard, Long> KEY = PrimaryKey.of(ID);

    @Incubating
    public static final MapChildField<TaggedMapCard, String, String> LABELS = new MapChildField<TaggedMapCard, String, String>() {
        private final ColumnField<TaggedMapCard, ?, ?> key = ColumnField.of(TaggedMapCard.class,
                ROOT, "id", Long.class);

        private final TableField<?, ?> owner = ROOT;

        @Override
        public String name() {
            return "labels";
        }

        @Override
        public ColumnField<TaggedMapCard, ?, ?> key() {
            return key;
        }

        @Override
        public TableField<?, ?> owner() {
            return owner;
        }

        @Override
        public String attribute() {
            return "labels";
        }

        @Override
        public Class<String> keyType() {
            return String.class;
        }

        @Override
        public Class<String> valueType() {
            return String.class;
        }

        @Override
        public TaggedMapCard with(TaggedMapCard parent, Map<String, String> entries) {
            return new TaggedMapCard(parent.id(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
        }
    };

    public static final RowMapper<TaggedMapCard> MAPPER = QTaggedMapCard::map;

    private QTaggedMapCard() {
    }

    public static ModelQuery.Builder<TaggedEntity, Long, TaggedMapCard> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static TaggedMapCard map(Row row) {
        return new TaggedMapCard(row.get(ID), Map.of());
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<TaggedMapCard> fields() {
        return Index.INSTANCE;
    }

    @Incubating
    public static ModelDelete.Builder<TaggedEntity, Long, TaggedMapCard> delete() {
        return ModelDelete.builder(ROOT).primaryKey(KEY);
    }

    private static final class Index {
        static final FieldIndex<TaggedMapCard> INSTANCE = FieldIndex.builder(TaggedMapCard.class)
                .select(ID)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .mapChild(LABELS)
                .build();
    }
}
