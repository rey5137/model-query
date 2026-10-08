package models;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChildField;
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
import com.rey.modelquery.processor.fixture.CustomerEntity;
import com.rey.modelquery.processor.fixture.ItemEntity;
import com.rey.modelquery.processor.fixture.OrderEntity;
import jakarta.persistence.criteria.JoinType;
import java.util.List;
import java.util.Optional;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QShelfCard {
    public static final TableField<ShelfEntity, ShelfEntity> ROOT = TableField.root(ShelfEntity.class);

    public static final OrderedColumnField<ShelfCard, ShelfEntity, Long> ID = ColumnField.of(ShelfCard.class,
            ROOT, "id", Long.class).named("id");

    public static final SelectSet<ShelfCard> ALL = SelectSet.of(ID);

    public static final SelectSet<ShelfCard> DEFAULT = ALL;

    public static final PrimaryKey<ShelfCard, Long> KEY = PrimaryKey.of(ID);

    @Incubating
    public static final ChildField<ShelfCard, ItemRef> ITEMS = new ChildField<ShelfCard, ItemRef>() {
        private final ColumnField<ShelfCard, ?, ?> key = ColumnField.of(ShelfCard.class, ROOT, "id",
                Long.class);

        private final TableField<?, ?> through = TableField.<OrderEntity, ItemEntity>join(
                TableField.<ShelfEntity, OrderEntity>join(ROOT, "order", JoinType.INNER), "related",
                JoinType.INNER);

        @Override
        public String name() {
            return "items";
        }

        @Override
        public ColumnField<ShelfCard, ?, ?> key() {
            return key;
        }

        @Override
        public Optional<ColumnField<ItemRef, ?, ?>> foreignKey() {
            return Optional.empty();
        }

        @Override
        public Optional<TableField<?, ?>> through() {
            return Optional.of(through);
        }

        @Override
        public boolean isToMany() {
            return true;
        }

        @Override
        public ModelQuery.Builder<?, ?, ItemRef> query() {
            return QItemRef.query();
        }

        @Override
        public ShelfCard with(ShelfCard parent, List<ItemRef> children) {
            return new ShelfCard(parent.id(), List.copyOf(children), parent.payer());
        }
    };

    @Incubating
    public static final ChildField<ShelfCard, CustomerName> PAYER = new ChildField<ShelfCard, CustomerName>() {
        private final ColumnField<ShelfCard, ?, ?> key = ColumnField.of(ShelfCard.class, ROOT, "id",
                Long.class);

        private final TableField<?, ?> through = TableField.<OrderEntity, CustomerEntity>join(
                TableField.<ShelfEntity, OrderEntity>join(ROOT, "order", JoinType.INNER), "payer",
                JoinType.INNER);

        @Override
        public String name() {
            return "payer";
        }

        @Override
        public ColumnField<ShelfCard, ?, ?> key() {
            return key;
        }

        @Override
        public Optional<ColumnField<CustomerName, ?, ?>> foreignKey() {
            return Optional.empty();
        }

        @Override
        public Optional<TableField<?, ?>> through() {
            return Optional.of(through);
        }

        @Override
        public boolean isToMany() {
            return false;
        }

        @Override
        public ModelQuery.Builder<?, ?, CustomerName> query() {
            return QCustomerName.query();
        }

        @Override
        public ShelfCard with(ShelfCard parent, List<CustomerName> children) {
            return new ShelfCard(parent.id(), parent.items(),
                    children.isEmpty() ? Optional.empty() : Optional.of(children.get(0)));
        }
    };

    public static final RowMapper<ShelfCard> MAPPER = QShelfCard::map;

    private QShelfCard() {
    }

    public static ModelQuery.Builder<ShelfEntity, Long, ShelfCard> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static ShelfCard map(Row row) {
        return new ShelfCard(row.get(ID), List.of(), Optional.empty());
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<ShelfCard> fields() {
        return Index.INSTANCE;
    }

    @Incubating
    public static ModelDelete.Builder<ShelfEntity, Long, ShelfCard> delete() {
        return ModelDelete.builder(ROOT).primaryKey(KEY);
    }

    private static final class Index {
        static final FieldIndex<ShelfCard> INSTANCE = FieldIndex.builder(ShelfCard.class)
                .select(ID)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .child(ITEMS, PAYER)
                .build();
    }
}
