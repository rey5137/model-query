package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.criteria.JoinType;
import java.util.Optional;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QInvoiceView {
    public static final TableField<InvoiceEntity, InvoiceEntity> ROOT = TableField.root(InvoiceEntity.class);

    public static final TableField<InvoiceEntity, CustomerEntity> CUSTOMER_TABLE = TableField.<InvoiceEntity, CustomerEntity>join(ROOT,
            "customer", JoinType.LEFT).as("customer").presentBy(QCustomerView.KEY)
            .named("customer");

    public static final TableField<CustomerEntity, CountryEntity> CUSTOMER_COUNTRY_TABLE = QCustomerView.COUNTRY_TABLE.withParent(CUSTOMER_TABLE);

    public static final TableField<InvoiceEntity, CustomerEntity> BUYER_TABLE = TableField.<InvoiceEntity, CustomerEntity>join(ROOT,
            "customer", JoinType.INNER).as("payer").presentBy(QCustomerView.KEY).named("payer");

    public static final TableField<CustomerEntity, CountryEntity> BUYER_COUNTRY_TABLE = QCustomerView.COUNTRY_TABLE.withParent(BUYER_TABLE);

    public static final OrderedColumnField<InvoiceView, InvoiceEntity, Long> ID = ColumnField.of(InvoiceView.class,
            ROOT, "id", Long.class).named("id");

    public static final ColumnField<InvoiceView, InvoiceEntity, InvoiceStatus> STATUS = ColumnField.of(InvoiceView.class,
            ROOT, "status", InvoiceStatus.class, String.class, InvoiceStatus.Converter.INSTANCE)
            .named("status");

    public static final OrderedColumnField<InvoiceView, CustomerEntity, Long> CUSTOMER_ID = QCustomerView.ID.withTable(InvoiceView.class,
            CUSTOMER_TABLE);

    public static final OrderedColumnField<InvoiceView, CustomerEntity, String> CUSTOMER_NAME = QCustomerView.NAME.withTable(InvoiceView.class,
            CUSTOMER_TABLE);

    public static final OrderedColumnField<InvoiceView, CountryEntity, String> CUSTOMER_COUNTRY_CODE = QCustomerView.COUNTRY_CODE.withTable(InvoiceView.class,
            CUSTOMER_COUNTRY_TABLE);

    public static final OrderedColumnField<InvoiceView, CountryEntity, String> CUSTOMER_COUNTRY_NAME = QCustomerView.COUNTRY_NAME.withTable(InvoiceView.class,
            CUSTOMER_COUNTRY_TABLE);

    public static final OrderedColumnField<InvoiceView, CustomerEntity, Long> BUYER_ID = QCustomerView.ID.withTable(InvoiceView.class,
            BUYER_TABLE);

    public static final OrderedColumnField<InvoiceView, CustomerEntity, String> BUYER_NAME = QCustomerView.NAME.withTable(InvoiceView.class,
            BUYER_TABLE);

    public static final OrderedColumnField<InvoiceView, CountryEntity, String> BUYER_COUNTRY_CODE = QCustomerView.COUNTRY_CODE.withTable(InvoiceView.class,
            BUYER_COUNTRY_TABLE);

    public static final OrderedColumnField<InvoiceView, CountryEntity, String> BUYER_COUNTRY_NAME = QCustomerView.COUNTRY_NAME.withTable(InvoiceView.class,
            BUYER_COUNTRY_TABLE);

    public static final ColumnSet<InvoiceView> ALL = ColumnSet.of(ID, STATUS);

    public static final ColumnSet<InvoiceView> DEFAULT = ALL;

    public static final ColumnSet<InvoiceView> CUSTOMER = ColumnSet.of(CUSTOMER_ID, CUSTOMER_NAME);

    public static final ColumnSet<InvoiceView> CUSTOMER_COUNTRY = ColumnSet.of(CUSTOMER_COUNTRY_CODE,
            CUSTOMER_COUNTRY_NAME);

    public static final ColumnSet<InvoiceView> BUYER = ColumnSet.of(BUYER_ID, BUYER_NAME);

    public static final ColumnSet<InvoiceView> BUYER_COUNTRY = ColumnSet.of(BUYER_COUNTRY_CODE,
            BUYER_COUNTRY_NAME);

    public static final PrimaryKey<InvoiceView, Long> KEY = PrimaryKey.of(ID);

    public static final RowMapper<InvoiceView> MAPPER = QInvoiceView::map;

    private QInvoiceView() {
    }

    public static ModelQuery.Builder<InvoiceEntity, Long, InvoiceView> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static InvoiceView map(Row row) {
        Row customer = row.scoped(CUSTOMER_TABLE);
        Row payer = row.scoped(BUYER_TABLE);
        return new InvoiceView(row.get(ID), row.get(STATUS), customer.get(QCustomerView.ID) == null
                ? Optional.empty() : Optional.of(QCustomerView.MAPPER.map(customer)),
                payer.get(QCustomerView.ID) == null ? Optional.empty()
                : Optional.of(QCustomerView.MAPPER.map(payer)));
    }

    @Incubating
    public static ModelDelete.Builder<InvoiceEntity, Long, InvoiceView> delete() {
        return ModelDelete.builder(ROOT).primaryKey(KEY);
    }
}
