package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinField;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.criteria.JoinType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** The {@code ChildField} and {@code JoinField} constants a fetch plan names, and the {@code @Child} checks. */
class FetchFieldTest {

    private static final String SHOP_IMPORTS = """
            package shop;

            import com.rey.modelquery.annotations.Child;
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.annotations.UpdateModel;
            import java.util.List;
            import java.util.Optional;

            """;

    /** The nested pair, with {@code customerView} as its nested model and {@code others} beside it. */
    private static JavaFileObject[] invoiceSources(JavaFileObject customerView, JavaFileObject... others) {
        return Stream.of(Stream.of(ShopSources.INVOICE_SOURCES), Stream.of(customerView, ShopSources.INVOICE_VIEW),
                        Stream.of(others))
                .flatMap(sources -> sources)
                .toArray(JavaFileObject[]::new);
    }

    /** The errors of {@code shop.<name>} declared as {@code declaration}, beside the nested pair. */
    private static List<String> errorsOf(String name, String declaration, JavaFileObject... others) {
        JavaFileObject model = source("shop." + name, SHOP_IMPORTS + declaration);
        Compilation compilation = compile(invoiceSources(ShopSources.CUSTOMER_VIEW,
                Stream.concat(Stream.of(model), Stream.of(others)).toArray(JavaFileObject[]::new)));
        assertThat(compilation).failed();
        return errors(compilation);
    }

