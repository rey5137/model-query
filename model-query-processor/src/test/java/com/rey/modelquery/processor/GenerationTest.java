package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SortSpec.Key;
import com.rey.modelquery.core.SortSpec;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** What the processor generates for class, record and nested models ({@code processor/30}, {@code processor/31}). */
class GenerationTest {

    private static final String MODEL_IMPORTS = """
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.processor.fixture.*;
            """;

    /** A record model named {@code models.<name>} over a fixture entity on the classpath. */
    private static JavaFileObject model(String name, String root, String components) {
        return source("models." + name, "package models;\n" + MODEL_IMPORTS
                + "@QueryModel(root = " + root + ".class)\n"
                + "public record " + name + "(" + components + ") {}\n");
    }

    @Test
    void ac_gen_01_class_model_matches_its_golden_file() {
        Compilation compilation = compile(ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, ShopSources.ORDER_VIEW);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QOrderView")).isEqualTo(resource("golden/QOrderView.java"));
    }

    @Test
    void ac_gen_01_record_model_matches_its_golden_file() {
        Compilation compilation = compile(ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, ShopSources.ORDER_SUMMARY);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QOrderSummary")).isEqualTo(resource("golden/QOrderSummary.java"));
    }

    /** {@link ShopSources#INVOICE_SOURCES} with the pair of models that nest: the outer one and {@code nested}. */
    private static JavaFileObject[] invoiceSources(JavaFileObject nested) {
        return Stream.concat(Stream.of(ShopSources.INVOICE_SOURCES), Stream.of(nested, ShopSources.INVOICE_VIEW))
                .toArray(JavaFileObject[]::new);
    }

    @Test
    void ac_gen_01_nested_pair_matches_its_golden_files() {
        Compilation compilation = compile(invoiceSources(ShopSources.CUSTOMER_VIEW));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QInvoiceView")).isEqualTo(resource("golden/QInvoiceView.java"));
        assertThat(generated(compilation, "shop.QCustomerView")).isEqualTo(resource("golden/QCustomerView.java"));
    }

    @Test
    void ac_gen_03_a_column_added_to_the_nested_model_joins_the_outer_column_set() {
        Compilation before = compile(invoiceSources(ShopSources.CUSTOMER_VIEW));
        // Only the nested model changes: the outer model's source is the same object in both compilations.
        Compilation after = compile(invoiceSources(ShopSources.customerView("""
                    String email;

                    public void setEmail(String email) {
                        this.email = email;
                    }
                """)));

        assertThat(after).succeededWithoutWarnings();
        assertThat(generatedFlat(before, "shop.QInvoiceView"))
                .contains("SelectSet<InvoiceView> CUSTOMER = SelectSet.of(CUSTOMER_ID, CUSTOMER_NAME);")
                .doesNotContain("EMAIL");
        assertThat(generatedFlat(after, "shop.QInvoiceView"))
                .contains("ColumnField<InvoiceView, CustomerEntity, String> CUSTOMER_EMAIL = "
                        + "QCustomerView.EMAIL.withTable(InvoiceView.class, CUSTOMER_TABLE);")
                .contains("SelectSet<InvoiceView> CUSTOMER = SelectSet.of(CUSTOMER_ID, CUSTOMER_NAME, "
                        + "CUSTOMER_EMAIL);")
                .contains("SelectSet<InvoiceView> BUYER = SelectSet.of(BUYER_ID, BUYER_NAME, BUYER_EMAIL);");
    }

