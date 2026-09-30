package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** {@code @Aggregate}, {@code @GroupBy} and {@code singleGroup} ({@code processor/30} §6), and their checks. */
class AggregateModelTest {

    private static final JavaFileObject SALE_ENTITY = source("shop.SaleEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;
            import java.time.LocalDate;

            @Entity
            public class SaleEntity {
                @Id
                Long id;
                String region;
                String product;
                BigDecimal amount;
                Integer units;
                Double weight;
                LocalDate placedOn;
            }
            """);

    private static final String IMPORTS = """
            package shop;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.math.BigDecimal;
            import java.time.LocalDate;

            """;

    private static JavaFileObject summary(String declaration) {
        return source("shop.SalesSummary", IMPORTS + declaration);
    }

    private static Compilation compileSummary(String declaration) {
        return compile(SALE_ENTITY, summary(declaration));
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }

    @Test
    void ac_gen_01_summary_model_matches_its_golden_file() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class)
                public record SalesSummary(
                        @GroupBy String region,
                        @GroupBy String product,
                        @Aggregate(fn = AggregateFunction.COUNT) Long lines,
                        @Aggregate(fn = AggregateFunction.COUNT, attribute = "product", distinct = true)
                                Long products,
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "amount") BigDecimal revenue,
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "units") Long units,
                        @Aggregate(fn = AggregateFunction.AVG, attribute = "weight") Double averageWeight,
                        @Aggregate(fn = AggregateFunction.MIN, attribute = "placedOn") LocalDate firstSale,
                        @Aggregate(fn = AggregateFunction.MAX, attribute = "amount") BigDecimal biggestSale) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QSalesSummary")).isEqualTo(resource("golden/QSalesSummary.java"));
    }

    @Test
    void ac_proc_09_group_keys_and_the_grouped_query_leave_aggregates_out_of_every_column_set() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class)
                public class SalesSummary {
                    @GroupBy
                    private String region;
                    @Aggregate(fn = AggregateFunction.COUNT)
                    private Long lines;
                    public void setRegion(String region) {}
                    public void setLines(Long lines) {}
                }
                """);

        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QSalesSummary");
        assertThat(generated)
                .contains("ColumnSet<SalesSummary> GROUP_KEYS = ColumnSet.of(REGION);")
                .contains("ColumnSet<SalesSummary> ALL = ColumnSet.of(REGION);")
                .contains("AggregateField<SalesSummary, Long> LINES = Agg.count(ROOT);")
                .contains("Builder<SaleEntity, Object, SalesSummary> query() { "
                        + "return ModelQuery.builder(ROOT, MAPPER).groupBy(GROUP_KEYS); }")
                .contains("if (row.isSelected(LINES)) { m.setLines(row.get(LINES)); }")
                .doesNotContain("KEY =");
    }

    @Test
    void ac_proc_09_a_summary_model_may_keep_a_primary_key() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class)
                public record SalesSummary(
                        @PrimaryKey @GroupBy Long id,
                        @Aggregate(fn = AggregateFunction.COUNT) Long lines) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QSalesSummary"))
                .contains("return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY).groupBy(GROUP_KEYS);");
    }

    @Test
    void ac_proc_09_a_count_over_an_attribute_counts_its_non_null_values() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class, singleGroup = true)
                public record SalesSummary(
                        @Aggregate(fn = AggregateFunction.COUNT, attribute = "amount") Long priced) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QSalesSummary")).contains("Agg.of(\"PRICED\", Long.class, "
                + "(ctx, cb) -> cb.count(ColumnField.of(SalesSummary.class, ROOT, \"amount\", "
                + "BigDecimal.class).path(ctx)));");
    }

    private static final String TOTAL = """
            @QueryModel(root = SaleEntity.class, singleGroup = %s)
            public record SalesSummary(@Aggregate(fn = AggregateFunction.COUNT) Long lines) {}
            """;

    @Test
    void ac_proc_10_single_group_suppresses_the_missing_group_by_error() {
        Compilation compilation = compileSummary(TOTAL.formatted("true"));

        assertThat(compilation).succeededWithoutWarnings();
        // A whole-table total has neither a groupBy nor a primaryKey (R-GEN-18).
        String generated = generatedFlat(compilation, "shop.QSalesSummary");
        assertThat(generated)
                .contains("return ModelQuery.builder(ROOT, MAPPER); }")
                .doesNotContain("groupBy", "primaryKey", "GROUP_KEYS", "KEY =");
    }

    @Test
    void ac_proc_10_omitting_single_group_raises_the_missing_group_by_error() {
        Compilation compilation = compileSummary(TOTAL.formatted("false"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3203,
                "SalesSummary: has @Aggregate fields but no @GroupBy; add one or set @QueryModel(singleGroup = true)"));
    }

    @Test
    void ac_diag_01_a_primitive_aggregate_is_mq3201() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class, singleGroup = true)
                public record SalesSummary(
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "weight") double weight) {}
                """);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3201,
                "SalesSummary.weight: SUM is NULL over zero rows; use Double, not a primitive"));
    }

    @Test
    void ac_diag_01_an_aggregate_of_the_wrong_type_is_mq3202() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class, singleGroup = true)
                public record SalesSummary(
                        @Aggregate(fn = AggregateFunction.COUNT) Integer lines,
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "amount") Double revenue,
                        @Aggregate(fn = AggregateFunction.AVG, attribute = "amount") BigDecimal average,
                        @Aggregate(fn = AggregateFunction.MAX, attribute = "placedOn") String latest) {}
                """);

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message(DiagnosticCode.MQ3202, "SalesSummary.lines: COUNT returns Long, field is Integer"),
                message(DiagnosticCode.MQ3202,
                        "SalesSummary.revenue: SUM over BigDecimal returns BigDecimal, field is Double"),
                message(DiagnosticCode.MQ3202,
                        "SalesSummary.average: AVG over BigDecimal returns Double, field is BigDecimal"),
                message(DiagnosticCode.MQ3202,
                        "SalesSummary.latest: MAX over LocalDate returns LocalDate, field is String"));
    }

    @Test
    void ac_diag_01_group_by_on_an_aggregate_or_a_join_is_mq3204() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class)
                public record SalesSummary(
                        @GroupBy String region,
                        @GroupBy @Aggregate(fn = AggregateFunction.COUNT) Long lines) {}
                """);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3204,
                "SalesSummary.lines: @GroupBy can't be combined with @Aggregate"));
    }

    @Test
    void ac_diag_01_mq3009_a_primitive_key_on_a_grouped_record_is_rejected() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class)
                public record SalesSummary(
                        @PrimaryKey long id,
                        @GroupBy String region,
                        @Aggregate(fn = AggregateFunction.COUNT) Long lines) {}
                """);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3009,
                "SalesSummary.id: primitive components can't be null when not selected; use Long"));
    }

    @Test
    void ac_diag_01_group_by_on_a_join_is_mq3204() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class)
                public record SalesSummary(
                        @PrimaryKey Long id,
                        @GroupBy @com.rey.modelquery.annotations.Join
                                java.util.Optional<SalesSummary> parent) {}
                """);

        assertThat(errors(compilation)).contains(message(DiagnosticCode.MQ3204,
                "SalesSummary.parent: @GroupBy can't be combined with @Join"));
    }

    @Test
    void ac_diag_01_a_32_bit_sum_declared_as_anything_but_long_is_mq3205() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class, singleGroup = true)
                public record SalesSummary(
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "units") Integer units) {}
                """);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3205,
                "SalesSummary.units: SUM over Integer returns Long; declare the field as Long"));
    }

    @Test
    void ac_diag_02_every_independent_aggregate_problem_is_reported_in_one_pass() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class)
                public record SalesSummary(
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "units") Integer units,
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "amont") BigDecimal revenue,
                        @Aggregate(fn = AggregateFunction.COUNT) long lines) {}
                """);

        assertThat(errors(compilation)).hasSize(4).anyMatch(text -> text.startsWith("MQ3203"))
                .anyMatch(text -> text.startsWith("MQ3205"))
                .anyMatch(text -> text.startsWith("MQ3001"))
                .anyMatch(text -> text.startsWith("MQ3201"));
    }

    private static final JavaFileObject CUSTOMER_ENTITY = source("shop.CustomerEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
            }
            """);

    private static final JavaFileObject ORDER_ENTITY = source("shop.OrderEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                @ManyToOne
                CustomerEntity customer;
            }
            """);

    private static final JavaFileObject CUSTOMER_TOTALS = source("shop.CustomerTotals", IMPORTS + """
            @QueryModel(root = CustomerEntity.class, singleGroup = true)
            public record CustomerTotals(
                    @PrimaryKey Long id,
                    @Aggregate(fn = AggregateFunction.COUNT) Long lines) {}
            """);

    private static Compilation compileCombination(String field) {
        return compileSummary("""
                @QueryModel(root = SaleEntity.class, singleGroup = true)
                public record SalesSummary(
                        %s) {}
                """.formatted(field));
    }

    @Test
    void ac_diag_01_distinct_on_anything_but_count_is_mq3206() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class, singleGroup = true)
                public record SalesSummary(
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "amount", distinct = true)
                                BigDecimal revenue) {}
                """);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3206,
                "SalesSummary.revenue: distinct only applies to COUNT, found SUM"));
    }

    @Test
    void ac_diag_01_single_group_with_a_group_by_field_is_mq3207() {
        Compilation compilation = compileSummary("""
                @QueryModel(root = SaleEntity.class, singleGroup = true)
                public record SalesSummary(
                        @GroupBy String region,
                        @Aggregate(fn = AggregateFunction.COUNT) Long lines) {}
                """);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3207,
                "SalesSummary: singleGroup = true can't be combined with @GroupBy fields; remove one"));
    }

    @Test
    void ac_diag_01_joining_a_summary_model_is_mq3005() {
        JavaFileObject view = source("shop.OrderView", IMPORTS + """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(
                        @PrimaryKey Long id,
                        @com.rey.modelquery.annotations.Join
                                java.util.Optional<CustomerTotals> customer) {}
                """);

        Compilation compilation = compile(CUSTOMER_ENTITY, ORDER_ENTITY, CUSTOMER_TOTALS, view);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3005,
                "OrderView.customer: @Join model CustomerTotals has @Aggregate fields; a summary model can't be joined"));
    }

    @Test
    void ac_diag_01_an_aggregate_beside_a_primary_key_is_mq3204() {
        Compilation compilation = compileCombination(
                "@PrimaryKey @Aggregate(fn = AggregateFunction.COUNT) Long lines");

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3204,
                "SalesSummary.lines: @Aggregate can't be combined with @PrimaryKey"));
    }

    @Test
    void ac_diag_01_an_aggregate_beside_a_column_is_mq3204() {
        Compilation compilation = compileCombination(
                "@com.rey.modelquery.annotations.Column @Aggregate(fn = AggregateFunction.COUNT) Long lines");

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3204,
                "SalesSummary.lines: @Aggregate can't be combined with @Column"));
    }

    @Test
    void ac_diag_01_an_aggregate_beside_a_transient_is_mq3204() {
        Compilation compilation = compileCombination(
                "@com.rey.modelquery.annotations.Transient @Aggregate(fn = AggregateFunction.COUNT) Long lines");

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3204,
                "SalesSummary.lines: @Aggregate can't be combined with @Transient"));
    }

    @Test
    void ac_diag_01_an_aggregate_beside_a_join_is_mq3204() {
        JavaFileObject view = source("shop.OrderView", IMPORTS + """
                @QueryModel(root = OrderEntity.class, singleGroup = true)
                public record OrderView(
                        @com.rey.modelquery.annotations.Join(prefix = "buyer")
                                @Aggregate(fn = AggregateFunction.COUNT)
                                java.util.Optional<CustomerRef> customer) {}
                """);
        JavaFileObject ref = source("shop.CustomerRef", IMPORTS + """
                @QueryModel(root = CustomerEntity.class)
                public record CustomerRef(@PrimaryKey Long id) {}
                """);

        Compilation compilation = compile(CUSTOMER_ENTITY, ORDER_ENTITY, ref, view);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3204,
                "OrderView.customer: @Aggregate can't be combined with @Join"));
    }

    @Test
    void ac_diag_01_a_primitive_count_is_one_error_naming_long() {
        Compilation compilation = compileCombination("@Aggregate(fn = AggregateFunction.COUNT) long lines");

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3201,
                "SalesSummary.lines: COUNT is NULL-safe only as an object; use Long, not a primitive"));
    }

    @Test
    void ac_diag_01_a_primitive_sum_over_a_32_bit_attribute_is_one_error_naming_long() {
        Compilation compilation = compileCombination(
                "@Aggregate(fn = AggregateFunction.SUM, attribute = \"units\") int units");

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3201,
                "SalesSummary.units: SUM is NULL over zero rows; use Long, not a primitive"));
    }

    @Test
    void ac_diag_01_an_aggregate_of_an_association_is_mq3002_without_a_filter_column_hint() {
        JavaFileObject view = source("shop.OrderSales", IMPORTS + """
                @QueryModel(root = OrderEntity.class, singleGroup = true)
                public record OrderSales(
                        @Aggregate(fn = AggregateFunction.COUNT, attribute = "customer") Long buyers) {}
                """);

        Compilation compilation = compile(CUSTOMER_ENTITY, ORDER_ENTITY, view);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3002,
                "OrderSales.buyers: @Aggregate attribute 'customer' is an association on OrderEntity; "
                        + "aggregate a basic attribute"));
    }
}
