package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** {@code @JoinSource} and {@code @SourceColumn} ({@code processor/30}, R-PROC-44, {@code api/17} R-SRC-21). */
class JoinSourceModelTest {

    private static final JavaFileObject DAY_ENTITY = source("shop.DayEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;
            import java.time.LocalDate;

            @Entity
            public class DayEntity {
                @Id
                Long id;
                LocalDate businessDate;
                String shift;
                Long storeId;
                BigDecimal countedCash;
            }
            """);

    private static final JavaFileObject SALE_ENTITY = source("shop.SaleEntity", """
            package shop;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;
            import java.time.LocalDate;

            @Entity
            public class SaleEntity {
                @Id
                Long id;
                @Column(nullable = false)
                LocalDate businessDate;
                @Column(nullable = false)
                Long storeId;
                BigDecimal cashTotal;
            }
            """);

    private static final JavaFileObject DAY_SALES = source("shop.DaySales", """
            package shop;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;
            import java.math.BigDecimal;
            import java.time.LocalDate;

            @SourceModel(root = SaleEntity.class)
            public record DaySales(
                    @PrimaryKey @GroupBy LocalDate businessDate,
                    @Aggregate(fn = AggregateFunction.SUM, attribute = "cashTotal") BigDecimal cash) {}
            """);

    private static final JavaFileObject STORE_DAY_SALES = source("shop.StoreDaySales", """
            package shop;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;
            import java.math.BigDecimal;
            import java.time.LocalDate;

            @SourceModel(root = SaleEntity.class)
            public record StoreDaySales(
                    @PrimaryKey @GroupBy LocalDate businessDate,
                    @PrimaryKey @GroupBy Long storeId,
                    @Aggregate(fn = AggregateFunction.SUM, attribute = "cashTotal") BigDecimal cash) {}
            """);

    private static JavaFileObject day(String declaration) {
        return source("shop.UnreconciledDay", """
                package shop;

                import com.rey.modelquery.annotations.JoinKind;
                import com.rey.modelquery.annotations.JoinSource;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import com.rey.modelquery.annotations.SourceColumn;
                import java.math.BigDecimal;
                import java.time.LocalDate;

                """ + declaration);
    }

    private static Compilation compileDay(String declaration) {
        return compile(DAY_ENTITY, SALE_ENTITY, DAY_SALES, STORE_DAY_SALES, day(declaration));
    }

    private static String message(String detail) {
        return DiagnosticCode.MQ3044.code() + ": " + detail;
    }

    @Test
    void ac_src_42_a_joined_source_is_a_source_join_a_query_parameter_and_a_column_over_its_table() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(
                        @PrimaryKey Long id, LocalDate businessDate, BigDecimal countedCash,
                        @SourceColumn(join = "sales", field = "cash") BigDecimal salesCash) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QUnreconciledDay");
        assertThat(generated)
                .contains("SourceJoin<UnreconciledDay, DaySales> SALES = SourceJoin.of(\"sales\", DaySales.class, "
                        + "JoinKind.LEFT, List.of(SourceJoin.on(ColumnField.of(UnreconciledDay.class, ROOT, "
                        + "\"businessDate\", LocalDate.class), QDaySales.BUSINESS_DATE)));")
                .contains("SALES_CASH = ColumnField.over(UnreconciledDay.class, SALES.root(), QDaySales.CASH);")
                .contains("query( SourceQuery<DaySales> sales)")
                .contains(".primaryKey(KEY).joinSource(SALES, sales);");
    }

    @Test
    void ac_src_42_two_joins_of_one_source_and_an_inner_join_keep_their_own_names_and_kinds() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "first", source = DaySales.class, type = JoinKind.INNER,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                @JoinSource(name = "second", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(
                        @PrimaryKey Long id,
                        @SourceColumn(join = "first", field = "cash") BigDecimal firstCash,
                        @SourceColumn(join = "second", field = "cash") BigDecimal secondCash) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QUnreconciledDay"))
                .contains("SourceJoin.of(\"first\", DaySales.class, JoinKind.INNER")
                .contains("FIRST_CASH = ColumnField.over(UnreconciledDay.class, FIRST.root(), QDaySales.CASH);")
                .contains("SECOND_CASH = ColumnField.over(UnreconciledDay.class, SECOND.root(), QDaySales.CASH);")
                .contains("query( SourceQuery<DaySales> first, SourceQuery<DaySales> second)")
                .contains(".joinSource(FIRST, first).joinSource(SECOND, second)");
    }

    @Test
    void ac_src_45_a_composite_key_is_joined_pair_by_pair() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = StoreDaySales.class, on = {
                        @JoinSource.On(column = "businessDate", key = "businessDate"),
                        @JoinSource.On(column = "storeId", key = "storeId")})
                public record UnreconciledDay(
                        @PrimaryKey Long id, @SourceColumn(join = "sales", field = "cash") BigDecimal salesCash) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
    }

    @Test
    void ac_src_45_a_partial_key_is_mq3044() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = StoreDaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(@PrimaryKey Long id) {}
                """);

