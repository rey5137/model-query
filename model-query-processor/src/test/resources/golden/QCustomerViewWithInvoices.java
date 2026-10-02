package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinField;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.criteria.JoinType;
import java.util.List;
import java.util.Optional;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QCustomerView {
    public static final TableField<CustomerEntity, CustomerEntity> ROOT = TableField.root(CustomerEntity.class);

    public static final TableField<CustomerEntity, CountryEntity> COUNTRY_TABLE = TableField.<CustomerEntity, CountryEntity>join(ROOT,
            "country", JoinType.LEFT).presentBy(QCountryView.KEY).named("country");

    public static final OrderedColumnField<CustomerView, CustomerEntity, Long> ID = ColumnField.of(CustomerView.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<CustomerView, CustomerEntity, String> NAME = ColumnField.of(CustomerView.class,
            ROOT, "name", String.class).named("name");

    public static final OrderedColumnField<CustomerView, CountryEntity, String> COUNTRY_CODE = QCountryView.CODE.withTable(CustomerView.class,
            COUNTRY_TABLE);

    public static final OrderedColumnField<CustomerView, CountryEntity, String> COUNTRY_NAME = QCountryView.NAME.withTable(CustomerView.class,
            COUNTRY_TABLE);

    public static final SelectSet<CustomerView> ALL = SelectSet.of(ID, NAME);

    public static final SelectSet<CustomerView> DEFAULT = ALL;

    public static final SelectSet<CustomerView> COUNTRY = SelectSet.of(COUNTRY_CODE, COUNTRY_NAME);

    public static final PrimaryKey<CustomerView, Long> KEY = PrimaryKey.of(ID);

    @Incubating
    public static final JoinField<CustomerView, CountryView> COUNTRY_JOIN = new JoinField<CustomerView, CountryView>() {
        @Override
        public String name() {
            return "country";
        }

        @Override
        public Class<CustomerView> model() {
            return CustomerView.class;
        }

        @Override
        public TableField<?, ?> table() {
            return COUNTRY_TABLE;
        }

        @Override
        public Optional<CountryView> get(CustomerView parent) {
            return parent.getCountry();
        }

        @Override
        public CustomerView with(CustomerView parent, CountryView nested) {
            parent.setCountry(Optional.of(nested));
            return parent;
        }
    };

    @Incubating
    public static final ChildField<CustomerView, InvoiceView> INVOICES = new ChildField<CustomerView, InvoiceView>() {
        private final ColumnField<CustomerView, ?, ?> key = ColumnField.of(CustomerView.class, ROOT,
                "id", Long.class);

        private final ColumnField<InvoiceView, ?, ?> foreignKey = ColumnField.of(InvoiceView.class,
                TableField.<InvoiceEntity, CustomerEntity>join(TableField.root(InvoiceEntity.class),
                "customer", JoinType.LEFT), "id", Long.class);

        @Override
        public String name() {
            return "invoices";
        }

        @Override
        public ColumnField<CustomerView, ?, ?> key() {
            return key;
        }

        @Override
        public Optional<ColumnField<InvoiceView, ?, ?>> foreignKey() {
            return Optional.of(foreignKey);
        }

        @Override
        public Optional<TableField<?, ?>> through() {
            return Optional.empty();
        }

        @Override
        public boolean isToMany() {
            return true;
        }

        @Override
        public ModelQuery.Builder<?, ?, InvoiceView> query() {
            return QInvoiceView.query();
        }

        @Override
        public CustomerView with(CustomerView parent, List<InvoiceView> children) {
            parent.setInvoices(List.copyOf(children));
            return parent;
        }
    };

    public static final RowMapper<CustomerView> MAPPER = QCustomerView::map;

    private QCustomerView() {
    }

    public static ModelQuery.Builder<CustomerEntity, Long, CustomerView> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static CustomerView map(Row row) {
        CustomerView m = new CustomerView();
        if (row.isSelected(ID)) {
            m.setId(row.get(ID));
        }
        if (row.isSelected(NAME)) {
            m.setName(row.get(NAME));
        }
        Row country = row.scoped(COUNTRY_TABLE);
        m.setCountry(country.get(QCountryView.CODE) == null ? Optional.empty()
                : Optional.of(QCountryView.MAPPER.map(country)));
        m.setInvoices(List.of());
        return m;
    }

    @Incubating
    public static ModelDelete.Builder<CustomerEntity, Long, CustomerView> delete() {
        return ModelDelete.builder(ROOT).primaryKey(KEY);
    }
}
