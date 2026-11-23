package models;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValueChildField;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QTaggedCard {
    public static final TableField<TaggedEntity, TaggedEntity> ROOT = TableField.root(TaggedEntity.class);

    public static final TableField<TaggedEntity, TaggedEntity> SIBLINGS_TABLE = TableField.<TaggedEntity, TaggedEntity>join(ROOT,
            "siblings", JoinType.LEFT);

    public static final OrderedColumnField<TaggedCard, TaggedEntity, Long> ID = ColumnField.of(TaggedCard.class,
            ROOT, "id", Long.class).named("id");

    public static final SelectSet<TaggedCard> ALL = SelectSet.of(ID);

    public static final SelectSet<TaggedCard> DEFAULT = ALL;

    public static final PrimaryKey<TaggedCard, Long> KEY = PrimaryKey.of(ID);

    @Incubating
    public static final ValueChildField<TaggedCard, String> TAGS = new ValueChildField<TaggedCard, String>() {
        private final ColumnField<TaggedCard, ?, ?> key = ColumnField.of(TaggedCard.class, ROOT,
                "id", Long.class);

        private final TableField<?, ?> owner = ROOT;

        @Override
        public String name() {
            return "tags";
        }

        @Override
        public ColumnField<TaggedCard, ?, ?> key() {
            return key;
        }

        @Override
        public TableField<?, ?> owner() {
            return owner;
        }

        @Override
        public String attribute() {
            return "tags";
        }

        @Override
        public Class<String> elementType() {
            return String.class;
        }

        @Override
        public boolean isOrdered() {
            return false;
        }

        @Override
        public boolean ordersByValue() {
            return true;
        }

        @Override
        public TaggedCard with(TaggedCard parent, List<String> values) {
            return new TaggedCard(parent.id(),
                    Collections.unmodifiableList(new ArrayList<>(values)), parent.scores(),
                    parent.parentNotes());
        }
    };

    @Incubating
    public static final ValueChildField<TaggedCard, Integer> SCORES = new ValueChildField<TaggedCard, Integer>() {
        private final ColumnField<TaggedCard, ?, ?> key = ColumnField.of(TaggedCard.class, ROOT,
                "id", Long.class);

        private final TableField<?, ?> owner = ROOT;

        @Override
        public String name() {
            return "scores";
        }

        @Override
        public ColumnField<TaggedCard, ?, ?> key() {
            return key;
        }

        @Override
        public TableField<?, ?> owner() {
            return owner;
        }

        @Override
        public String attribute() {
            return "scores";
        }

        @Override
        public Class<Integer> elementType() {
            return Integer.class;
        }

        @Override
        public boolean isOrdered() {
            return true;
        }

        @Override
        public boolean ordersByValue() {
            return true;
        }

        @Override
        public TaggedCard with(TaggedCard parent, List<Integer> values) {
            return new TaggedCard(parent.id(), parent.tags(),
                    Collections.unmodifiableSet(new LinkedHashSet<>(values)), parent.parentNotes());
        }
    };

    @Incubating
    public static final ValueChildField<TaggedCard, String> PARENT_NOTES = new ValueChildField<TaggedCard, String>() {
        private final ColumnField<TaggedCard, ?, ?> key = ColumnField.of(TaggedCard.class, ROOT,
                "id", Long.class);

        private final TableField<?, ?> owner = TableField.<TaggedEntity, TaggedEntity>join(ROOT,
                "parent", JoinType.INNER);

        @Override
        public String name() {
            return "parentNotes";
        }

        @Override
        public ColumnField<TaggedCard, ?, ?> key() {
            return key;
        }

        @Override
        public TableField<?, ?> owner() {
            return owner;
        }

        @Override
        public String attribute() {
            return "notes";
        }

        @Override
        public Class<String> elementType() {
            return String.class;
        }

        @Override
        public boolean isOrdered() {
            return false;
        }

        @Override
        public boolean ordersByValue() {
            return false;
        }

        @Override
        public TaggedCard with(TaggedCard parent, List<String> values) {
            return new TaggedCard(parent.id(), parent.tags(), parent.scores(),
                    Collections.unmodifiableList(new ArrayList<>(values)));
        }
    };

    public static final RowMapper<TaggedCard> MAPPER = QTaggedCard::map;

    private QTaggedCard() {
    }

    public static ModelQuery.Builder<TaggedEntity, Long, TaggedCard> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static TaggedCard map(Row row) {
        return new TaggedCard(row.get(ID), List.of(), Set.of(), List.of());
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<TaggedCard> fields() {
        return Index.INSTANCE;
    }

    @Incubating
    public static ModelDelete.Builder<TaggedEntity, Long, TaggedCard> delete() {
        return ModelDelete.builder(ROOT).primaryKey(KEY);
    }

    private static final class Index {
        static final FieldIndex<TaggedCard> INSTANCE = FieldIndex.builder(TaggedCard.class)
                .select(ID)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .valueChild(TAGS, SCORES, PARENT_NOTES)
                .build();
    }
}
