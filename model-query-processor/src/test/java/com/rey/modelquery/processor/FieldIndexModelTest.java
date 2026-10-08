package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.FieldIndex;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The generated {@code fields()} (processor/31 R-GEN-32 and R-GEN-33): what the index holds for a model with every
 * kind of field, a field named {@code fields}, a write model, and a model whose entity and converter are named
 * {@code Index}; and the {@code MQ3022} warning for a filter column whose key is already held.
 */
class FieldIndexModelTest {

    private static final String HEAD = """
            package idx;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.Child;
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Computed;
            import com.rey.modelquery.annotations.FilterColumn;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.math.BigDecimal;
            import java.util.List;
            import java.util.Optional;

            """;

    private static final JavaFileObject COUNTRY_ENTITY = source("idx.CountryEntity", """
            package idx;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CountryEntity {
                @Id
                String code;
                String name;
            }
            """);

    private static final JavaFileObject CUSTOMER_ENTITY = source("idx.CustomerEntity", """
            package idx;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
                String email;
                @ManyToOne
                CountryEntity country;
            }
            """);

    private static final JavaFileObject ORDER_ENTITY = source("idx.OrderEntity", """
            package idx;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;
            import java.math.BigDecimal;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                String status;
                BigDecimal amount;
                @ManyToOne
                CustomerEntity customer;
            }
            """);

    private static final JavaFileObject LINE_ENTITY = source("idx.LineEntity", """
            package idx;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class LineEntity {
                @Id
                Long id;
                String sku;
                @ManyToOne
                OrderEntity order;
            }
            """);

    private static final JavaFileObject DOUBLED = source("idx.Doubled", """
            package idx;

            import com.rey.modelquery.core.ColumnField;
            import com.rey.modelquery.core.Expr;
            import com.rey.modelquery.core.ExpressionDefinition;
            import com.rey.modelquery.core.ExpressionField;
            import com.rey.modelquery.core.TableField;
            import java.math.BigDecimal;

            public final class Doubled implements ExpressionDefinition<OrderView, BigDecimal> {
                public static final Doubled INSTANCE = new Doubled();

                @Override
                public ExpressionField<OrderView, BigDecimal> expression() {
                    return Expr.times(ColumnField.of(OrderView.class, TableField.root(OrderEntity.class), "amount",
                            BigDecimal.class), BigDecimal.valueOf(2));
                }
            }
            """);

    private static final JavaFileObject COUNTRY_VIEW = source("idx.CountryView", HEAD + """
            @QueryModel(root = CountryEntity.class)
            public record CountryView(@PrimaryKey String code, String name) {}
            """);

    private static final JavaFileObject CUSTOMER_VIEW = source("idx.CustomerView", HEAD + """
            @QueryModel(root = CustomerEntity.class)
            public record CustomerView(@PrimaryKey Long id, String name, @Join Optional<CountryView> country) {}
            """);

    private static final JavaFileObject LINE_VIEW = source("idx.LineView", HEAD + """
            @QueryModel(root = LineEntity.class)
            public record LineView(@PrimaryKey Long id, String sku) {}
            """);

    /** A column, a computed expression, a join with a join below it, a child, and three filter-only columns. */
    private static final JavaFileObject ORDER_VIEW = source("idx.OrderView", HEAD + """
            @QueryModel(root = OrderEntity.class)
            @FilterColumn(name = "STATUS_RAW", path = "status")
            @FilterColumn(name = "AMOUNT_FILTER", path = "amount")
            @FilterColumn(name = "CUSTOMER_EMAIL", path = "customer.email")
            public record OrderView(
                    @PrimaryKey Long id,
                    String status,
                    @Computed(Doubled.class) BigDecimal doubled,
                    @Join Optional<CustomerView> customer,
                    @Child(foreignKey = "order.id") List<LineView> lines) {}
            """);

    private static final JavaFileObject ORDER_TOTALS = source("idx.OrderTotals", HEAD + """
            @QueryModel(root = OrderEntity.class)
            public record OrderTotals(
                    @GroupBy String status,
                    @Aggregate(fn = AggregateFunction.COUNT) Long lines,
                    @Aggregate(fn = AggregateFunction.SUM, attribute = "amount") BigDecimal revenue) {}
            """);