        assertThat(errors(compilation)).containsExactly(message("UnreconciledDay join 'sales': on misses the key "
                + "field(s) [storeId] of StoreDaySales; a partial key could repeat an outer row"));
    }

    @Test
    void ac_src_45_an_extra_pair_a_non_key_field_and_a_key_paired_twice_are_mq3044() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = DaySales.class, on = {
                        @JoinSource.On(column = "businessDate", key = "businessDate"),
                        @JoinSource.On(column = "businessDate", key = "businessDate"),
                        @JoinSource.On(column = "countedCash", key = "cash"),
                        @JoinSource.On(column = "storeId", key = "nope")})
                public record UnreconciledDay(@PrimaryKey Long id) {}
                """);

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message("UnreconciledDay join 'sales': on pairs the key field 'businessDate' twice"),
                message("UnreconciledDay join 'sales': on(key = \"cash\") is not a key field of DaySales; "
                        + "join on its key [businessDate]"),
                message("UnreconciledDay join 'sales': on(key = \"nope\") is no field of DaySales"));
    }

    @Test
    void ac_src_45_a_type_mismatch_and_an_unknown_outer_column_are_mq3044() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = DaySales.class,
                        on = @JoinSource.On(column = "shift", key = "businessDate"))
                @JoinSource(name = "other", source = DaySales.class,
                        on = @JoinSource.On(column = "missing", key = "businessDate"))
                public record UnreconciledDay(@PrimaryKey Long id) {}
                """);

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message("UnreconciledDay join 'sales': on(column = \"shift\") is String, the key field "
                        + "businessDate is LocalDate"),
                message("UnreconciledDay join 'other': on(column = \"missing\"): no attribute 'missing' on "
                        + "DayEntity"));
    }

    @Test
    void ac_src_45_a_class_that_is_no_source_model_is_mq3044() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = SaleEntity.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(@PrimaryKey Long id) {}
                """);

        assertThat(errors(compilation)).containsExactly(
                message("UnreconciledDay join 'sales': SaleEntity is not a @SourceModel"));
    }

    @Test
    void ac_src_45_a_source_column_naming_an_unknown_join_or_field_is_mq3044_and_a_wrong_type_is_mq3002() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(
                        @PrimaryKey Long id,
                        @SourceColumn(join = "nope", field = "cash") BigDecimal noJoin,
                        @SourceColumn(join = "sales", field = "nope") BigDecimal noField,
                        @SourceColumn(join = "sales", field = "cash") String wrongType) {}
                """);

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message("UnreconciledDay.noJoin: no @JoinSource named 'nope' on UnreconciledDay"),
                message("UnreconciledDay.noField: 'nope' is no field of DaySales, the source of join 'sales'"),
                DiagnosticCode.MQ3002.code() + ": UnreconciledDay.wrongType: model type String, the source's "
                        + "cash is BigDecimal");
    }

    @Test
    void ac_src_46_ac_src_57_a_child_whose_child_model_joins_a_source_is_mq3039() {
        Compilation compilation = compile(DAY_ENTITY, SALE_ENTITY, DAY_SALES, day("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(
                        @PrimaryKey Long id, @SourceColumn(join = "sales", field = "cash") BigDecimal salesCash) {}
                """), source("shop.Parent", """
                package shop;

                import com.rey.modelquery.annotations.Child;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import java.util.List;

                @QueryModel(root = DayEntity.class)
                public record Parent(@PrimaryKey Long id, @Child(foreignKey = "id") List<UnreconciledDay> days) {}
                """));

        assertThat(errors(compilation)).containsExactly(DiagnosticCode.MQ3039.code() + ": Parent.days: "
                + "UnreconciledDay joins 'sales', a @SourceModel; a fetch plan has no source to pass it");
    }

    @Test
    void ac_src_45_a_join_on_a_model_rooted_at_a_source_is_mq3044() {
        Compilation compilation = compile(DAY_ENTITY, SALE_ENTITY, DAY_SALES, source("shop.Outer", """
                package shop;

                import com.rey.modelquery.annotations.JoinSource;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import java.time.LocalDate;

                @QueryModel(root = DaySales.class)
                @JoinSource(name = "again", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record Outer(@PrimaryKey LocalDate businessDate) {}
                """));

        assertThat(errors(compilation)).containsExactly(
                message("Outer: a model rooted at a source cannot join another source"));
    }

    @Test
    void ac_proc_44_a_source_column_is_left_out_of_generated_inserts_and_changes() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class, generateInserts = true, generateChanges = true)
                @JoinSource(name = "sales", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(
                        @PrimaryKey Long id, BigDecimal countedCash,
                        @SourceColumn(join = "sales", field = "cash") BigDecimal salesCash) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QUnreconciledDay");
        assertThat(generated).contains(".add(COUNTED_CASH,").doesNotContain(".add(SALES_CASH,")
                .doesNotContain(".addKey(SALES_CASH,")
                .contains("{@code salesCash}: {@code @SourceColumn}, a joined source's column");
        assertThat(generatedFlat(compilation, "shop.UnreconciledDayChanges"))
                .contains("countedCash(").doesNotContain("salesCash(");
    }

    @Test
    void ac_proc_46_a_source_column_beside_a_primary_key_or_a_non_column_annotation_is_mq3044() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(
                        @PrimaryKey Long id,
                        @PrimaryKey @SourceColumn(join = "sales", field = "businessDate") LocalDate keyed,
                        @SourceColumn(join = "sales", field = "cash")
                        @com.rey.modelquery.annotations.Aggregate(
                                fn = com.rey.modelquery.annotations.AggregateFunction.SUM, attribute = "countedCash")
                        BigDecimal summed,
                        @SourceColumn(join = "sales", field = "cash")
                        @com.rey.modelquery.annotations.Transient BigDecimal skipped) {}
                """);

        String combined = "@SourceColumn can't be combined with @Aggregate, @Computed, @Window, @Join, @Transient, "
                + "@Child or @Selected";
        assertThat(errors(compilation)).contains(
                message("UnreconciledDay.keyed: @SourceColumn can't be the @PrimaryKey; a model's key is the "
                        + "root's, and a joined value can be NULL or shared"),
                message("UnreconciledDay.summed: " + combined),
                message("UnreconciledDay.skipped: " + combined));
    }

    @Test
    void ac_proc_46_a_converted_source_key_in_an_on_pair_is_mq3044() {
        Compilation compilation = compile(DAY_ENTITY, SALE_ENTITY, source("shop.CodeConverter", """
                package shop;

                import com.rey.modelquery.core.ColumnConverter;

                public class CodeConverter implements ColumnConverter<String, Long> {
                    public String toModel(Long attribute) {
                        return attribute == null ? null : attribute.toString();
                    }

                    public Long toAttribute(String model) {
                        return model == null ? null : Long.valueOf(model);
                    }
                }
                """), source("shop.StoreSales", """
                package shop;

                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;

                @SourceModel(root = SaleEntity.class)
                public record StoreSales(
                        @PrimaryKey @GroupBy @Column(attribute = "storeId", converter = CodeConverter.class)
                        String code) {}
                """), day("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = StoreSales.class,
                        on = @JoinSource.On(column = "shift", key = "code"))
                public record UnreconciledDay(@PrimaryKey Long id) {}
                """));

        assertThat(errors(compilation)).containsExactly(
                message("UnreconciledDay join 'sales': on(key = \"code\") is converted by CodeConverter; the "
                        + "join would compare the database value, so join on a key field with no converter"));
    }

    @Test
    void ac_src_45_a_nested_join_model_that_joins_a_source_is_mq3044() {
        Compilation compilation = compile(DAY_ENTITY, SALE_ENTITY, DAY_SALES, source("shop.NoteEntity", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;

                @Entity
                public class NoteEntity {
                    @Id
                    Long id;
                    @ManyToOne
                    DayEntity day;
                }
                """), day("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "sales", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(
                        @PrimaryKey Long id, @SourceColumn(join = "sales", field = "cash") BigDecimal salesCash) {}
                """), source("shop.Note", """
                package shop;

                import com.rey.modelquery.annotations.Join;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import java.util.Optional;

                @QueryModel(root = NoteEntity.class)
                public record Note(@PrimaryKey Long id, @Join(attribute = "day") Optional<UnreconciledDay> day) {}
                """));

        assertThat(errors(compilation)).containsExactly(message("Note.day: UnreconciledDay joins a source; "
                + "a @Join model reads entity columns only"));
    }

    @Test
    void ac_src_45_a_nested_join_model_with_an_entity_join_is_mq3044() {
        Compilation compilation = compile(DAY_ENTITY, source("shop.NoteEntity", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;

                @Entity
                public class NoteEntity {
                    @Id
                    Long id;
                    @ManyToOne
                    DayEntity day;
                }
                """), source("shop.UnreconciledDay", """
                package shop;

                import com.rey.modelquery.annotations.JoinEntity;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = DayEntity.class)
                @JoinEntity(name = "twin", entity = DayEntity.class, on = @JoinEntity.On(column = "id"))
                public record UnreconciledDay(@PrimaryKey Long id) {}
                """), source("shop.Note", """
                package shop;

                import com.rey.modelquery.annotations.Join;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import java.util.Optional;

                @QueryModel(root = NoteEntity.class)
                public record Note(@PrimaryKey Long id, @Join(attribute = "day") Optional<UnreconciledDay> day) {}
                """));

        assertThat(errors(compilation)).containsExactly(message("Note.day: UnreconciledDay has an entity join; "
                + "a @Join model reads entity columns only"));
    }

    @Test
    void ac_src_45_a_join_named_like_a_field_or_root_clashes_in_the_generated_constants() {
        Compilation compilation = compileDay("""
                @QueryModel(root = DayEntity.class)
                @JoinSource(name = "shift", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                @JoinSource(name = "root", source = DaySales.class,
                        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
                public record UnreconciledDay(@PrimaryKey Long id, String shift) {}
                """);

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                DiagnosticCode.MQ3015.code() + ": UnreconciledDay.shift: constant SHIFT is also generated for join "
                        + "'shift'; rename the field or set @Join(prefix)",
                message("UnreconciledDay join 'root': constant ROOT is also generated for the generated class"));
    }
}
