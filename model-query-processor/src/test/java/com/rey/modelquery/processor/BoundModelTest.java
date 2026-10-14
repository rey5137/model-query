package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.compileAgainst;
import static com.rey.modelquery.processor.ProcessorHarness.compileToClasspath;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A model compiled without its entity, bound by {@code @BindQueryModel} in the module that sees it (R-PROC-28). */
class BoundModelTest {

    /** {@link ShopSources#ORDER_VIEW} less its {@code @QueryModel}: a model module that sees no entity. */
    private static final JavaFileObject ORDER_VIEW = unannotated(ShopSources.ORDER_VIEW);

    private static final JavaFileObject ORDER_LINE = source("shop.OrderLine", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;

            public record OrderLine(@PrimaryKey Long id, String status) {}
            """);

    private static final String ORDER_ROW = """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;

            public class OrderRow {
                @PrimaryKey
                private Long id;
                private String status;

                public OrderRow() {}

                public void setId(Long id) {
                    this.id = id;
                }

                public void setStatus(String status) {
                    this.status = status;
                }
            }
            """;

    /** Models another package can name only from a subclass's body; a binding there compiles, and its QModel can't. */
    private static final JavaFileObject OUTER = source("shop.Outer", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;

            public class Outer {
                protected record OrderRow(@PrimaryKey Long id, String status) {}

                protected static class Inner {
                    public record OrderRow(@PrimaryKey Long id, String status) {}
                }
            }
            """);

    private static final String NOT_PUBLIC = ", but QOrderRow is generated in persistence and calls it; make it public";

    @TempDir
    Path classes;

    @Test
    void ac_gen_30_bound_model_matches_the_annotated_golden_apart_from_its_package() {
        compileToClasspath(classes, ORDER_VIEW);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                binding("OrderModels", "@BindQueryModel(model = OrderView.class, root = OrderEntity.class)"));