    /** A field named like the method: its constant is {@code FIELDS}, which Java keeps apart from {@code fields()}. */
    private static final JavaFileObject FIELDS_VIEW = source("idx.FieldsView", HEAD + """
            @QueryModel(root = OrderEntity.class)
            public record FieldsView(@PrimaryKey Long id, @Column(attribute = "status") String fields) {}
            """);

    private static final Compilation COMPILATION = compile(COUNTRY_ENTITY, CUSTOMER_ENTITY, ORDER_ENTITY, LINE_ENTITY,
            DOUBLED, COUNTRY_VIEW, CUSTOMER_VIEW, LINE_VIEW, ORDER_VIEW, ORDER_TOTALS, FIELDS_VIEW);

    private static ClassLoader loader;

    @BeforeAll
    static void load() {
        loader = classes(COMPILATION);
    }

    /** The model is erased: the test names fields by key, not by type. */
    @SuppressWarnings("unchecked")
    private static FieldIndex<Object> fields(String qModel) throws ReflectiveOperationException {
        Method method = loader.loadClass(qModel).getMethod("fields");
        return (FieldIndex<Object>) method.invoke(null);
    }

    // ---- AC-GEN-21

    @Test
    void ac_gen_21_the_index_holds_every_field_set_filter_column_and_child_keyed_as_the_spec_says() throws Exception {
        FieldIndex<Object> index = fields("idx.QOrderView");

        // Selects in the order of SELECTED_FIELDS, then the sets (ALL, DEFAULT and each join's), then the children.
        assertThat(index.names()).containsExactly("id", "status", "customer.id", "customer.name",
                "customer.country.code", "customer.country.name", "doubled", "ALL", "DEFAULT", "customer",
                "customer.country", "lines");
        // Filter-only columns by attribute path, after every select column and expression; STATUS_RAW is left out.
        assertThat(index.filterNames()).containsExactly("id", "status", "customer.id", "customer.name",
                "customer.country.code", "customer.country.name", "doubled", "amount", "customer.email");
        assertThat(index.select("amount")).isEmpty();
        assertThat(index.filter("amount")).isPresent();
        assertThat(index.set("customer.country").orElseThrow().fields())
                .containsExactly(index.select("customer.country.code").orElseThrow(),
                        index.select("customer.country.name").orElseThrow());
        assertThat(constant("idx.QOrderView", "STATUS_RAW")).isNotNull();
    }

    @Test
    void ac_gen_21_the_generated_source_passes_constants_by_kind_in_declaration_order() {
        assertThat(generatedFlat(COMPILATION, "idx.QOrderView"))
                .contains("FieldIndex.builder(OrderView.class) .select(ID, STATUS, CUSTOMER_ID, CUSTOMER_NAME, "
                        + "CUSTOMER_COUNTRY_CODE, CUSTOMER_COUNTRY_NAME, DOUBLED) "
                        + ".filterOnly(AMOUNT_FILTER, CUSTOMER_EMAIL) "
                        + ".set(\"ALL\", ALL) .set(\"DEFAULT\", DEFAULT) "
                        + ".joinSet(CUSTOMER_TABLE, CUSTOMER) .joinSet(CUSTOMER_COUNTRY_TABLE, CUSTOMER_COUNTRY) "
                        + ".child(LINES) .build();");
    }

    @Test
    void ac_gen_21_an_aggregate_is_keyed_by_its_field_name_not_its_function_text() throws Exception {
        FieldIndex<Object> index = fields("idx.QOrderTotals");

        assertThat(index.names()).containsExactly("status", "lines", "revenue", "ALL", "DEFAULT");
        assertThat(index.select("lines").orElseThrow()).isInstanceOf(AggregateField.class);
        assertThat(generatedFlat(COMPILATION, "idx.QOrderTotals"))
                .contains("Agg.<OrderTotals>count(ROOT)")
                .contains(".named(\"lines\")")
                .contains(".named(\"revenue\")")
                .contains(".select(STATUS, LINES, REVENUE)")
                .doesNotContain("GROUP_KEYS, ")
                .doesNotContain(".set(\"GROUP_KEYS\"");
    }

    @Test
    void ac_gen_21_the_index_is_built_on_its_first_call_and_is_one_instance() throws Exception {
        assertThat(fields("idx.QOrderView")).isSameAs(fields("idx.QOrderView"));
        assertThat(generated(COMPILATION, "idx.QOrderView"))
                .contains("private static final class Index {")
                .contains("public static FieldIndex<OrderView> fields() {");
    }

