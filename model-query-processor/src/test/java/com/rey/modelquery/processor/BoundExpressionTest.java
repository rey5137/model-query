package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.compileAgainst;
import static com.rey.modelquery.processor.ProcessorHarness.compileToClasspath;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A valueless {@code @Computed} and an expression {@code @Aggregate} of a bound model, whose definitions the binding
 * names with {@code @BindExpression}, since the model's module cannot compile them (R-PROC-29).
 */
class BoundExpressionTest {

    private static final JavaFileObject ORDER_ENTITY = source("shop.OrderEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                String status;
                BigDecimal amount;
            }
            """);

    /** A group key and an aggregate whose definitions the model's module cannot name. */
    private static final String ORDER_BAND = """
            package shop;

            import com.rey.modelquery.annotations.*;
            import java.math.BigDecimal;

            public record OrderBand(
                    @GroupBy @Computed String band,
                    @Aggregate(fn = AggregateFunction.SUM) BigDecimal doubled,
                    @Aggregate(fn = AggregateFunction.COUNT) Long id) {}
            """;

    private static final String BIND =
            "@BindQueryModel(model = OrderBand.class, root = OrderEntity.class, expressions = {%s})";

    private static final String BOTH = "@BindExpression(field = \"band\", value = Band.class), "
            + "@BindExpression(field = \"doubled\", value = BandDoubled.class)";

    @TempDir
    Path classes;

    private final ProcessorHarness.Recording processor = new ProcessorHarness.Recording();

    @Test
    void ac_proc_25_bound_computed_and_expression_aggregate_generate_what_the_annotated_forms_do() {
        Compilation expected = compile(ORDER_ENTITY, band("shop"), bandDoubled("shop"), source("shop.OrderBand",
                ORDER_BAND.replace("public record", "@QueryModel(root = OrderEntity.class)\n"
                                + "public record")
                        .replace("@Computed String", "@Computed(Band.class) String")
                        .replace("SUM)", "SUM, expression = BandDoubled.class)")));
        assertThat(expected).succeededWithoutWarnings();
        compileToClasspath(classes, source("shop.OrderBand", ORDER_BAND));

        Compilation compilation = compileAgainst(classes, processor, ORDER_ENTITY, band("persistence"),
                bandDoubled("persistence"), binding(BIND.formatted(BOTH)));

        assertThat(compilation).succeededWithoutWarnings();
        String bound = generated(compilation, "persistence.QOrderBand");
        assertThat(bound).contains("Agg.sum(BandDoubled.INSTANCE.expression())");
        assertThat(bound.replace("package persistence;", "package shop;")
                .replaceAll("(?m)^import shop\\.[\\w.]+;\\n", ""))
                .isEqualTo(generated(expected, "shop.QOrderBand"));
        assertThat(processor.originating()).containsOnlyKeys("persistence.QOrderBand");
    }

    @Test
    void ac_proc_25_valueless_computed_with_no_bind_expression_is_mq3025() {
        assertErrors(BIND.formatted("@BindExpression(field = \"doubled\", value = BandDoubled.class)"),
                "MQ3025: OrderBand.band: @Computed names no ExpressionDefinition, and no @BindExpression on "
                        + "persistence.Models names one; add @BindExpression(field = \"band\", value = Def.class)");
    }

    @Test
    void ac_proc_25_valueless_computed_on_an_annotated_model_is_mq3025() {
        Compilation compilation = processor.compile(ORDER_ENTITY, source("shop.OrderBand", ORDER_BAND
                .replace("public record", "@QueryModel(root = OrderEntity.class)\npublic record")
                .replace("SUM)", "SUM, attribute = \"amount\")")));

        assertThat(errors(compilation)).containsExactly("MQ3025: OrderBand.band: @Computed names no "
                + "ExpressionDefinition; name one, as @Computed(Def.class)");
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_25_valueless_computed_on_an_update_model_keeps_its_mq3302() {
        Compilation compilation = processor.compile(ORDER_ENTITY, source("shop.OrderFix", """
                package shop;

                import com.rey.modelquery.annotations.*;
                import java.math.BigDecimal;