    @Test
    void ac_fch_10_a_record_and_a_class_with_children_and_joins_match_their_golden_files() {
        Compilation compilation = compile(invoiceSources(ShopSources.CUSTOMER_WITH_INVOICES));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QInvoiceView")).isEqualTo(resource("golden/QInvoiceView.java"));
        assertThat(generated(compilation, "shop.QCustomerView"))
                .isEqualTo(resource("golden/QCustomerViewWithInvoices.java"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac_fch_10_a_child_and_its_back_join_initialise_in_either_order() throws Exception {
        Compilation compilation = compile(invoiceSources(ShopSources.CUSTOMER_WITH_INVOICES));
        assertThat(compilation).succeededWithoutWarnings();

        for (List<String> order : List.of(List.of("shop.QCustomerView", "shop.QInvoiceView"),
                List.of("shop.QInvoiceView", "shop.QCustomerView"))) {
            ClassLoader loader = classes(compilation);
            for (String qModel : order) {
                Class.forName(qModel, true, loader);
            }
            Class<?> qCustomer = loader.loadClass("shop.QCustomerView");
            Class<?> qInvoice = loader.loadClass("shop.QInvoiceView");
            var invoices = (ChildField<Object, Object>) constant(qCustomer, "INVOICES");
            var customer = (JoinField<Object, Object>) constant(qInvoice, "CUSTOMER_JOIN");

            assertThat(invoices.name()).as(order.get(0)).isEqualTo("invoices");
            assertThat(invoices.isToMany()).isTrue();
            // The key reads the attribute as the generated column does, and the foreign key goes through the
            // association as a @Join of the child would (R-FCH-03, R-FCH-05).
            assertThat(invoices.key()).isEqualTo(constant(qCustomer, "ID"));
            assertThat(invoices.foreignKey()).isEqualTo(ColumnField.of(
                    (Class<Object>) loader.loadClass("shop.InvoiceView"),
                    TableField.join(TableField.root(loader.loadClass("shop.InvoiceEntity")), "customer",
                            JoinType.LEFT),
                    "id", Long.class));
            assertThat(invoices.query()).isNotNull();
            assertThat(customer.table()).isNotNull().isSameAs(constant(qInvoice, "CUSTOMER_TABLE"));
            assertThat(customer.model()).isSameAs(loader.loadClass("shop.InvoiceView"));
            assertThat(constant(qInvoice, "KEY")).isNotNull();
            assertThat(constant(qCustomer, "KEY")).isNotNull();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac_fch_10_generated_fields_set_a_record_through_its_constructor_and_a_class_through_its_setter()
            throws Exception {
        ClassLoader loader = classes(compile(invoiceSources(ShopSources.CUSTOMER_WITH_INVOICES)));
        Class<?> qCustomer = loader.loadClass("shop.QCustomerView");
        Class<?> qInvoice = loader.loadClass("shop.QInvoiceView");
        Class<?> qCountry = loader.loadClass("shop.QCountryView");
        var customerId = (ColumnField<Object, ?, ?>) constant(qCustomer, "ID");
        var invoiceId = (ColumnField<Object, ?, ?>) constant(qInvoice, "ID");
        var countryCode = (ColumnField<Object, ?, ?>) constant(qCountry, "CODE");

        // A mapped row holds an empty child until a plan loads it (R-FCH-03).
        Object customer = ((RowMapper<?>) constant(qCustomer, "MAPPER")).map(row(customerId, 7L));
        Object invoice = ((RowMapper<?>) constant(qInvoice, "MAPPER")).map(row(invoiceId, 3L));
        Object country = ((RowMapper<?>) constant(qCountry, "MAPPER")).map(row(countryCode, "SE"));
        assertThat(field(customer, "invoices")).isEqualTo(List.of());
        assertThat(field(invoice, "billingCountry")).isEqualTo(Optional.empty());

        var invoices = (ChildField<Object, Object>) constant(qCustomer, "INVOICES");
        assertThat(invoices.with(customer, List.of(invoice))).isSameAs(customer);
        assertThat(field(customer, "invoices")).isEqualTo(List.of(invoice));

        var billingCountry = (ChildField<Object, Object>) constant(qInvoice, "BILLING_COUNTRY");
        Object billed = billingCountry.with(invoice, List.of(country));
        assertThat(billed).isNotSameAs(invoice);
        assertThat(field(billed, "billingCountry")).isEqualTo(Optional.of(country));
        assertThat(field(billed, "id")).isEqualTo(3L);
        assertThat(field(billingCountry.with(billed, List.of()), "billingCountry")).isEqualTo(Optional.empty());

        var customerJoin = (JoinField<Object, Object>) constant(qInvoice, "CUSTOMER_JOIN");
        Object joined = customerJoin.with(billed, customer);
        assertThat(customerJoin.get(joined)).contains(customer);
        assertThat(field(joined, "billingCountry")).isEqualTo(Optional.of(country));
        var countryJoin = (JoinField<Object, Object>) constant(qCustomer, "COUNTRY_JOIN");
        assertThat(countryJoin.get(countryJoin.with(customer, country))).contains(country);
    }

    @Test
    void ac_fch_10_a_child_model_from_another_module_matches_its_golden_file() {
        // As for a nested model (D-45): the fixture's QModel is generated here from a copy of its source, then
        // compiled beside the parent while OrderRef itself comes from the classpath.
        String fixture = "com.rey.modelquery.processor.fixture.";
        String childQModel = generated(compile(source(fixture + "OrderRef", """
                package com.rey.modelquery.processor.fixture;

                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderEntity.class)
                public record OrderRef(@PrimaryKey Long id, String status) {}
                """)), fixture + "QOrderRef");

        Compilation compilation = compile(source(fixture + "QOrderRef", childQModel), source("models.CustomerCard", """
                package models;

                import com.rey.modelquery.annotations.Child;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import com.rey.modelquery.processor.fixture.CustomerEntity;
                import com.rey.modelquery.processor.fixture.OrderRef;
                import java.util.List;

                @QueryModel(root = CustomerEntity.class)
                public record CustomerCard(
                        @PrimaryKey Long id,
                        String name,
                        @Child(foreignKey = "customer.id") List<OrderRef> orders) {}
                """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "models.QCustomerCard")).isEqualTo(resource("golden/QCustomerCard.java"));
    }

    @Test
    void ac_fch_10_mq3401_refuses_another_annotation_beside_child_and_an_update_model() {
        assertThat(errorsOf("RefundView", """
                @QueryModel(root = InvoiceEntity.class)
                public record RefundView(
                        @PrimaryKey Long id,
                        @Child(key = "customer.id") @Join Optional<CustomerView> customer,
                        @Child Optional<String> note) {}
                """)).containsExactly(
                "MQ3401: RefundView.customer: @Child can't be combined with @Join",
                "MQ3401: RefundView.note: @Child needs a List or Optional of a @QueryModel, found Optional<String>");
        assertThat(errorsOf("InvoicePatch", """
                @UpdateModel(root = InvoiceEntity.class)
                public record InvoicePatch(@PrimaryKey Long id, @Child Optional<CountryView> country) {}
                """)).containsExactly("MQ3401: InvoicePatch.country: @Child isn't allowed on @UpdateModel; an "
                + "update writes columns and loads no children");
    }

    @Test
    void ac_fch_10_mq3402_and_mq3404_refuse_a_key_that_is_not_one_attribute() {
        assertThat(errorsOf("RefundView", """
                @QueryModel(root = InvoiceEntity.class)
                public record RefundView(
                        @PrimaryKey Long id,
                        @Child(key = "customer", foreignKey = "id") Optional<CustomerView> owner,
                        @Child(key = {"id", "status"}) Optional<CountryView> country) {}
                """)).containsExactly(
                "MQ3402: RefundView.owner: 'customer' on InvoiceEntity is an association, not a key attribute; name "
                        + "an attribute of it, such as 'customer.id'",
                "MQ3404: RefundView.country: @Child takes one key attribute each side; key names 2");
        assertThat(errorsOf("ShipmentView", """
                @QueryModel(root = OrderEntity.class)
                public record ShipmentView(
                        @PrimaryKey Long id,
                        @Child(key = "address", foreignKey = "code") Optional<CountryView> country) {}
                """, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY)).containsExactly(
                "MQ3404: ShipmentView.country: @Child takes one key attribute each side; key 'address' is the "
                        + "embedded value Address");
        assertThat(errorsOf("DocumentView", """
                @QueryModel(root = DocumentEntity.class)
                public record DocumentView(
                        @PrimaryKey Long id,
                        @Child(key = "digest", foreignKey = "id") Optional<CustomerView> signer) {}
                """, source("shop.DocumentEntity", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                public class DocumentEntity {
                    @Id
                    Long id;
                    byte[] digest;
                }
                """))).containsExactly(
                "MQ3404: DocumentView.signer: @Child takes one key attribute each side; key 'digest' is an array, "
                        + "byte[]");
        assertThat(errorsOf("BasketView", """
                @QueryModel(root = com.rey.modelquery.processor.fixture.OrderEntity.class)
                public record BasketView(
                        @PrimaryKey Long id,
                        @Child(key = "items.id", foreignKey = "id") Optional<CustomerView> buyer,
                        @Child(key = "customer.id", foreignKey = "items.id") List<BasketView> sharing) {}
                """)).containsExactly(
                "MQ3402: BasketView.buyer: key 'items.id' crosses a collection, which gives the parent a row per "
                        + "element; name a single-valued attribute, or put the collection in the child's foreignKey");
    }

    @Test
    void ac_fch_10_the_generated_constants_of_children_and_joins_are_claimed() {
        assertThat(errorsOf("RefundView", """
                @QueryModel(root = InvoiceEntity.class)
                public record RefundView(
                        @PrimaryKey Long id,
                        @Column(attribute = "status") String customerJoin,
                        @Join Optional<CustomerView> customer,
                        @Child(key = "customer.country.code") Optional<CountryView> customerName) {}
                """)).containsExactly(
                "MQ3015: RefundView.customerJoin: constant CUSTOMER_JOIN is also generated for the join field of "
                        + "customer; rename the field or set @Join(prefix)",
                "MQ3015: RefundView.customerName: constant CUSTOMER_NAME is also generated for customer.name; rename "
                        + "the field or set @Join(prefix)");
    }

    /** A row that selects {@code column} alone, through which every join reads as a miss. */
    private static Row row(SelectField<?, ?> column, Object value) {
        Map<SelectField<?, ?>, Object> values = Map.of(column, value);
        return new Row() {
            @Override
            @SuppressWarnings("unchecked")
            public <C> C get(SelectField<?, C> selected) {
                return (C) values.get(selected);
            }

            @Override
            public Object raw(SelectField<?, ?> selected) {
                return values.get(selected);
            }

            @Override
            public boolean isSelected(SelectField<?, ?> selected) {
                return values.containsKey(selected);
            }

            @Override
            public Row scoped(TableField<?, ?> join) {
                return ProcessorHarness.row(Map.of());
            }
        };
    }

    private static Object constant(Class<?> qModel, String name) throws ReflectiveOperationException {
        return qModel.getField(name).get(null);
    }

    private static Object field(Object model, String name) throws ReflectiveOperationException {
        Field field = model.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(model);
    }
}
