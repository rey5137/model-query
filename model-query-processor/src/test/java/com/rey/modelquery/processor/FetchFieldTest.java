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
import com.rey.modelquery.core.JoinScope;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.criteria.JoinType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            assertThat(invoices.foreignKey()).contains(ColumnField.of(
                    (Class<Object>) loader.loadClass("shop.InvoiceView"),
                    TableField.join(TableField.root(loader.loadClass("shop.InvoiceEntity")), "customer",
                            JoinType.LEFT),
                    "id", Long.class));
            assertThat(invoices.through()).isEmpty();
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
                "MQ3401: RefundView.note: @Child needs a List or Optional of a model annotated @QueryModel "
                + "or bound by @BindQueryModel in this compilation, found Optional<String>");
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

    private static final String THROUGH_IMPORTS = """
            package models;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.Child;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.processor.fixture.CustomerEntity;
            import com.rey.modelquery.processor.fixture.ItemEntity;
            import com.rey.modelquery.processor.fixture.OrderEntity;
            import java.util.List;
            import java.util.Optional;

            """;

    /** An entity whose order, a to-one, reaches the fixture's items, a many-to-many, and its payer, a to-one. */
    private static final JavaFileObject SHELF_ENTITY = source("models.ShelfEntity", """
            package models;

            import com.rey.modelquery.processor.fixture.OrderEntity;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class ShelfEntity {
                @Id
                Long id;
                @ManyToOne
                OrderEntity order;
            }
            """);

    /** {@code ItemRef} over the fixture's items, then each type of {@code models} in package {@code models}. */
    private static Compilation compileThrough(String... models) {
        var sources = new ArrayList<JavaFileObject>(List.of(SHELF_ENTITY, source("models.ItemRef", THROUGH_IMPORTS
                + "@QueryModel(root = ItemEntity.class)\npublic record ItemRef(@PrimaryKey Long id) {}\n")));
        for (String model : models) {
            Matcher name = Pattern.compile("(?:record|class) (\\w+)").matcher(model);
            assertThat(name.find()).isTrue();
            sources.add(source("models." + name.group(1), THROUGH_IMPORTS + model));
        }
        return compile(sources.toArray(JavaFileObject[]::new));
    }

    @Test
    void ac_fch_11_a_through_child_matches_its_golden_file() {
        Compilation compilation = compileThrough("""
                @QueryModel(root = CustomerEntity.class)
                public record CustomerName(@PrimaryKey Long id, String name) {}
                """, """
                @QueryModel(root = ShelfEntity.class)
                public record ShelfCard(
                        @PrimaryKey Long id,
                        @Child(through = "order.related") List<ItemRef> items,
                        @Child(through = "order.payer") Optional<CustomerName> payer) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "models.QShelfCard")).isEqualTo(resource("golden/QShelfCard.java"));
    }

    @Test
    void ac_fch_11_mq3406_refuses_a_through_path_that_does_not_reach_the_child_root() {
        Compilation compilation = compileThrough("""
                @QueryModel(root = OrderEntity.class)
                public record OrderCard(
                        @PrimaryKey Long id,
                        @Child(through = " ") List<ItemRef> blank,
                        @Child(through = "relatedx") List<ItemRef> unknown,
                        @Child(through = "status") List<ItemRef> basic,
                        @Child(through = "address.city") List<ItemRef> embedded,
                        @Child(through = "customer") List<ItemRef> elsewhere,
                        @Child(through = "related") List<SpecialItemRef> special) {}
                """, """
                @QueryModel(root = SpecialItemEntity.class)
                public record SpecialItemRef(@PrimaryKey Long id) {}
                """, """
                @jakarta.persistence.Entity
                public class SpecialItemEntity extends ItemEntity {}
                """);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(
                "MQ3406: OrderCard.blank: through ' ' is blank",
                "MQ3406: OrderCard.unknown: through 'relatedx' can't be followed: no attribute 'relatedx' on "
                        + "OrderEntity",
                "MQ3406: OrderCard.basic: through 'status' crosses 'status' on OrderEntity, which is not an "
                        + "association to an entity",
                "MQ3406: OrderCard.embedded: through 'address.city' crosses the embedded value 'address' of "
                        + "OrderEntity, which a join can't reach into",
                "MQ3406: OrderCard.elsewhere: through 'customer' ends at CustomerEntity, not at ItemEntity, the root "
                        + "of ItemRef",
                "MQ3406: OrderCard.special: through 'related' ends at ItemEntity, not at SpecialItemEntity, the root "
                        + "of SpecialItemRef");
    }

    @Test
    void ac_fch_11_mq3406_mq3401_and_mq3405_refuse_a_through_key_child_or_foreign_key_it_cannot_load() {
        Compilation compilation = compileThrough("""
                @QueryModel(root = CustomerEntity.class)
                public record CustomerCount(@GroupBy String name, @Aggregate(fn = AggregateFunction.COUNT) Long rows) {}
                """, """
                @QueryModel(root = ItemEntity.class, singleGroup = true)
                public record ItemCount(@Aggregate(fn = AggregateFunction.COUNT) Long rows) {}
                """, """
                @QueryModel(root = OrderEntity.class)
                public record OrderCard(
                        @PrimaryKey Long id,
                        @Child(foreignKey = "id", through = "related") List<ItemRef> both,
                        @Child(key = "quantity", through = "related") List<ItemRef> notId,
                        @Child(key = "customer.id", through = "related") List<ItemRef> joined,
                        @Child(through = "payer") Optional<CustomerCount> grouped,
                        @Child(through = "related") Optional<ItemCount> unkeyed) {}
                """);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(
                "MQ3401: OrderCard.both: @Child takes foreignKey or through, not both; through matches the child on "
                        + "the parent's @Id read along the path",
                "MQ3406: OrderCard.notId: key 'quantity' is not the single @Id of OrderEntity, which a through child "
                        + "is matched on",
                "MQ3406: OrderCard.joined: key 'customer.id' is not the single @Id of OrderEntity, which a through "
                        + "child is matched on",
                "MQ3406: OrderCard.grouped: CustomerCount is grouped, and a through child can't be: its rows would "
                        + "have to be grouped by the parent's key too",
                "MQ3405: OrderCard.unkeyed: an Optional @Child through a collection needs a @PrimaryKey on "
                        + "ItemCount, which deduplicates its rows",
                "MQ3406: OrderCard.unkeyed: ItemCount is grouped, and a through child can't be: its rows would have "
                        + "to be grouped by the parent's key too");
    }

    /**
     * An entity with element collections of each kind a value child meets, a to-one and a to-many back to itself and
     * an embedded value holding one; an embeddable element; and an entity with a composite id (R-FCH-19).
     */
    private static final JavaFileObject[] TAGGED_SOURCES = {
        source("models.TaggedEntity", """
                package models;

                import jakarta.persistence.ElementCollection;
                import jakarta.persistence.Embedded;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Lob;
                import jakarta.persistence.ManyToMany;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.OrderColumn;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;

                @Entity
                public class TaggedEntity {
                    @Id
                    Long id;
                    String name;
                    @ElementCollection
                    List<String> tags;
                    @ElementCollection
                    @OrderColumn
                    List<Integer> scores;
                    @ElementCollection
                    @Lob
                    Set<String> notes;
                    @ElementCollection
                    Map<String, String> labels;
                    @ElementCollection
                    List<Spot> spots;
                    @Embedded
                    TagBox box;
                    @ManyToOne
                    TaggedEntity parent;
                    @ManyToMany
                    List<TaggedEntity> siblings;
                }
                """),
        source("models.Spot", """
                package models;

                @jakarta.persistence.Embeddable
                public class Spot {
                    String city;
                    @jakarta.persistence.Embedded Geo geo;
                    Long code;
                }
                """),
        source("models.Geo", """
                package models;
                @jakarta.persistence.Embeddable
                public class Geo { java.math.BigDecimal lat; }
                """),
        source("models.CodeConverter", """
                package models;
                public class CodeConverter implements com.rey.modelquery.core.ColumnConverter<String, Long> {
                    public String toModel(Long value) { return value == null ? null : value.toString(); }
                    public Long toAttribute(String value) { return value == null ? null : Long.valueOf(value); }
                }
                """),
        source("models.TagBox", """
                package models;

                @jakarta.persistence.Embeddable
                public class TagBox {
                    @jakarta.persistence.ElementCollection
                    java.util.List<String> inner;
                }
                """),
        source("models.PairEntity", """
                package models;

                @jakarta.persistence.Entity
                @jakarta.persistence.IdClass(PairId.class)
                public class PairEntity {
                    @jakarta.persistence.Id
                    Long a;
                    @jakarta.persistence.Id
                    Long b;
                    @jakarta.persistence.ElementCollection
                    java.util.List<String> tags;
                }
                """),
        source("models.PairId", """
                package models;

                public record PairId(Long a, Long b) implements java.io.Serializable {}
                """)};

    /** Each type of {@code models} in package {@code models}, beside {@link #TAGGED_SOURCES}. */
    private static Compilation compileTagged(String... models) {
        var sources = new ArrayList<JavaFileObject>(List.of(TAGGED_SOURCES));
        for (String model : models) {
            Matcher name = Pattern.compile("(?:record|class) (\\w+)").matcher(model);
            assertThat(name.find()).isTrue();
            sources.add(source("models." + name.group(1), THROUGH_IMPORTS + "import java.util.Set;\n\n" + model));
        }
        return compile(sources.toArray(JavaFileObject[]::new));
    }

    @Test
    void ac_proc_62_element_model_generates_columns_and_a_child_field() throws Exception {
        Compilation compilation = compileTagged("""
                @com.rey.modelquery.annotations.ElementModel(Spot.class)
                public record SpotRow(String city,
                        @com.rey.modelquery.annotations.Column(attribute = "geo.lat") java.math.BigDecimal lat,
                        @com.rey.modelquery.annotations.Column(converter = CodeConverter.class) String code) {}
                """, """
                @QueryModel(root = TaggedEntity.class)
                public record TaggedCard(@PrimaryKey Long id, @Child(through = "spots") List<SpotRow> spots) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        String element = generated(compilation, "models.QSpotRow");
        assertThat(element).contains("private static final TableField<Spot, Spot> ROOT",
                "ColumnField<SpotRow, Spot, String> CITY", "SelectSet<SpotRow> ALL", "RowMapper<SpotRow> MAPPER");
        assertThat(element).contains("geo.lat", "CodeConverter");
        assertThat(element).doesNotContain(" query(");
        assertThat(element).isEqualTo(resource("golden/QSpotRow.java"));
        assertThat(generated(compilation, "models.QTaggedCard")).contains(
                "ChildField<TaggedCard, SpotRow> SPOTS", "TableField.join(ROOT, \"spots\", JoinType.INNER)",
                "ModelQuery.builder(TableField.root(Spot.class), QSpotRow.MAPPER)");
        var field = (ChildField<?, ?>) classes(compilation).loadClass("models.QTaggedCard").getField("SPOTS").get(null);
        assertThat(field.through()).isPresent();
        assertThat(field.query()).isNotNull();
    }

    @Test
    void ac_proc_63_element_model_rejects_wrong_fields_and_uses() {
        Compilation compilation = compileTagged("""
                @com.rey.modelquery.annotations.ElementModel(Spot.class)
                public record SpotRow(@PrimaryKey String city) {}
                """, """
                @com.rey.modelquery.annotations.ElementModel(TaggedEntity.class)
                public record WrongRoot(String name) {}
                """, """
                @QueryModel(root = TaggedEntity.class)
                public record TaggedCard(@PrimaryKey Long id,
                        @Child(through = "spots") Set<SpotRow> set,
                        @Child(through = "spots") Optional<SpotRow> optional,
                        @Child(through = "tags") List<SpotRow> basic,
                        @Child(foreignKey = "id") List<SpotRow> modelChild) {}
                """);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).anySatisfy(error -> assertThat(error).startsWith("MQ3038: WrongRoot:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: SpotRow.city:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: TaggedCard.set:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: TaggedCard.optional:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: TaggedCard.basic:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: TaggedCard.modelChild:"));
    }

    @Test
    void ac_proc_64_element_models_refuse_incompatible_roots_and_nested_uses() {
        Compilation compilation = compileTagged("""
                @com.rey.modelquery.annotations.ElementModel(Spot.class)
                public record SpotRow(String city) {}
                """, """
                @com.rey.modelquery.annotations.ElementModel(Geo.class)
                public record GeoRow(java.math.BigDecimal lat) {}
                """, """
                @QueryModel(root = Spot.class)
                public record BadRoot(String city) {}
                """, """
                @QueryModel(root = SpotRow.class)
                public record BadModelRoot(String city) {}
                """, """
                @QueryModel(root = TaggedEntity.class)
                public record BadField(@PrimaryKey Long id, SpotRow row) {}
                """, """
                @QueryModel(root = TaggedEntity.class)
                public record TaggedCard(@PrimaryKey Long id,
                        @Child(through = "spots") List<GeoRow> wrong,
                        @com.rey.modelquery.annotations.Join Optional<SpotRow> joined) {}
                """);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).anySatisfy(error -> assertThat(error).startsWith("MQ3038: BadRoot:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: BadModelRoot:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: BadField.row:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: TaggedCard.wrong:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: TaggedCard.joined:"));
    }

    @Test
    void ac_proc_65_element_model_rejects_forbidden_members_and_wrong_column_types() {
        Compilation compilation = compileTagged("""
                @com.rey.modelquery.annotations.ElementModel(Spot.class)
                @com.rey.modelquery.annotations.FilterColumn(name = "FILTER", path = "city")
                public record Forbidden(
                        @Child(through = "spots") List<String> children,
                        @com.rey.modelquery.annotations.Join Optional<Forbidden> joined) {}
                """, """
                @com.rey.modelquery.annotations.ElementModel(Spot.class)
                public record WrongType(@com.rey.modelquery.annotations.Column(attribute = "geo.lat") String lat) {}
                """, """
                @com.rey.modelquery.annotations.ElementModel(Spot.class)
                public record Grouped(@GroupBy String city, @Aggregate(fn = AggregateFunction.COUNT) Long n) {}
                """, """
                @com.rey.modelquery.annotations.ElementModel(Spot.class)
                @com.rey.modelquery.annotations.JoinSource(name = "source", source = TaggedEntity.class, on = {})
                public record SourceJoin(String city) {}
                """, """
                @com.rey.modelquery.annotations.ElementModel(Spot.class)
                @com.rey.modelquery.annotations.JoinEntity(name = "entity", entity = TaggedEntity.class, on = {})
                public record EntityJoin(String city) {}
                """, """
                @QueryModel(root = TaggedEntity.class)
                public record GroupedParent(@PrimaryKey Long id, @Child(through = "spots") List<Grouped> spots) {}
                """);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).anySatisfy(error -> assertThat(error).startsWith("MQ3407: Forbidden:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: Forbidden.children:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: Forbidden.joined:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: Grouped.city:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: Grouped.n:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: SourceJoin:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3407: EntityJoin:"))
                .anySatisfy(error -> assertThat(error).startsWith("MQ3002: WrongType.lat:"));
    }

    @Test
    void ac_fch_26_a_value_child_matches_its_golden_file() {
        Compilation compilation = compileTagged("""
                @QueryModel(root = TaggedEntity.class)
                public record TaggedCard(
                        @PrimaryKey Long id,
                        @Child(through = "tags") List<String> tags,
                        @Child(through = "scores") Set<Integer> scores,
                        @Child(through = "parent.notes") List<String> parentNotes) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "models.QTaggedCard")).isEqualTo(resource("golden/QTaggedCard.java"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac_fch_26_a_value_child_keeps_nulls_and_repeats_and_a_set_keeps_the_first_of_equal_values()
            throws Exception {
        Compilation compilation = compileTagged("""
                @QueryModel(root = TaggedEntity.class)
                public record TaggedCard(
                        @PrimaryKey Long id,
                        @Child(through = "tags") List<String> tags,
                        @Child(through = "scores") Set<Integer> scores) {}
                """);
        ClassLoader loader = classes(compilation);
        Class<?> generated = loader.loadClass("models.QTaggedCard");
        var tags = (com.rey.modelquery.core.ValueChildField<Object, String>) generated.getField("TAGS").get(null);
        var scores = (com.rey.modelquery.core.ValueChildField<Object, Integer>) generated.getField("SCORES")
                .get(null);
        Object card = loader.loadClass("models.TaggedCard").getConstructors()[0]
                .newInstance(1L, List.of(), java.util.Set.of());

        Object filled = scores.with(tags.with(card, java.util.Arrays.asList("b", null, "b")),
                java.util.Arrays.asList(3, 1, 3, null));

        assertThat(filled.toString()).isEqualTo("TaggedCard[id=1, tags=[b, null, b], scores=[3, 1, null]]");
        assertThat(card.toString()).isEqualTo("TaggedCard[id=1, tags=[], scores=[]]");
        assertThat(tags.isOrdered()).isFalse();
        assertThat(scores.isOrdered()).isTrue();
        assertThat(tags.ordersByValue()).isTrue();
        assertThat(tags.attribute()).isEqualTo("tags");
        assertThat(tags.owner()).isEqualTo(TableField.root(loader.loadClass("models.TaggedEntity")));
    }

    @Test
    void ac_fch_26_a_value_child_keys_on_the_root_id_whatever_the_primary_key_names() throws Exception {
        // MQ3004 still asks the model for a @PrimaryKey; the value child reads the root's @Id instead, unselected.
        Compilation compilation = compileTagged("""
                @QueryModel(root = TaggedEntity.class)
                public record TaggedNamed(@PrimaryKey String name, @Child(through = "tags") List<String> tags) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        var tags = (com.rey.modelquery.core.ValueChildField<?, ?>) classes(compilation)
                .loadClass("models.QTaggedNamed").getField("TAGS").get(null);
        assertThat(tags.key().path()).isEqualTo("id");
    }

    @Test
    void ac_fch_27_mq3406_refuses_a_value_child_path_it_cannot_load() {
        Compilation compilation = compileTagged("""
                @QueryModel(root = TaggedEntity.class)
                public record TaggedCard(
                        @PrimaryKey Long id,
                        @Child(through = "siblings.tags") List<String> merged,
                        @Child(through = "box.inner") List<String> embedded,
                        @Child(through = "tags.length") List<String> past,
                        @Child(through = "labels") List<String> labels,
                        @Child(key = "name", through = "tags") List<String> notId) {}
                """, """
                @QueryModel(root = PairEntity.class)
                public record PairCard(
                        @PrimaryKey Long a,
                        @PrimaryKey Long b,
                        @Child(through = "tags") List<String> tags) {}
                """, """
                @com.rey.modelquery.annotations.SourceModel(root = TaggedEntity.class)
                public record TaggedTotals(@PrimaryKey Long id) {}
                """, """
                @QueryModel(root = TaggedTotals.class)
                public record TaggedRow(@PrimaryKey Long id, @Child(through = "tags") List<String> tags) {}
                """);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(
                "MQ3406: TaggedCard.merged: through 'siblings.tags' crosses the collection 'siblings' of "
                        + "TaggedEntity before the element collection 'tags' of TaggedEntity, which would merge the "
                        + "elements of every TaggedEntity into one list; put the child on a model of TaggedEntity "
                        + "instead",
                "MQ3406: TaggedCard.embedded: through 'box.inner' crosses the embedded value 'box' of TaggedEntity, "
                        + "which a join can't reach into",
                "MQ3406: TaggedCard.past: through 'tags.length' goes on past the element collection 'tags' of "
                        + "TaggedEntity, whose elements a path can't step into; end it there",
                "MQ3406: TaggedCard.labels: through 'labels' ends at the element collection 'labels', a Map, which "
                        + "a List or Set value child can't hold",
                "MQ3406: TaggedCard.notId: key 'name' is not the single @Id of TaggedEntity, which a through child "
                        + "is matched on",
                "MQ3406: PairCard.tags: PairEntity has a composite id, and a value child is matched on the parent's "
                        + "single @Id",
                "MQ3406: TaggedRow.tags: through 'tags' needs an entity root to join along, and TaggedRow is rooted "
                        + "at TaggedTotals, a @SourceModel; use foreignKey");
    }

    @Test
    void ac_fch_27_mq3407_mq3002_and_mq3401_refuse_a_value_child_field_it_cannot_fill() {
        Compilation compilation = compileTagged("""
                @QueryModel(root = TaggedEntity.class)
                public record TaggedCard(
                        @PrimaryKey Long id,
                        @Child(through = "tags") Optional<String> optional,
                        @Child(through = "tags") java.util.Collection<String> collection,
                        @Child(through = "spots") List<String> spots,
                        @Child(through = "tags") List<Integer> mistyped,
                        @Child(foreignKey = "id", through = "tags") List<String> both,
                        @Child(through = "siblings") Set<TaggedRef> modelSet,
                        @Child(through = "tags") List<TaggedRef> modelThere) {}
                """, """
                @QueryModel(root = TaggedEntity.class)
                public record TaggedRef(@PrimaryKey Long id) {}
                """);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(
                "MQ3407: TaggedCard.optional: through 'tags' ends at the element collection 'tags', and a value "
                        + "child is a List or Set of its element type, found Optional<String>",
                "MQ3407: TaggedCard.collection: through 'tags' ends at the element collection 'tags', and a value "
                        + "child is a List or Set of its element type, found Collection<String>",
                "MQ3407: TaggedCard.spots: through 'spots' ends at the element collection 'spots' of @Embeddable "
                        + "Spot; use a List of an @ElementModel of that embeddable",
                "MQ3002: TaggedCard.mistyped: List<Integer> holds Integer, and the element collection 'tags' holds "
                        + "String",
                "MQ3401: TaggedCard.both: @Child takes foreignKey or through, not both; through matches the child "
                        + "on the parent's @Id read along the path",
                "MQ3401: TaggedCard.modelSet: @Child of a model needs a List or Optional, found Set<TaggedRef>; a "
                        + "Set holds the values of an element collection",
                "MQ3406: TaggedCard.modelThere: through 'tags' ends at the element collection 'tags', not at "
                        + "TaggedEntity, the root of TaggedRef; a value child is a List or Set of its element type");
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

            @Override
            public Row scoped(JoinScope scope) {
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