        assertThat(compilation).succeededWithoutWarnings();
        String bound = generated(compilation, "persistence.QOrderView");
        assertThat(bound).isEqualTo(resource("golden/bound/QOrderView.java"));
        // Moving the QModel to the binding's package changes its package line and imports the model and its root.
        assertThat(bound.replace("package persistence;", "package shop;")
                .replace("import shop.OrderEntity;\n", "")
                .replace("import shop.OrderView;\n", ""))
                .isEqualTo(resource("golden/QOrderView.java"));
        assertThat(compilation.generatedSourceFile("shop.QOrderView")).isEmpty();
        assertThat(processor.originating()).containsOnlyKeys("persistence.QOrderView");
        assertThat(processor.originating().get("persistence.QOrderView")).singleElement().satisfies(element -> {
            assertThat(element.getKind()).isEqualTo(ElementKind.CLASS);
            assertThat(element).hasToString("persistence.OrderModels");
        });
    }

    @Test
    void ac_proc_23_bindings_repeated_on_one_type_generate_each_model() {
        compileToClasspath(classes, ORDER_VIEW, ORDER_LINE);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                binding("OrderModels", """
                        @BindQueryModel(model = OrderView.class, root = OrderEntity.class)
                        @BindQueryModel(model = OrderLine.class, root = OrderEntity.class,
                                prefix = "", suffix = "Columns")
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "persistence.OrderLineColumns"))
                .contains("public final class OrderLineColumns", "import shop.OrderLine;", "new OrderLine(");
        assertThat(processor.originating()).containsOnlyKeys("persistence.QOrderView", "persistence.OrderLineColumns");
        assertThat(processor.originating().values()).allSatisfy(elements -> assertThat(elements).singleElement()
                .hasToString("persistence.OrderModels"));
    }

    @Test
    void ac_proc_23_binding_on_a_package_info_generates_in_that_package() {
        compileToClasspath(classes, ORDER_VIEW);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                source("persistence.package-info", """
                        @BindQueryModel(model = OrderView.class, root = OrderEntity.class)
                        package persistence;

                        import com.rey.modelquery.annotations.BindQueryModel;
                        import shop.OrderEntity;
                        import shop.OrderView;
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "persistence.QOrderView"))
                .isEqualTo(resource("golden/bound/QOrderView.java"));
        assertThat(processor.originating().get("persistence.QOrderView")).singleElement().satisfies(element -> {
            assertThat(element.getKind()).isEqualTo(ElementKind.PACKAGE);
            assertThat(element).hasToString("persistence");
        });
    }

    @Test
    void ac_proc_23_model_bound_twice_reports_mq3023_on_each_binding() {
        compileToClasspath(classes, ORDER_VIEW);
        String bind = "@BindQueryModel(model = OrderView.class, root = OrderEntity.class)";

        var processor = new ProcessorHarness.Recording();
        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS,
                ShopSources.ORDER_ENTITY, binding("OrderModels", bind), binding("MoreModels", bind));

        assertThat(compilation).failed();
        String message = "MQ3023: OrderView: @BindQueryModel binds it more than once, on ";
        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message + "persistence.OrderModels and persistence.MoreModels; keep one",
                message + "persistence.OrderModels and persistence.MoreModels; keep one");
        assertThat(compilation.errors()).extracting(error -> error.getSource().getName())
                .containsExactlyInAnyOrder("persistence/OrderModels.java", "persistence/MoreModels.java");
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderView");
    }

    @Test
    void ac_proc_23_model_bound_twice_on_one_type_reports_mq3023_once() {
        compileToClasspath(classes, ORDER_VIEW);
        String bind = "@BindQueryModel(model = OrderView.class, root = OrderEntity.class)\n";

        var processor = new ProcessorHarness.Recording();
        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS,
                ShopSources.ORDER_ENTITY, binding("OrderModels", bind + bind));

        assertThat(errors(compilation)).containsExactly(
                "MQ3023: OrderView: @BindQueryModel binds it more than once, on persistence.OrderModels; keep one");
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderView");
    }

    @Test
    void ac_proc_23_models_of_one_simple_name_bound_into_one_package_report_mq3023_on_each() {
        compileToClasspath(classes, ORDER_LINE, source("other.OrderLine", """
                package other;

                import com.rey.modelquery.annotations.PrimaryKey;

                public record OrderLine(@PrimaryKey Long id, String status) {}
                """));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                binding("OrderModels", """
                        @BindQueryModel(model = OrderLine.class, root = OrderEntity.class)
                        @BindQueryModel(model = other.OrderLine.class, root = OrderEntity.class)
                        """));

        String clash = " on persistence.OrderModels each generate persistence.QOrderLine; give one another prefix or "
                + "package";
        assertThat(errors(compilation)).containsExactly(
                "MQ3023: OrderLine: @BindQueryModel on persistence.OrderModels and @BindQueryModel of other.OrderLine"
                        + clash,
                "MQ3023: OrderLine: @BindQueryModel of shop.OrderLine on persistence.OrderModels and @BindQueryModel"
                        + clash);
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_23_binding_generating_an_annotated_models_qmodel_name_is_mq3023_and_the_annotated_one_generates() {
        compileToClasspath(classes, ORDER_LINE);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                source("persistence.OrderLine", """
                        package persistence;

                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;
                        import shop.OrderEntity;

                        @QueryModel(root = OrderEntity.class)
                        public record OrderLine(@PrimaryKey Long id, String status) {}
                        """),
                binding("OrderModels", "@BindQueryModel(model = shop.OrderLine.class, root = OrderEntity.class)"));

        assertThat(errors(compilation)).containsExactly("MQ3023: OrderLine: @BindQueryModel on "
                + "persistence.OrderModels and @QueryModel persistence.OrderLine each generate persistence.QOrderLine; "
                + "give one another prefix or package");
        assertThat(processor.originating()).containsOnlyKeys("persistence.QOrderLine");
        assertThat(processor.originating().get("persistence.QOrderLine")).singleElement()
                .hasToString("persistence.OrderLine");
    }

    @Test
    void ac_proc_23_annotated_model_on_the_classpath_bound_is_mq3023() {
        compileToClasspath(classes, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, ShopSources.ORDER_VIEW);

        var processor = new ProcessorHarness.Recording();
        Compilation compilation = compileAgainst(classes, processor,
                binding("OrderModels", "@BindQueryModel(model = OrderView.class, root = OrderEntity.class)"));

        assertThat(errors(compilation)).containsExactly("MQ3023: OrderView: carries @QueryModel and is bound by "
                + "@BindQueryModel on persistence.OrderModels; keep one");
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderView");
    }

    @Test
    void ac_proc_23_annotated_model_in_the_compilation_bound_is_mq3023_and_keeps_its_own_qmodel() {
        var processor = new ProcessorHarness.Recording();
        Compilation compilation = processor.compile(ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                ShopSources.ORDER_VIEW,
                binding("OrderModels", "@BindQueryModel(model = OrderView.class, root = OrderEntity.class)"));

        assertThat(errors(compilation)).containsExactly("MQ3023: OrderView: carries @QueryModel and is bound by "
                + "@BindQueryModel on persistence.OrderModels; keep one");
        assertThat(processor.originating()).containsOnlyKeys("shop.QOrderView");
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderView");
    }

    @Test
    void ac_proc_24_model_not_public_is_mq3024() {
        compileToClasspath(classes, OUTER);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                subclassBinding("Outer.OrderRow"));

        assertThat(errors(compilation)).containsExactly(
                "MQ3024: OrderRow is not public, but QOrderRow is generated in persistence; make it public");
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderRow");
    }

    @Test
    void ac_proc_24_model_in_a_type_that_is_not_public_is_mq3024() {
        compileToClasspath(classes, OUTER);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                subclassBinding("Outer.Inner.OrderRow"));

        assertThat(errors(compilation)).containsExactly("MQ3024: OrderRow: its enclosing Inner is not public, but "
                + "QOrderRow is generated in persistence; make it public");
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderRow");
    }

    @Test
    void ac_proc_24_model_bound_in_its_own_package_needs_no_public_access() {
        compileToClasspath(classes, orderRow("public class OrderRow", "class OrderRow", "public OrderRow() {}",
                "OrderRow() {}", "public void set", "void set"));

        Compilation compilation = compileAgainst(classes, new ModelQueryProcessor(), ShopSources.ADDRESS,
                ShopSources.ORDER_ENTITY, source("shop.OrderModels", """
                        package shop;

                        import com.rey.modelquery.annotations.BindQueryModel;

                        @BindQueryModel(model = OrderRow.class, root = OrderEntity.class)
                        final class OrderModels {}
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QOrderRow")).contains("public final class QOrderRow");
    }

    @Test
    void ac_proc_24_no_arg_constructor_not_public_is_mq3024() {
        compileToClasspath(classes, orderRow("public OrderRow() {}", "protected OrderRow() {}"));

        var processor = new ProcessorHarness.Recording();
        Compilation compilation = bindOrderRow(processor);

        assertThat(errors(compilation)).containsExactly(
                "MQ3024: OrderRow: the no-arg constructor is not public" + NOT_PUBLIC);
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderRow");
    }

    @Test
    void ac_proc_24_setter_not_public_is_mq3024() {
        compileToClasspath(classes, orderRow("public void setStatus", "void setStatus"));

        var processor = new ProcessorHarness.Recording();
        Compilation compilation = bindOrderRow(processor);

        assertThat(errors(compilation)).containsExactly("MQ3024: OrderRow.setStatus is not public" + NOT_PUBLIC);
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderRow");
    }

    @Test
    void ac_proc_24_join_getter_not_public_is_mq3024() {
        compileToClasspath(classes, source("shop.CustomerEntity", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                public class CustomerEntity {
                    @Id
                    Long id;
                    String name;
                }
                """), source("shop.SaleEntity", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;

                @Entity
                public class SaleEntity {
                    @Id
                    Long id;
                    @ManyToOne
                    CustomerEntity customer;
                }
                """), source("shop.CustomerRef", """
                package shop;

                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = CustomerEntity.class)
                public record CustomerRef(@PrimaryKey Long id, String name) {}
                """), source("shop.SaleRow", """
                package shop;

                import com.rey.modelquery.annotations.Join;
                import com.rey.modelquery.annotations.PrimaryKey;
                import java.util.Optional;

                public class SaleRow {
                    @PrimaryKey
                    private Long id;
                    @Join(attribute = "customer")
                    private Optional<CustomerRef> customer;

                    public void setId(Long id) {
                        this.id = id;
                    }

                    public void setCustomer(Optional<CustomerRef> customer) {
                        this.customer = customer;
                    }

                    Optional<CustomerRef> getCustomer() {
                        return customer;
                    }
                }
                """));

        var processor = new ProcessorHarness.Recording();
        Compilation compilation = compileAgainst(classes, processor,
                binding("SaleModels", "@BindQueryModel(model = SaleRow.class, root = SaleEntity.class)"));

        assertThat(errors(compilation)).containsExactly("MQ3024: SaleRow.getCustomer is not public, but QSaleRow is "
                + "generated in persistence and calls it; make it public");
        assertThat(processor.originating()).doesNotContainKey("persistence.QSaleRow");
    }

    @Test
    void ac_proc_24_model_that_is_no_class_or_record_is_mq3024() {
        Compilation compilation = compile(ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                binding("OrderModels", "@BindQueryModel(model = Runnable.class, root = OrderEntity.class)"));

        assertThat(errors(compilation)).containsExactly(
                "MQ3024: java.lang.Runnable: a bound model must be a public class or record");
    }

    @Test
    void ac_proc_24_converter_not_public_in_another_package_is_mq3014() {
        compileToClasspath(classes, source("shop.Hidden", """
                package shop;

                import com.rey.modelquery.core.ColumnConverter;

                class Hidden implements ColumnConverter<Integer, String> {
                    public static final Hidden INSTANCE = new Hidden();

                    @Override
                    public Integer toModel(String attribute) {
                        return Integer.valueOf(attribute);
                    }

                    @Override
                    public String toAttribute(Integer model) {
                        return model.toString();
                    }
                }
                """), source("shop.OrderRow", """
                package shop;

                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.PrimaryKey;

                public record OrderRow(@PrimaryKey Long id, @Column(attribute = "status", converter = Hidden.class)
                        Integer status) {}
                """));

        var processor = new ProcessorHarness.Recording();
        Compilation compilation = bindOrderRow(processor);

        assertThat(errors(compilation)).containsExactly("MQ3014: OrderRow.status: Hidden is not visible to QOrderRow, "
                + "which is generated in persistence; make it public");
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderRow");
    }

    @Test
    void ac_proc_24_expression_definition_not_public_in_another_package_is_mq3018() {
        compileToClasspath(classes, source("shop.HiddenDef", """
                package shop;

                import com.rey.modelquery.core.ExpressionDefinition;
                import com.rey.modelquery.core.ExpressionField;
                import java.math.BigDecimal;

                class HiddenDef implements ExpressionDefinition<OrderRow, BigDecimal> {
                    public static final HiddenDef INSTANCE = new HiddenDef();

                    @Override
                    public ExpressionField<OrderRow, BigDecimal> expression() {
                        throw new UnsupportedOperationException();
                    }
                }
                """), source("shop.OrderRow", """
                package shop;

                import com.rey.modelquery.annotations.Computed;
                import com.rey.modelquery.annotations.PrimaryKey;
                import java.math.BigDecimal;

                public record OrderRow(@PrimaryKey Long id, @Computed(HiddenDef.class) BigDecimal doubled) {}
                """));

        var processor = new ProcessorHarness.Recording();
        Compilation compilation = bindOrderRow(processor);

        assertThat(errors(compilation)).containsExactly("MQ3018: OrderRow.doubled: HiddenDef is not visible to "
                + "QOrderRow, which is generated in persistence; make it public");
        assertThat(processor.originating()).doesNotContainKey("persistence.QOrderRow");
    }

    private Compilation bindOrderRow(ProcessorHarness.Recording processor) {
        return compileAgainst(classes, processor, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                binding("OrderModels", "@BindQueryModel(model = OrderRow.class, root = OrderEntity.class)"));
    }

    /** A binding of {@code model} on a type nested in a subclass of {@code Outer}, whose body may name it. */
    private static JavaFileObject subclassBinding(String model) {
        return source("persistence.OrderModels", """
                package persistence;

                import com.rey.modelquery.annotations.BindQueryModel;
                import shop.*;

                final class OrderModels extends Outer {
                    @BindQueryModel(model = %s.class, root = OrderEntity.class)
                    static final class Binding {}
                }
                """.formatted(model));
    }

    /** {@link #ORDER_ROW} with each text of {@code fromTo} at an even index replaced by the one after it. */
    private static JavaFileObject orderRow(String... fromTo) {
        String text = ORDER_ROW;
        for (int i = 0; i < fromTo.length; i += 2) {
            assertThat(text).contains(fromTo[i]);
            text = text.replace(fromTo[i], fromTo[i + 1]);
        }
        return source("shop.OrderRow", text);
    }

    /** A type {@code persistence.<name>} carrying {@code annotations}, which see every type of {@code shop}. */
    private static JavaFileObject binding(String name, String annotations) {
        return source("persistence." + name, """
                package persistence;

                import com.rey.modelquery.annotations.BindQueryModel;
                import shop.*;

                %sfinal class %s {}
                """.formatted(annotations.endsWith("\n") ? annotations : annotations + "\n", name));
    }

    /** {@code model} with its {@code @QueryModel} and the import of it removed. */
    private static JavaFileObject unannotated(JavaFileObject model) {
        try {
            String text = model.getCharContent(false).toString();
            String bare = text.replace("import com.rey.modelquery.annotations.QueryModel;\n", "")
                    .replaceAll("@QueryModel\\([^)]*\\)\n", "");
            assertThat(bare).doesNotContain("QueryModel");
            return source("shop.OrderView", bare);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
