package models;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.processor.fixture.CustomerEntity;
import com.rey.modelquery.processor.fixture.OrderEntity;
import com.rey.modelquery.processor.fixture.OrderRef;
import com.rey.modelquery.processor.fixture.QOrderRef;
import jakarta.persistence.criteria.JoinType;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QCustomerCard {
    public static final TableField<CustomerEntity, CustomerEntity> ROOT = TableField.root(CustomerEntity.class);

    public static final OrderedColumnField<CustomerCard, CustomerEntity, Long> ID = ColumnField.of(CustomerCard.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<CustomerCard, CustomerEntity, String> NAME = ColumnField.of(CustomerCard.class,
            ROOT, "name", String.class).named("name");

    public static final SelectSet<CustomerCard> ALL = SelectSet.of(ID, NAME);

    public static final SelectSet<CustomerCard> DEFAULT = ALL;

    public static final PrimaryKey<CustomerCard, Long> KEY = PrimaryKey.of(ID);

    @Incubating
    public static final ChildField<CustomerCard, OrderRef> ORDERS = new ChildField<CustomerCard, OrderRef>() {
        private final ColumnField<CustomerCard, ?, ?> key = ColumnField.of(CustomerCard.class, ROOT,
                "id", Long.class);

        private final ColumnField<OrderRef, ?, ?> foreignKey = ColumnField.of(OrderRef.class,
                TableField.<OrderEntity, CustomerEntity>join(TableField.root(OrderEntity.class),
                "customer", JoinType.LEFT), "id", Long.class);

        @Override
        public String name() {
            return "orders";
        }

        @Override
        public ColumnField<CustomerCard, ?, ?> key() {
            return key;
        }

        @Override
        public ColumnField<OrderRef, ?, ?> foreignKey() {
            return foreignKey;
        }

        @Override
        public boolean isToMany() {
            return true;
        }

        @Override
        public ModelQuery.Builder<?, ?, OrderRef> query() {
            return QOrderRef.query();
        }

        @Override
        public CustomerCard with(CustomerCard parent, List<OrderRef> children) {
            return new CustomerCard(parent.id(), parent.name(), List.copyOf(children));
        }
    };

    public static final RowMapper<CustomerCard> MAPPER = QCustomerCard::map;

    private QCustomerCard() {
    }

    public static ModelQuery.Builder<CustomerEntity, Long, CustomerCard> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static CustomerCard map(Row row) {
        return new CustomerCard(row.get(ID), row.get(NAME), List.of());
    }

    @Incubating
    public static ModelDelete.Builder<CustomerEntity, Long, CustomerCard> delete() {
        return ModelDelete.builder(ROOT).primaryKey(KEY);
    }
}
