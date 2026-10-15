package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.compileAgainst;
import static com.rey.modelquery.processor.ProcessorHarness.compileToClasspath;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code @Window} on a {@code @SourceModel}: {@code processor/30} R-PROC-36, {@code processor/31} R-GEN-38,
 * {@code api/17} R-SRC-04.
 */
class WindowModelTest {

    @TempDir
    Path classpath;

    private static final JavaFileObject ORDER_ENTITY = source("shop.OrderEntity", """
            package shop;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;
            import java.time.Instant;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                @Column(nullable = false)
                String customer;
                String status;
                BigDecimal total;
                int quantity;
                Instant createdAt;
            }
            """);

    /** An ungrouped source: a ranking window over the row's own attributes and a framed aggregate window. */
    private static final JavaFileObject RANKED_ORDER = source("shop.RankedOrder", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;
            import com.rey.modelquery.annotations.Window;
            import com.rey.modelquery.annotations.WindowFrame;
            import com.rey.modelquery.annotations.WindowFunction;
            import java.math.BigDecimal;

            @SourceModel(root = OrderEntity.class)
            public record RankedOrder(
                    @PrimaryKey Long id,
                    String customer,
                    @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "customer",
                            orderBy = "createdAt desc") Long rank,
                    @Window(function = WindowFunction.SUM, argument = "total", orderBy = "createdAt",
                            frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal running) {}
            """);

    /** A grouped source: the window reads a group key as its order term and an @Aggregate field as its argument. */
    private static final JavaFileObject CUSTOMER_TOTALS = source("shop.CustomerTotals", """
            package shop;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;
            import com.rey.modelquery.annotations.Window;
            import com.rey.modelquery.annotations.WindowFrame;
            import com.rey.modelquery.annotations.WindowFunction;
            import java.math.BigDecimal;

            @SourceModel(root = OrderEntity.class)
            public record CustomerTotals(
                    @PrimaryKey @GroupBy String customer,
                    @Aggregate(fn = AggregateFunction.SUM, attribute = "total") BigDecimal total,
                    @Aggregate(fn = AggregateFunction.COUNT) Long orders,
                    @Window(function = WindowFunction.RANK, orderBy = "total desc") Long placing,
                    @Window(function = WindowFunction.SUM, argument = "total", orderBy = "customer",
                            frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING) BigDecimal grand) {}
            """);

    /** An outer model over the source: a window field is a plain column there. */
    private static final JavaFileObject RANKED_ROW = source("shop.RankedRow", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.math.BigDecimal;

            @QueryModel(root = RankedOrder.class)
            public record RankedRow(@PrimaryKey Long id, String customer, Long rank, BigDecimal running) {}
            """);

    private static Compilation compileShop() {
        return compile(ORDER_ENTITY, RANKED_ORDER, CUSTOMER_TOTALS, RANKED_ROW);
    }

    /** A source model named {@code name} whose only window is {@code window}, over {@link #ORDER_ENTITY}. */
    private static Compilation compileWindow(String name, String window) {
        return compile(ORDER_ENTITY, source("shop." + name, """
                package shop;

                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import com.rey.modelquery.annotations.Window;
                import com.rey.modelquery.annotations.WindowFrame;
                import com.rey.modelquery.annotations.WindowFunction;
                import java.math.BigDecimal;

                @SourceModel(root = OrderEntity.class)
                public record %s(@PrimaryKey Long id, %s) {}
                """.formatted(name, window)));
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }

    @Test
    void ac_gen_38_an_ungrouped_source_with_windows_matches_its_golden_file() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QRankedOrder")).isEqualTo(resource("golden/QRankedOrder.java"));
    }

    @Test
    void ac_gen_38_a_grouped_source_with_windows_matches_its_golden_file() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QCustomerTotals")).isEqualTo(resource("golden/QCustomerTotals.java"));
    }

    @Test
    void ac_gen_38_an_outer_model_reads_a_window_field_as_a_plain_column() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QRankedRow")).isEqualTo(resource("golden/QRankedRow.java"));
    }

    @Test
    void ac_gen_38_a_window_constant_is_in_all_and_is_aliased_and_indexed_by_its_field_name() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QRankedOrder")).contains(
                "WindowField.of(RankedOrder.class, WindowFunction.ROW_NUMBER, Long.class, null, List.of(CUSTOMER)",
                "SelectSet.of(ID, CUSTOMER, RANK, RUNNING)",
                ".select(ID, CUSTOMER, RANK, RUNNING)");
    }

    @Test
    void ac_gen_38_a_window_constant_is_not_a_filter_term() {
        compileToClasspath(classpath, ORDER_ENTITY, RANKED_ORDER);

        Compilation compilation = compileAgainst(classpath, new ProcessorHarness.Recording(), source("shop.UseRanked",
                """
                package shop;

                public final class UseRanked {
                    static Object query() {
                        return QRankedOrder.source().where(f -> f.eq(QRankedOrder.RANK, 1L));
                    }
                }
                """));

        // R-GEN-38: a window field is no ScalarField, so javac refuses the predicate; the processor reports nothing.
        assertThat(compilation).failed();
        assertThat(errors(compilation)).isNotEmpty()
                .anySatisfy(error -> assertThat(error).contains("WindowField"))
                .allSatisfy(error -> assertThat(error).doesNotContain("MQ3"));
    }

    @Test
    void ac_proc_32_a_window_term_that_names_no_attribute_is_mq3032() {
        Compilation compilation = compileWindow("BadTerm", """
                @Window(function = WindowFunction.ROW_NUMBER, orderBy = "nope") Long rank""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "BadTerm.rank: orderBy 'nope': no attribute 'nope' on OrderEntity"));
    }

    @Test
    void ac_proc_32_a_ranking_window_with_an_argument_is_mq3032() {
        Compilation compilation = compileWindow("ExtraArgument", """
                @Window(function = WindowFunction.ROW_NUMBER, argument = "total",
                        orderBy = "createdAt") Long rank""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "ExtraArgument.rank: ROW_NUMBER takes no argument; drop argument"));
    }

    @Test
    void ac_proc_32_an_aggregate_window_without_an_argument_is_mq3032() {
        Compilation compilation = compileWindow("MissingArgument", """
                @Window(function = WindowFunction.SUM) BigDecimal total""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "MissingArgument.total: SUM needs an argument; write @Window(argument = \"...\")"));
    }

    @Test
    void ac_proc_32_a_field_whose_type_is_not_the_result_type_is_mq3032() {
        Compilation compilation = compileWindow("WrongType", """
                @Window(function = WindowFunction.ROW_NUMBER, orderBy = "createdAt") Integer rank""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "WrongType.rank: ROW_NUMBER returns Long, field is Integer"));
    }

    @Test
    void ac_proc_32_an_order_sensitive_window_without_order_by_is_mq3032() {
        Compilation compilation = compileWindow("Unordered", """
                @Window(function = WindowFunction.LAG, argument = "total") BigDecimal previous""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "Unordered.previous: LAG is order-sensitive; add orderBy"));
    }

    @Test
    void ac_proc_32_a_rows_framed_aggregate_without_order_by_is_mq3032() {
        Compilation compilation = compileWindow("UnorderedRows", """
                @Window(function = WindowFunction.SUM, argument = "total",
                        frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal running""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "UnorderedRows.running: SUM is order-sensitive; add orderBy"));
    }

    @Test
    void ac_proc_32_a_first_value_window_without_a_frame_is_mq3032() {
        Compilation compilation = compileWindow("Frameless", """
                @Window(function = WindowFunction.FIRST_VALUE, argument = "total",
                        orderBy = "createdAt") BigDecimal first""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "Frameless.first: FIRST_VALUE needs an explicit frame; with the database's default frame"
                        + " the window is unspecified"));
    }

    @Test
    void ac_proc_32_a_last_value_window_with_a_range_frame_is_mq3032() {
        Compilation compilation = compileWindow("RangedValue", """
                @Window(function = WindowFunction.LAST_VALUE, argument = "total", orderBy = "createdAt",
                        frame = WindowFrame.RANGE_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal last""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "RangedValue.last: LAST_VALUE takes only a ROWS frame; a RANGE frame would make every row its own"
                        + " peer group"));
    }

    @Test
    void ac_proc_32_a_lead_window_with_a_frame_is_mq3032() {
        Compilation compilation = compileWindow("FramedLead", """
                @Window(function = WindowFunction.LEAD, argument = "total", orderBy = "createdAt",
                        frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal next""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "FramedLead.next: LEAD takes no frame; drop frame"));
    }

    @Test
    void ac_proc_32_a_term_that_is_neither_a_group_key_nor_an_aggregate_is_mq3032() {
        Compilation compilation = compile(ORDER_ENTITY, source("shop.BadTotals", """
                package shop;

                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import com.rey.modelquery.annotations.Window;
                import com.rey.modelquery.annotations.WindowFunction;

                @SourceModel(root = OrderEntity.class)
                public record BadTotals(
                        @PrimaryKey @GroupBy String customer,
                        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "status",
                                orderBy = "customer") Long rank) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3032,
                "BadTotals.rank: partitionBy 'status' is neither a group key nor an @Aggregate of this grouped"
                        + " source; name one of GROUP_KEYS or an aggregate field"));
    }

    @Test
    void ac_proc_33_a_window_field_on_a_query_model_that_is_no_source_model_is_mq3033() {
        Compilation compilation = compile(ORDER_ENTITY, source("shop.WindowView", """
                package shop;

                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import com.rey.modelquery.annotations.Window;
                import com.rey.modelquery.annotations.WindowFunction;

                @QueryModel(root = OrderEntity.class)
                public record WindowView(@PrimaryKey Long id,
                        @Window(function = WindowFunction.ROW_NUMBER, orderBy = "createdAt") Long rank) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3033,
                "WindowView.rank: @Window is only allowed on a @SourceModel"));
    }
}