    @Test
    void ac_qry_13_a_generated_model_sorts_by_property_paths_through_a_renamed_field_and_two_joins()
            throws Exception {
        // The nested field "contact" reads the attribute "email", and two @Joins read the one attribute "customer".
        Compilation compilation = compile(invoiceSources(ShopSources.customerView("""
                    @com.rey.modelquery.annotations.Column(attribute = "email")
                    String contact;

                    public void setContact(String contact) {
                        this.contact = contact;
                    }
                """)));
        assertThat(compilation).succeededWithoutWarnings();
        Class<?> qModel = ProcessorHarness.classes(compilation).loadClass("shop.QInvoiceView");
        ModelQuery<?, ?, ?> query = selectingAll(qModel, "ALL", "CUSTOMER", "BUYER", "CUSTOMER_COUNTRY");

        var sorted = query.orderedBy(SortSpec.of(Key.desc("customer.contact"), Key.asc("payer.contact"),
                Key.asc("customer.country.code"), Key.asc("status")));

        assertThat(new ArrayList<Object>(sorted.orderBy())).containsExactly(column(qModel, "CUSTOMER_CONTACT").desc(),
                column(qModel, "BUYER_CONTACT").asc(), column(qModel, "CUSTOMER_COUNTRY_CODE").asc(),
                column(qModel, "STATUS").asc());
        // The attribute path of both joins' column names both, and a bare name no joined column.
        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("customer.email"))))
                .hasMessageStartingWith("MQ2301: InvoiceView: sort property 'customer.email' names more than one "
                        + "selected column: [customer.contact reading customer.email (attribute path), payer.contact "
                        + "reading customer.email (attribute path)]");
        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("contact"))))
                .hasMessageStartingWith("MQ2301: InvoiceView: sort property 'contact' names no selected column");
    }

    /** The query of {@code qModel} selecting its column sets named {@code sets}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ModelQuery<?, ?, ?> selectingAll(Class<?> qModel, String... sets)
            throws ReflectiveOperationException {
        SelectSet columns = (SelectSet) qModel.getField(sets[0]).get(null);
        for (int i = 1; i < sets.length; i++) {
            columns = columns.with((SelectSet) qModel.getField(sets[i]).get(null));
        }
        return ((ModelQuery.Builder) qModel.getMethod("query").invoke(null)).select(columns).build();
    }

    @Test
    void ac_proc_04_a_converter_without_an_instance_is_constructed_and_bridges_a_parameterized_attribute() {
        Compilation compilation = compile(
                source("tags.TagEntity", """
                        package tags;

                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;
                        import java.util.List;

                        @Entity
                        public class TagEntity {
                            @Id
                            Long id;
                            List<String> tags;
                        }
                        """),
                source("tags.Joined", """
                        package tags;

                        import com.rey.modelquery.core.ColumnConverter;
                        import java.util.List;

                        class Joined implements ColumnConverter<String, List<String>> {
                            @Override
                            public String toModel(List<String> attribute) {
                                return String.join(",", attribute);
                            }

                            @Override
                            public List<String> toAttribute(String model) {
                                return List.of(model.split(","));
                            }
                        }
                        """),
                source("tags.TagRow", """
                        package tags;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;

                        @QueryModel(root = TagEntity.class)
                        public record TagRow(@PrimaryKey Long id, @Column(converter = Joined.class) String tags) {}
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "tags.QTagRow")).contains(
                "@SuppressWarnings(\"unchecked\") public static final ColumnField<TagRow, TagEntity, String> TAGS = "
                        + "ColumnField.of(TagRow.class, ROOT, \"tags\", String.class, "
                        + "(Class<List<String>>) (Class<?>) List.class, new Joined()) .named(\"tags\");");
    }

    @Test
    void ac_gen_02_id_embedded_and_mapped_superclass_attributes_generate() {
        Compilation compilation = compile(model("OrderRow", "OrderEntity", """
                @PrimaryKey Long id,
                java.time.Instant createdAt,
                @Column(attribute = "address.city") String city,
                @PrimaryKey int quantity"""));

        assertThat(compilation).succeededWithoutWarnings();
        // id is declared as the mapped superclass's type variable, and read as the subclass binds it.
        assertThat(generatedFlat(compilation, "models.QOrderRow"))
                .contains("ColumnField<OrderRow, OrderEntity, Long> ID = ColumnField.of(OrderRow.class, ROOT, \"id\", "
                        + "Long.class)")
                .contains("ColumnField.of(OrderRow.class, ROOT, \"createdAt\", Instant.class)")
                .contains("ColumnField.of(OrderRow.class, ROOT, \"address.city\", String.class)")
                .contains("ColumnField<OrderRow, OrderEntity, Integer> QUANTITY")
                .contains("SelectSet.of(ID, CREATED_AT, CITY, QUANTITY)");
    }

    @Test
    void ac_gen_02_embedded_id_generates_dotted_key_columns() {
        Compilation compilation = compile(model("OrderLineRow", "OrderLineEntity", """
                @PrimaryKey @Column(attribute = "id.orderId") Long orderId,
                @PrimaryKey @Column(attribute = "id.lineNo") Integer lineNo,
                Integer quantity"""));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "models.QOrderLineRow"))
                .contains("ORDER_ID = ColumnField.of(OrderLineRow.class, ROOT, \"id.orderId\", Long.class)")
                .contains("LINE_NO = ColumnField.of(OrderLineRow.class, ROOT, \"id.lineNo\", Integer.class)")
                .contains("PrimaryKey<OrderLineRow, List<Object>> KEY = PrimaryKey.composite(ORDER_ID, LINE_NO)");
    }

    @Test
    void ac_gen_02_id_class_generates_a_composite_key() {
        Compilation compilation = compile(model("StockRow", "StockEntity", """
                @PrimaryKey Long warehouseId,
                @PrimaryKey Long productId,
                Integer quantity"""));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "models.QStockRow"))
                .contains("PrimaryKey<StockRow, List<Object>> KEY = PrimaryKey.composite(WAREHOUSE_ID, PRODUCT_ID)")
                .contains("public static ModelQuery.Builder<StockEntity, List<Object>, StockRow> query()");
    }

    @Test
    void ac_gen_02_property_access_reads_getters_when_declared_or_when_the_id_is_on_a_getter() {
        Compilation compilation = compile(
                model("PropertyRow", "PropertyAccessEntity",
                        "@PrimaryKey Long id, String label, Boolean active, Integer rank"),
                model("GetterRow", "GetterIdEntity", "@PrimaryKey Long id, String name"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "models.QPropertyRow")).contains("SelectSet.of(ID, LABEL, ACTIVE, RANK)");
        assertThat(generatedFlat(compilation, "models.QGetterRow")).contains("SelectSet.of(ID, NAME)");
    }

    @Test
    void ac_gen_02_access_type_decides_whether_a_field_or_a_getter_is_the_attribute() {
        Compilation compilation = compile(
                // Property access: the backing field and the @Transient getter are not attributes.
                model("PropertyRow", "PropertyAccessEntity", "@PrimaryKey Long id, String labelValue, String derived"),
                model("GetterRow", "GetterIdEntity", "@PrimaryKey Long id, String text"),
                // Field access: a getter, a static field and a @Transient field are not attributes.
                model("OrderRow", "OrderEntity", "@PrimaryKey Long id, String label, String scratch"));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                DiagnosticCode.MQ3001.code() + ": PropertyRow.labelValue: no attribute 'labelValue' on "
                        + "PropertyAccessEntity",
                DiagnosticCode.MQ3001.code() + ": PropertyRow.derived: no attribute 'derived' on PropertyAccessEntity",
                DiagnosticCode.MQ3001.code() + ": GetterRow.text: no attribute 'text' on GetterIdEntity",
                DiagnosticCode.MQ3001.code() + ": OrderRow.label: no attribute 'label' on OrderEntity",
                DiagnosticCode.MQ3001.code() + ": OrderRow.scratch: no attribute 'scratch' on OrderEntity");
    }

    @Test
    void ac_gen_02_to_one_associations_generate_as_columns_of_the_target_entity_with_a_warning() {
        Compilation compilation = compile(model("OrderRow", "OrderEntity",
                "@PrimaryKey Long id, CustomerEntity customer, CustomerEntity payer"));

        assertThat(compilation).succeeded();
        assertThat(compilation.warnings().stream().map(warning -> warning.getMessage(Locale.ROOT)))
                .containsExactly(
                        DiagnosticCode.MQ3016.code() + ": OrderRow.customer: selects the whole CustomerEntity entity; "
                                + "use @Join with a query model of CustomerEntity to select only its columns",
                        DiagnosticCode.MQ3016.code() + ": OrderRow.payer: selects the whole CustomerEntity entity; "
                                + "use @Join with a query model of CustomerEntity to select only its columns");
        assertThat(generatedFlat(compilation, "models.QOrderRow"))
                .contains("ColumnField<OrderRow, OrderEntity, CustomerEntity> CUSTOMER = ColumnField.of("
                        + "OrderRow.class, ROOT, \"customer\", CustomerEntity.class)")
                .contains("ColumnField<OrderRow, OrderEntity, CustomerEntity> PAYER");
    }

    @Test
    void ac_gen_02_a_converted_to_one_column_is_not_warned_of() {
        Compilation compilation = compile(
                source("models.CustomerName", """
                        package models;

                        import com.rey.modelquery.core.ColumnConverter;
                        import com.rey.modelquery.processor.fixture.CustomerEntity;

                        class CustomerName implements ColumnConverter<String, CustomerEntity> {
                            @Override
                            public String toModel(CustomerEntity attribute) {
                                return attribute.toString();
                            }

                            @Override
                            public CustomerEntity toAttribute(String model) {
                                throw new UnsupportedOperationException();
                            }
                        }
                        """),
                model("OrderRow", "OrderEntity",
                        "@PrimaryKey Long id, @Column(converter = CustomerName.class) String customer"));

        // D-45: the field holds the converter's value, so MQ3016's advice to use @Join does not apply.
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "models.QOrderRow"))
                .contains("ColumnField<OrderRow, OrderEntity, String> CUSTOMER = ColumnField.of(");
    }

    @Test
    void ac_gen_03_a_nested_model_may_be_read_from_the_classpath() {
        // This module compiles with the processor off, so the fixture's QModel is generated here from a copy of its
        // source, then compiled beside the outer model while CustomerSummary itself comes from the classpath.
        String fixture = "com.rey.modelquery.processor.fixture.";
        String nestedQModel = generated(compile(source(fixture + "CustomerSummary", """
                package com.rey.modelquery.processor.fixture;

                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = CustomerEntity.class)
                public record CustomerSummary(@PrimaryKey Long id, String name) {}
                """)), fixture + "QCustomerSummary");

        Compilation compilation = compile(
                source(fixture + "QCustomerSummary", nestedQModel),
                model("OrderRow", "OrderEntity", "@PrimaryKey Long id, "
                        + "@com.rey.modelquery.annotations.Join java.util.Optional<CustomerSummary> customer"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "models.QOrderRow"))
                .contains(".presentBy(QCustomerSummary.KEY)")
                .contains("ColumnField<OrderRow, CustomerEntity, String> CUSTOMER_NAME = "
                        + "QCustomerSummary.NAME.withTable(OrderRow.class, CUSTOMER_TABLE)");
    }

    @Test
    void ac_gen_02_a_column_path_does_not_cross_an_association() {
        Compilation compilation = compile(model("OrderRow", "OrderEntity", """
                @PrimaryKey Long id,
                @Column(attribute = "customer.name") String customerName,
                @Column(attribute = "payer.name") String payerName,
                @Column(attribute = "items.id") Long itemId,
                @Column(attribute = "related.id") Long relatedId,
                @Column(attribute = "customer.nme") String misspelt"""));

        assertThat(errors(compilation)).containsExactly(
                DiagnosticCode.MQ3001.code() + ": OrderRow.customerName: 'customer' on OrderEntity is an association, "
                        + "so 'customer.name' needs @Join or @FilterColumn",
                DiagnosticCode.MQ3001.code() + ": OrderRow.payerName: 'payer' on OrderEntity is an association, "
                        + "so 'payer.name' needs @Join or @FilterColumn",
                DiagnosticCode.MQ3001.code() + ": OrderRow.itemId: 'items' on OrderEntity is an association, "
                        + "so 'items.id' needs @Join or @FilterColumn",
                DiagnosticCode.MQ3001.code() + ": OrderRow.relatedId: 'related' on OrderEntity is an association, "
                        + "so 'related.id' needs @Join or @FilterColumn",
                // The association is what stops the path, whatever follows it.
                DiagnosticCode.MQ3001.code() + ": OrderRow.misspelt: 'customer' on OrderEntity is an association, "
                        + "so 'customer.nme' needs @Join or @FilterColumn");
    }

    @Test
    void ac_gen_02_collection_associations_are_recognised_and_cannot_be_a_column() {
        Compilation compilation = compile(model("OrderRow", "OrderEntity", """
                @PrimaryKey Long id,
                java.util.List<ItemEntity> items,
                java.util.Set<ItemEntity> related"""));

        assertThat(errors(compilation)).containsExactly(
                DiagnosticCode.MQ3002.code() + ": OrderRow.items: model type List<ItemEntity>, entity attribute "
                        + "'items' is a collection, which a column can't select; filter on it with Filters.exists",
                DiagnosticCode.MQ3002.code() + ": OrderRow.related: model type Set<ItemEntity>, entity attribute "
                        + "'related' is a collection, which a column can't select; filter on it with Filters.exists");
    }

    @Test
    void ac_gen_04_partial_column_set_leaves_unselected_class_fields_at_their_initialiser() throws Exception {
        Compilation compilation = compile(ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, ShopSources.ORDER_VIEW);
        Class<?> qModel = ProcessorHarness.classes(compilation).loadClass("shop.QOrderView");
        var values = new HashMap<SelectField<?, ?>, Object>();
        values.put(column(qModel, "ID"), 7L);
        // Selected and NULL: the setter runs, unlike for a column that is not selected.
        values.put(column(qModel, "TOTAL"), null);

        Object model = mapper(qModel).map(ProcessorHarness.row(values));

        assertThat(read(model, "id")).isEqualTo(7L);
        assertThat(read(model, "total")).isNull();
        assertThat(read(model, "status")).isEqualTo("NEW");
        assertThat(read(model, "city")).isNull();
        assertThat(read(model, "paid")).isEqualTo(false);
        assertThat(read(model, "label")).isEqualTo("unmapped");
    }

    @Test
    void ac_gen_04_partial_column_set_leaves_unselected_record_components_null() throws Exception {
        Compilation compilation = compile(ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, ShopSources.ORDER_SUMMARY);
        Class<?> qModel = ProcessorHarness.classes(compilation).loadClass("shop.QOrderSummary");
        Map<SelectField<?, ?>, Object> values = Map.of(column(qModel, "ID"), 7L, column(qModel, "STATUS"), "PAID");

        Object model = mapper(qModel).map(ProcessorHarness.row(values));

        assertThat(read(model, "id")).isEqualTo(7L);
        assertThat(read(model, "status")).isEqualTo("PAID");
        assertThat(read(model, "city")).isNull();
        assertThat(read(model, "notes")).isNull();
        assertThat(read(model, "label")).isNull();
    }

    private static final JavaFileObject FLAG_ENTITY = source("flags.FlagEntity", """
            package flags;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class FlagEntity {
                @Id
                Long id;
                Boolean isActive;
                boolean isDeleted;
                boolean paid;
            }
            """);

    @Test
    void ac_gen_05_lombok_setters_compile_including_both_boolean_is_prefixes() {
        Compilation compilation = javac()
                .withProcessors(ProcessorHarness.lombok(), new ModelQueryProcessor())
                .compile(FLAG_ENTITY, source("flags.FlagView", """
                        package flags;

                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;

                        @lombok.Getter
                        @lombok.Setter
                        @lombok.NoArgsConstructor
                        @lombok.AllArgsConstructor
                        @QueryModel(root = FlagEntity.class)
                        public class FlagView {
                            @PrimaryKey
                            private Long id;
                            private Boolean isActive;
                            private boolean isDeleted;
                            private boolean paid;
                        }
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "flags.QFlagView"))
                .contains("m.setIsActive(row.get(IS_ACTIVE))")
                .contains("m.setDeleted(row.get(IS_DELETED))")
                .contains("m.setPaid(row.get(PAID))");
    }

    @Test
    void ac_gen_05_hand_written_setters_compile_including_both_boolean_is_prefixes() {
        Compilation compilation = compile(FLAG_ENTITY, source("flags.FlagView", """
                package flags;

                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = FlagEntity.class)
                public class FlagView {
                    @PrimaryKey
                    private Long id;
                    private Boolean isActive;
                    private boolean isDeleted;
                    private boolean paid;

                    public void setId(Long id) {
                        this.id = id;
                    }

                    public void setIsActive(Boolean isActive) {
                        this.isActive = isActive;
                    }

                    public void setDeleted(boolean isDeleted) {
                        this.isDeleted = isDeleted;
                    }

                    public void setPaid(boolean paid) {
                        this.paid = paid;
                    }
                }
                """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "flags.QFlagView"))
                .contains("m.setIsActive(row.get(IS_ACTIVE))")
                .contains("m.setDeleted(row.get(IS_DELETED))");
    }

    @Test
    void ac_gen_05_a_lombok_no_arg_constructor_is_accepted_whichever_processor_runs_first() {
        JavaFileObject view = source("flags.FlagView", """
                package flags;

                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @lombok.Setter
                @lombok.NoArgsConstructor
                @QueryModel(root = FlagEntity.class)
                public class FlagView {
                    @PrimaryKey
                    private Long id;

                    public FlagView(Long id) {
                        this.id = id;
                    }
                }
                """);

        // Ordered before Lombok, the processor cannot see the constructor Lombok adds (D-44).
        assertThat(javac().withProcessors(new ModelQueryProcessor(), ProcessorHarness.lombok())
                .compile(FLAG_ENTITY, view)).succeededWithoutWarnings();
        assertThat(javac().withProcessors(ProcessorHarness.lombok(), new ModelQueryProcessor())
                .compile(FLAG_ENTITY, view)).succeededWithoutWarnings();
    }

    @Test
    void r_gen_03_a_parameterized_column_type_and_a_dollar_in_a_field_name_generate() {
        Compilation compilation = compile(
                source("odd.OddEntity", """
                        package odd;

                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;
                        import java.util.Map;
import java.util.stream.Stream;

                        @Entity
                        public class OddEntity {
                            @Id
                            Long id;
                            Map<String, String> labels;
                            String a$b;
                        }
                        """),
                source("odd.OddView", """
                        package odd;

                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;
                        import java.util.Map;
import java.util.stream.Stream;

                        @QueryModel(root = OddEntity.class)
                        public record OddView(@PrimaryKey Long id, Map<String, String> labels, String a$b) {}
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "odd.QOddView"))
                .contains("(Class<Map<String, String>>) (Class<?>) Map.class")
                .contains("SelectSet.of(ID, LABELS, A$B)");
    }

    @Test
    void ac_gen_05_a_missing_setter_is_left_to_javac() {
        // R-GEN-11, R-DIAG-06: a missing setter is javac's error on the generated call, not a processor diagnostic.
        Compilation compilation = compile(FLAG_ENTITY, source("flags.FlagView", """
                package flags;

                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = FlagEntity.class)
                public class FlagView {
                    @PrimaryKey
                    Long id;
                }
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).singleElement().asString().contains("setId").doesNotContain("MQ3");
    }

    @Test
    void ac_gen_08_each_generated_file_originates_from_its_model_alone() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(
                ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, ShopSources.ORDER_VIEW, ShopSources.ORDER_SUMMARY);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(processor.originating()).containsOnlyKeys("shop.QOrderView", "shop.QOrderSummary");
        assertThat(processor.originating().get("shop.QOrderView")).singleElement().satisfies(element -> {
            assertThat(element.getKind()).isEqualTo(ElementKind.CLASS);
            assertThat(element).hasToString("shop.OrderView");
        });
        assertThat(processor.originating().get("shop.QOrderSummary")).singleElement().satisfies(element -> {
            assertThat(element.getKind()).isEqualTo(ElementKind.RECORD);
            assertThat(element).hasToString("shop.OrderSummary");
        });
    }

    @Test
    void ac_gen_08_the_processor_is_registered_as_isolating_for_gradle() throws IOException {
        // Lombok's jar carries a registration of its own at the same path, so every one on the classpath is read.
        var lines = new ArrayList<String>();
        for (URL url : Collections.list(ModelQueryProcessor.class.getClassLoader()
                .getResources("META-INF/gradle/incremental.annotation.processors"))) {
            try (var in = url.openStream()) {
                lines.addAll(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
            }
        }

        assertThat(lines).contains(ModelQueryProcessor.class.getName() + ",isolating");
    }

    @Test
    void ac_proc_02_root_entity_only_on_the_classpath_generates() {
        Compilation compilation = compile(model("OrderRow", "OrderEntity", "@PrimaryKey Long id, String status"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "models.QOrderRow"))
                .contains("import com.rey.modelquery.processor.fixture.OrderEntity;")
                .contains("TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class)")
                .contains("ColumnField.of(OrderRow.class, ROOT, \"status\", String.class)");
    }

    @Test
    void ac_proc_03_prefix_option_renames_the_generated_class_and_every_reference_in_it() {
        Compilation compilation = javac()
                .withProcessors(new ModelQueryProcessor())
                .withOptions("-A" + QueryModelReader.PREFIX_OPTION + "=X")
                .compile(ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, ShopSources.ORDER_VIEW);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation.generatedSourceFile("shop.QOrderView")).isEmpty();
        assertThat(generatedFlat(compilation, "shop.XOrderView"))
                .contains("public final class XOrderView {")
                .contains("MAPPER = XOrderView::map")
                .contains("private XOrderView() {")
                .doesNotContain("QOrderView");
    }

    @Test
    void ac_proc_03_suffix_option_and_annotation_values_name_the_generated_class() {
        Compilation compilation = javac()
                .withProcessors(new ModelQueryProcessor())
                .withOptions("-A" + QueryModelReader.PREFIX_OPTION + "=X", "-A" + QueryModelReader.SUFFIX_OPTION + "=_")
                .compile(
                        model("OrderRow", "OrderEntity", "@PrimaryKey Long id"),
                        // A name written on the annotation wins over the option (D-44).
                        source("models.StockRow", "package models;\n" + MODEL_IMPORTS + """
                                @QueryModel(root = StockEntity.class, prefix = "", suffix = "Columns")
                                public record StockRow(@PrimaryKey Long warehouseId) {}
                                """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "models.XOrderRow_")).contains("MAPPER = XOrderRow_::map");
        assertThat(generatedFlat(compilation, "models.StockRowColumns")).contains("MAPPER = StockRowColumns::map");
    }

    private static SelectField<?, ?> column(Class<?> qModel, String constant) throws ReflectiveOperationException {
        return (SelectField<?, ?>) qModel.getField(constant).get(null);
    }

    private static RowMapper<?> mapper(Class<?> qModel) throws ReflectiveOperationException {
        return (RowMapper<?>) qModel.getField("MAPPER").get(null);
    }

    private static Object read(Object model, String field) throws ReflectiveOperationException {
        Field declared = model.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        return declared.get(model);
    }
}