    // ---- AC-GEN-22

    @Test
    void ac_gen_22_a_field_named_fields_keeps_its_constant_and_the_method() throws Exception {
        FieldIndex<Object> index = fields("idx.QFieldsView");

        assertThat(constant("idx.QFieldsView", "FIELDS")).isNotNull();
        assertThat(index.select("fields").orElseThrow()).isSameAs(constant("idx.QFieldsView", "FIELDS"));
    }

    // ---- AC-GEN-24

    @Test
    void ac_gen_24_update_and_insert_models_generate_no_fields() {
        Compilation update = compile(UpdateModelSources.ORDER_PATCH_SOURCES);
        Compilation insert = compile(InsertModelSources.NEW_ORDER_SOURCES);

        assertThat(update).succeededWithoutWarnings();
        assertThat(insert).succeededWithoutWarnings();
        assertThat(generated(update, "patch.QOrderPatch")).doesNotContain("fields()").doesNotContain("FieldIndex");
        assertThat(generated(insert, "ins.QNewOrder")).doesNotContain("fields()").doesNotContain("FieldIndex");
    }

    // ---- AC-GEN-25

    @Test
    void ac_gen_25_an_entity_and_a_converter_named_index_compile_and_fields_works() throws Exception {
        Compilation compilation = compile(
                source("named.Index", """
                        package named;

                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;

                        @Entity
                        public class Index {
                            @Id
                            Long id;
                            Integer code;
                        }
                        """),
                source("named.convert.Index", """
                        package named.convert;

                        import com.rey.modelquery.core.ColumnConverter;

                        public final class Index implements ColumnConverter<String, Integer> {
                            public static final Index INSTANCE = new Index();

                            @Override
                            public String toModel(Integer attribute) {
                                return String.valueOf(attribute);
                            }

                            @Override
                            public Integer toAttribute(String model) {
                                return Integer.valueOf(model);
                            }
                        }
                        """),
                source("named.IndexView", """
                        package named;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;

                        @QueryModel(root = Index.class)
                        public record IndexView(
                                @PrimaryKey Long id, @Column(converter = named.convert.Index.class) String code) {}
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        Method method = classes(compilation).loadClass("named.QIndexView").getMethod("fields");
        var index = (FieldIndex<?>) method.invoke(null);
        assertThat(index.names()).containsExactly("id", "code", "ALL", "DEFAULT");
    }

    // ---- AC-DIAG-11

    @Test
    void ac_diag_11_a_filter_column_whose_key_is_held_is_left_out_with_mq3022_and_stays_a_constant() {
        assertThat(COMPILATION).succeeded();
        assertThat(COMPILATION).hadWarningCount(1);
        assertThat(COMPILATION).hadWarningContaining("MQ3022: OrderView @FilterColumn(STATUS_RAW): its key 'status' "
                + "is already held by the column 'status'; fields() leaves it out, and it stays a constant");
        assertThat(generatedFlat(COMPILATION, "idx.QOrderView"))
                .contains("ColumnField<OrderView, OrderEntity, String> STATUS_RAW = ")
                .doesNotContain("STATUS_RAW)");
    }

    @Test
    void ac_diag_11_a_second_filter_column_on_one_path_is_left_out_with_mq3022() {
        JavaFileObject twice = source("idx.TwiceView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                @FilterColumn(name = "AMOUNT_A", path = "amount")
                @FilterColumn(name = "AMOUNT_B", path = "amount")
                public record TwiceView(@PrimaryKey Long id) {}
                """);
        Compilation compilation = compile(ORDER_ENTITY, CUSTOMER_ENTITY, COUNTRY_ENTITY, twice);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningCount(1);
        assertThat(compilation).hadWarningContaining("MQ3022: TwiceView @FilterColumn(AMOUNT_B): its key 'amount' "
                + "is already held by @FilterColumn(AMOUNT_A); fields() leaves it out, and it stays a constant");
        assertThat(generatedFlat(compilation, "idx.QTwiceView")).contains(".filterOnly(AMOUNT_A)");
    }

    private static Object constant(String qModel, String name) throws ReflectiveOperationException {
        Field field = loader.loadClass(qModel).getField(name);
        return field.get(null);
    }
}