                @UpdateModel(root = OrderEntity.class)
                public record OrderFix(@PrimaryKey Long id, @Computed BigDecimal amount) {}
                """));

        assertThat(errors(compilation)).containsExactly("MQ3302: OrderFix.amount: @Computed isn't allowed on "
                + "@UpdateModel; an update writes columns, not expressions");
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_25_bind_expression_naming_an_unknown_field_is_mq3025() {
        assertErrors(BIND.formatted(BOTH + ", @BindExpression(field = \"net\", value = Band.class)"),
                "MQ3025: OrderBand.net: @BindExpression names a field OrderBand does not declare");
    }

    @Test
    void ac_proc_25_bind_expression_on_a_field_neither_computed_nor_aggregate_is_mq3025() {
        compileToClasspath(classes, source("shop.OrderBand", ORDER_BAND.replace(
                "@Aggregate(fn = AggregateFunction.COUNT) Long id", "@Transient String label")));

        Compilation compilation =
                bind(BIND.formatted(BOTH + ", @BindExpression(field = \"label\", value = Band.class)"));

        assertThat(errors(compilation)).containsExactly(
                "MQ3025: OrderBand.label: @BindExpression names a field that is neither @Computed nor @Aggregate");
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_25_bind_expression_on_a_computed_that_names_a_definition_is_mq3025() {
        compileToClasspath(classes, ORDER_ENTITY, band("shop"),
                source("shop.OrderBand", ORDER_BAND.replace("@Computed String", "@Computed(Band.class) String")));

        Compilation compilation = bind(BIND.formatted(BOTH.replace("Band.class)", "BandDoubled.class)")));

        assertThat(errors(compilation)).containsExactly(
                "MQ3025: OrderBand.band: @Computed already names shop.Band; keep one");
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_25_bind_expression_on_an_aggregate_that_names_an_expression_is_mq3025() {
        compileToClasspath(classes, ORDER_ENTITY, bandDoubled("shop"), source("shop.OrderBand",
                ORDER_BAND.replace("SUM)", "SUM, expression = BandDoubled.class)")));

        Compilation compilation = bind(BIND.formatted(BOTH));

        assertThat(errors(compilation)).containsExactly(
                "MQ3025: OrderBand.doubled: @Aggregate already names shop.BandDoubled as its expression; keep one");
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_25_bind_expression_naming_a_field_twice_is_mq3025() {
        assertErrors(BIND.formatted(BOTH + ", @BindExpression(field = \"band\", value = Band.class)"),
                "MQ3025: OrderBand.band: @BindExpression names it more than once; keep one");
    }

    /** That binding {@code ORDER_BAND} with {@code annotation} reports exactly {@code error} and generates nothing. */
    private void assertErrors(String annotation, String error) {
        compileToClasspath(classes, source("shop.OrderBand", ORDER_BAND));

        Compilation compilation = bind(annotation);

        assertThat(errors(compilation)).containsExactly(error);
        assertThat(processor.originating()).isEmpty();
    }

    private Compilation bind(String annotation) {
        return compileAgainst(classes, processor, ORDER_ENTITY, band("persistence"),
                bandDoubled("persistence"), binding(annotation));
    }

    /** {@code persistence.Models} carrying {@code annotation}. */
    private static JavaFileObject binding(String annotation) {
        return source("persistence.Models", """
                package persistence;

                import com.rey.modelquery.annotations.*;
                import shop.*;

                %s
                final class Models {}
                """.formatted(annotation));
    }

    /** {@code OrderEntity.status}, coalesced to a non-null key, typed to {@code OrderBand}, in {@code pkg}. */
    private static JavaFileObject band(String pkg) {
        return definition(pkg, "Band", "String", "Expr.coalesce(ColumnField.of(OrderBand.class, "
                + "TableField.root(OrderEntity.class), \"status\", String.class), \"none\")");
    }

    /** {@code OrderEntity.amount * 2}, typed to {@code OrderBand}, in {@code pkg}. */
    private static JavaFileObject bandDoubled(String pkg) {
        return definition(pkg, "BandDoubled", "BigDecimal", "Expr.times(ColumnField.of(OrderBand.class, "
                + "TableField.root(OrderEntity.class), \"amount\", BigDecimal.class), BigDecimal.valueOf(2))");
    }

    private static JavaFileObject definition(String pkg, String name, String type, String expression) {
        return source(pkg + "." + name, """
                package %s;

                import com.rey.modelquery.core.ColumnField;
                import com.rey.modelquery.core.Expr;
                import com.rey.modelquery.core.ExpressionDefinition;
                import com.rey.modelquery.core.ExpressionField;
                import com.rey.modelquery.core.TableField;
                import java.math.BigDecimal;
                import shop.*;

                public final class %s implements ExpressionDefinition<OrderBand, %s> {
                    public static final %s INSTANCE = new %s();

                    @Override
                    public ExpressionField<OrderBand, %s> expression() {
                        return %s;
                    }
                }
                """.formatted(pkg, name, type, name, name, type, expression));
    }
}
