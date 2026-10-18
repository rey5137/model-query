package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SourceQuery;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Source-over-source compile-time rules (R-SRC-16, R-PROC-40). */
class SourceOverSourceModelTest {

    private static final String HEADER = """
            package shop;
            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Depth;
            import com.rey.modelquery.annotations.Discriminator;
            import com.rey.modelquery.annotations.FilterColumn;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;
            import com.rey.modelquery.annotations.Window;
            import com.rey.modelquery.annotations.WindowFunction;
            import com.rey.modelquery.annotations.WindowFrame;
            """;

    private static final JavaFileObject ENTITY = source("shop.Item", """
            package shop;
            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity public class Item {
                @Id Long id;
                @Column(nullable = false) String name;
                Long amount;
            }
            """);

    private static final JavaFileObject INNER = model("Inner", "Item", "@PrimaryKey Long id, String name, Long amount");

    @Test
    void ac_proc_38_a_self_rooted_source_reports_a_cycle() {
        Compilation compilation = compile(model("A", "A", "@PrimaryKey Long id"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3038, "A: root chain cycles through A"));
    }

    @Test
    void ac_proc_38_mutually_rooted_sources_report_cycles() {
        Compilation compilation = compile(model("A", "B", "@PrimaryKey Long id"),
                model("B", "A", "@PrimaryKey Long id"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message(DiagnosticCode.MQ3038, "A: root chain cycles through A"),
                message(DiagnosticCode.MQ3038, "B: root chain cycles through B"));
    }

    @Test
    void ac_proc_41_a_source_rooted_at_a_branch_must_root_at_its_shape() {
        Compilation compilation = compile(ENTITY, INNER, source("shop.Branch", HEADER + """
                @SourceModel(root = Item.class, shape = Inner.class)
                public record Branch(@PrimaryKey Long id, String name, Long amount) {}
                """), model("Outer", "Branch", "@PrimaryKey Long id"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3042,
                "Outer: root Branch is a branch; root at the shape Inner instead"));
    }

    @Test
    void ac_src_28_a_deferred_branch_contributes_group_key_nullability() {
        Compilation compilation = javac().withProcessors(new ModelQueryProcessor(), new SourceGenerator(Map.of(
                "shop.NullableItem", """
                        package shop;
                        @jakarta.persistence.Entity public class NullableItem {
                            @jakarta.persistence.Id Long id;
                            String name;
                        }
                        """,
                "shop.Shape", HEADER + """
                        @SourceModel(root = Item.class)
                        public record Shape(@PrimaryKey Long id, String name) {}
                        """,
                "shop.Summary", HEADER + """
                        @SourceModel(root = Shape.class)
                        public record Summary(@PrimaryKey @GroupBy String name) {}
                        """))).compile(ENTITY, source("shop.Branch", HEADER + """
                @SourceModel(root = NullableItem.class, shape = Shape.class)
                public record Branch(@PrimaryKey Long id, String name) {}
                """));

        assertNullable(compilation, "Shape", "name");
    }

    @Test
    void ac_proc_41_a_deferred_branch_makes_a_source_over_a_source_a_shape() {
        Compilation compilation = javac().withProcessors(new ModelQueryProcessor(), new SourceGenerator(Map.of(
                "shop.NullableItem", """
                        package shop;
                        @jakarta.persistence.Entity public class NullableItem { @jakarta.persistence.Id Long id; }
                        """,
                "shop.Outer", HEADER + """
                        @SourceModel(root = Inner.class)
                        public record Outer(@PrimaryKey Long id) {}
                        """))).compile(ENTITY, INNER, source("shop.Branch", HEADER + """
                @SourceModel(root = NullableItem.class, shape = Outer.class)
                public record Branch(@PrimaryKey Long id) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message(DiagnosticCode.MQ3042, "Outer: a source over Inner cannot be a shape"),
                message(DiagnosticCode.MQ3042, "Branch: the shape Outer is a source over Inner; "
                        + "a source over a source cannot be a shape"));
    }

    @Test
    void ac_src_26_source_over_plain_source_reads_cte_columns_and_can_add_a_window() {
        Compilation compilation = compile(ENTITY, INNER, model("Ranked", "Inner", """
                @PrimaryKey Long id,
                @Window(function = WindowFunction.ROW_NUMBER, orderBy = "name") Long rank"""));

        assertThat(compilation).succeeded();
        assertThat(generatedFlat(compilation, "shop.QRanked"))
                .contains("TableField.source(Inner.class)", "QInner.NAME");
    }

    @Test
    void ac_src_26_a_source_filter_aliases_an_inner_cte_column() {
        Compilation compilation = compile(ENTITY, INNER, source("shop.Filtered", HEADER + """
                @FilterColumn(name = "NAME_FILTER", path = "name")
                @SourceModel(root = Inner.class)
                public record Filtered(@PrimaryKey Long id) {}
                """));

        assertThat(compilation).succeeded();
        assertThat(generatedFlat(compilation, "shop.QFiltered"))
                .contains("NAME_FILTER = ColumnField.over(Filtered.class, ROOT, QInner.NAME);");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "name.value"})
    void ac_src_26_a_source_filter_must_name_one_inner_cte_column(String path) {
        Compilation compilation = compile(ENTITY, INNER, source("shop.Filtered", HEADER + """
                @FilterColumn(name = "NAME_FILTER", path = "%s")
                @SourceModel(root = Inner.class)
                public record Filtered(@PrimaryKey Long id) {}
                """.formatted(path)));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3011,
                "Filtered @FilterColumn(NAME_FILTER): '" + path + "' is no field of Inner; "
                        + "a source filter reads one CTE column by field name"));
    }

    @Test
    void ac_src_26_a_source_over_a_source_needs_the_inner_key_fields() {
        Compilation compilation = compile(ENTITY, INNER, model("WrongKey", "Inner", "@PrimaryKey String name"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031,
                "WrongKey: @PrimaryKey must be Inner's key fields 'id'; an ungrouped source reads one row per key "
                        + "of the inner source Inner; group the source to key it by its @GroupBy columns"));
    }

    @Test
    void ac_src_26_a_source_over_a_source_rejects_duplicate_key_aliases() {
        Compilation compilation = compile(ENTITY, INNER, model("WrongKey", "Inner", """
                @PrimaryKey Long id, @PrimaryKey @Column(attribute = "id") Long other"""));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).anyMatch(error -> error.startsWith(DiagnosticCode.MQ3031.code()));
    }

    @Test
    void ac_src_27_a_grouped_inner_source_exposes_non_nullable_count() {
        Compilation compilation = compile(ENTITY, model("Counts", "Item", """
                @PrimaryKey @GroupBy String name, @Aggregate(fn = AggregateFunction.COUNT) Long count"""),
                model("Summary", "Counts", """
                        @PrimaryKey @GroupBy Long count, @Aggregate(fn = AggregateFunction.COUNT) Long groups"""));

        assertThat(compilation).succeeded();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROW_NUMBER", "RANK", "DENSE_RANK", "COUNT", "SUM"})
    void ac_src_26_group_key_nullability_follows_the_inner_window(String function) {
        String argument = function.equals("COUNT") || function.equals("SUM")
                ? ", argument = \"amount\", frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW" : "";
        Compilation compilation = compile(ENTITY, model("Ranked", "Item", """
                @PrimaryKey Long id,
                @Window(function = WindowFunction.%s, orderBy = "id"%s) Long rank""".formatted(function, argument)),
                summary("Ranked", "Long rank"));

        if (function.equals("SUM")) {
            assertNullable(compilation, "Ranked", "rank");
        } else {
            assertThat(compilation).succeeded();
        }
    }

    @Test
    void ac_src_26_mapped_nullability_is_inherited_through_three_levels() {
        Compilation compilation = compile(ENTITY, INNER, model("Middle", "Inner", "@PrimaryKey Long id, Long amount"),
                summary("Middle", "Long amount"));

        assertNullable(compilation, "Middle", "amount");
    }

    @Test
    void ac_src_26_non_nullable_mapped_group_key_can_have_an_aggregate() {
        Compilation compilation = compile(ENTITY, INNER, summary("Inner", "String name"));

        assertThat(compilation).succeeded();
    }

    @Test
    void ac_src_28_group_key_is_nullable_when_any_shape_branch_is_nullable() {
        JavaFileObject nullable = source("shop.NullableItem", """
                package shop;
                @jakarta.persistence.Entity public class NullableItem {
                    @jakarta.persistence.Id Long id;
                    String name;
                }
                """);
        Compilation compilation = compile(ENTITY, nullable,
                model("Shape", "Item", "@PrimaryKey Long id, String name"),
                source("shop.Branch", HEADER + """
                        @SourceModel(root = NullableItem.class, shape = Shape.class)
                        public record Branch(@PrimaryKey Long id, String name) {}
                        """), summary("Shape", "String name"));

        assertNullable(compilation, "Shape", "name");
    }

    @Test
    void ac_src_29_recursive_depth_is_a_non_nullable_group_key() {
        Compilation compilation = compile(ENTITY,
                model("Walk", "Item", "@PrimaryKey Long id, @Depth Integer level"), summary("Walk", "Integer level"));

        assertThat(compilation).succeeded();
    }

    @Test
    void ac_proc_41_a_source_over_a_source_cannot_be_recursive() {
        Compilation compilation = compile(ENTITY, INNER,
                model("Outer", "Inner", "@PrimaryKey Long id, @Depth Integer level"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3042,
                "Outer: a source over Inner cannot be recursive"));
    }

    @Test
    void ac_proc_41_a_source_over_a_source_cannot_be_a_branch() {
        Compilation compilation = compile(ENTITY, INNER, source("shop.Outer", HEADER + """
                @SourceModel(root = Inner.class, shape = Inner.class)
                public record Outer(@PrimaryKey Long id, String name, Long amount) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3042,
                "Outer: a source over Inner cannot be a branch"));
    }

    @Test
    void ac_proc_41_a_source_over_a_source_cannot_be_a_shape() {
        Compilation compilation = compile(ENTITY, INNER, model("Outer", "Inner", "@PrimaryKey Long id"),
                source("shop.Branch", HEADER + """
                        @SourceModel(root = Item.class, shape = Outer.class)
                        public record Branch(@PrimaryKey Long id) {}
                        """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3042, "Outer: a source over Inner cannot be a shape"),
                message(DiagnosticCode.MQ3042, "Branch: the shape Outer is a source over Inner; "
                        + "a source over a source cannot be a shape"));
    }

    @Test
    void ac_src_26_a_source_field_must_have_the_inner_fields_type() {
        Compilation compilation = compile(ENTITY, INNER, model("Outer", "Inner", "@PrimaryKey Long id, Long name"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3002,
                "Outer.name: model type Long, the source's name is String"));
    }

    @Test
    void ac_src_26_an_inner_aggregate_other_than_count_is_nullable() {
        Compilation compilation = compile(ENTITY, model("Totals", "Item", """
                @PrimaryKey @GroupBy String name,
                @Aggregate(fn = AggregateFunction.SUM, attribute = "amount") Long total
                """), summary("Totals", "Long total"));

        assertNullable(compilation, "Totals", "total");
    }

    @Test
    void ac_src_28_a_shape_discriminator_is_a_non_nullable_group_key() {
        Compilation compilation = compile(ENTITY, model("Shape", "Item", """
                @PrimaryKey @Discriminator("ITEM") String kind, @PrimaryKey Long id"""),
                summary("Shape", "String kind"));

        assertThat(compilation).succeeded();
    }

    @Test
    void ac_src_26_a_grouped_source_over_a_source_needs_the_full_group_key() {
        Compilation compilation = compile(ENTITY, INNER, model("Summary", "Inner", """
                @PrimaryKey @GroupBy Long id, @GroupBy String name, @Aggregate(fn = AggregateFunction.COUNT) Long count
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031,
                "Summary: @PrimaryKey must be every @GroupBy column of the source, 'id', 'name', "
                        + "since a group is one row"));
    }

    @Test
    void ac_src_26_a_source_filter_cannot_alias_a_join() {
        Compilation compilation = compile(ENTITY, INNER, source("shop.Filtered", HEADER + """
                @FilterColumn(name = "NAME_FILTER", path = "name", alias = "joined")
                @SourceModel(root = Inner.class)
                public record Filtered(@PrimaryKey Long id) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3011,
                "Filtered @FilterColumn(NAME_FILTER): a source column has no join to alias"));
    }

    @Test
    void ac_src_26_a_source_filter_inherits_the_inner_converter() {
        Compilation compilation = compile(ENTITY, INNER, source("shop.Filtered", HEADER + """
                @FilterColumn(name = "NAME_FILTER", path = "name", converter = String.class)
                @SourceModel(root = Inner.class)
                public record Filtered(@PrimaryKey Long id) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3014,
                "Filtered @FilterColumn(NAME_FILTER): a source filter reads its source column's converter; "
                        + "drop converter"));
    }

    @Test
    void ac_src_26_to_31_generated_factories_build_all_inner_kinds_and_three_levels() throws Exception {
        Compilation compilation = compile(ENTITY, INNER,
                model("Plain", "Inner", "@PrimaryKey Long id, String name"),
                model("Third", "Plain", "@PrimaryKey Long id, String name"),
                summary("Inner", "Long id"),
                model("OverSummary", "Summary", "@PrimaryKey Long id, Long count"),
                model("Shape", "Item", "@PrimaryKey @Discriminator(\"A\") String kind, @PrimaryKey Long id"),
                source("shop.Branch", HEADER + """
                        @SourceModel(root = Item.class, shape = Shape.class)
                        public record Branch(@PrimaryKey @Discriminator("B") String kind, @PrimaryKey Long id) {}
                        """),
                model("OverUnion", "Shape", "@PrimaryKey String kind, @PrimaryKey Long id"),
                model("Walk", "Item", "@PrimaryKey Long id, Long amount, @Depth Integer level"),
                model("OverWalk", "Walk", "@PrimaryKey Long id, Integer level"),
                source("shop.UseSources", """
                        package shop;
                        import com.rey.modelquery.core.SourceQuery;
                        import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
                        public class UseSources {
                            public static List<SourceQuery<?>> build() {
                                var plain = QPlain.source(QInner.source().build()).build();
                                var grouped = QSummary.source(QInner.source().build()).build();
                                var union = QShape.source().build().unionAll(QBranch.source().build());
                                var walk = QWalk.source()
                                        .recursive(s -> s.childKey(QWalk.AMOUNT).parentKey(QWalk.ID), 4).build();
                                var tree = QWalk.source()
                                        .tree(s -> s.childKey(QWalk.AMOUNT).parentKey(QWalk.ID), 4).build();
                                return List.of(plain, grouped, QOverSummary.source(grouped).build(),
                                        QOverUnion.source(union).build(), QOverWalk.source(walk).build(),
                                        QOverWalk.source(tree).build(), QThird.source(plain).build());
                            }
                        }
                        """));
        assertThat(compilation).succeeded();
        assertThat(generatedFlat(compilation, "shop.QPlain"))
                .contains("@Incubating", "source(SourceQuery<Inner> inner)",
                        "SourceQuery.builder(Plain.class, ROOT, ALL, KEY, inner)");
        assertThat(generatedFlat(compilation, "shop.QSummary"))
                .contains("SourceQuery.builder(Summary.class, ROOT, ALL, KEY, GROUP_KEYS, inner)");
        Object built = classes(compilation).loadClass("shop.UseSources").getMethod("build").invoke(null);
        assertThat((List<?>) built).hasSize(7);
        assertThat(((List<?>) built).stream().map(value -> ((SourceQuery<?>) value).unique()).toList())
                .containsExactly(true, true, true, true, false, true, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QPlain.source()", "QPlain.source(QPlain.source(QInner.source().build()).build())"})
    void ac_src_26_generated_factory_requires_a_correctly_typed_inner(String expression) {
        Compilation compilation = compile(ENTITY, INNER,
                model("Plain", "Inner", "@PrimaryKey Long id"), source("shop.UseSources", """
                        package shop;
                        public class UseSources { Object value = %s; }
                        """.formatted(expression)));
        assertThat(compilation).failed();
        if (expression.equals("QPlain.source()")) {
            assertThat(errors(compilation)).containsExactly("""
                    method source in class shop.QPlain cannot be applied to given types;
                      required: com.rey.modelquery.core.SourceQuery<shop.Inner>
                      found:    no arguments
                      reason: actual and formal argument lists differ in length""");
        } else {
            assertThat(errors(compilation)).containsExactly("incompatible types: "
                    + "com.rey.modelquery.core.SourceQuery<shop.Plain> cannot be converted to "
                    + "com.rey.modelquery.core.SourceQuery<shop.Inner>");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Item", "Inner"})
    void ac_src_37_a_computed_correlated_source_loads_but_source_factory_is_mq1908(String root) throws Exception {
        Compilation compilation = compile(ENTITY, INNER, source("shop.CorrelatedSource", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @SourceModel(root = %s.class)
                public record CorrelatedSource(@PrimaryKey Long id, @Computed(CountRows.class) Long value) {}
                """.formatted(root)), source("shop.CountRows", """
                package shop;
                import com.rey.modelquery.core.*;
                public class CountRows implements ExpressionDefinition<CorrelatedSource, Long> {
                    private static final TableField<Item, Item> ROOT = TableField.root(Item.class);
                    private static final ColumnField<CorrelatedSource, Item, Long> ID =
                            ColumnField.of(CorrelatedSource.class, ROOT, "id", Long.class);
                    public ExpressionField<CorrelatedSource, Long> expression() {
                        return Expr.plus(Correlated.count(CorrelatedSource.class, SubSelect.of(ID),
                                (f, outer) -> f.compare(ID, Op.EQ, outer.column(ID))), 1L);
                    }
                }
                """));
        assertThat(compilation).succeeded();
        Class<?> qModel = Class.forName("shop.QCorrelatedSource", true, classes(compilation));
        assertThat(qModel.getField("VALUE").get(null)).isNotNull();
        var method = root.equals("Item") ? qModel.getMethod("source") : qModel.getMethod("source", SourceQuery.class);
        Object[] arguments = root.equals("Item") ? new Object[0]
                : new Object[] {((SourceQuery.Builder<?, ?, ?>) qModel.getClassLoader().loadClass("shop.QInner")
                        .getMethod("source").invoke(null)).build()};
        assertThatThrownBy(() -> method.invoke(null, arguments))
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .cause().isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        error -> assertThat(error.code()).isEqualTo(MqCode.MQ1908));
    }

    private static JavaFileObject summary(String root, String key) {
        return model("Summary", root, "@PrimaryKey @GroupBy " + key
                + ", @Aggregate(fn = AggregateFunction.COUNT) Long count");
    }

    private static JavaFileObject model(String name, String root, String fields) {
        return source("shop." + name, HEADER + "@SourceModel(root = " + root + ".class) public record "
                + name + "(" + fields + ") {}\n");
    }

    private static void assertNullable(Compilation compilation, String inner, String field) {
        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031, "Summary." + field
                + ": a group key must be non-nullable, and " + inner + "." + field
                + " is nullable in the inner source"));
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }
    /** Generates models and roots in the next round, leaving the original branch on the deferred list. */
    private static final class SourceGenerator extends AbstractProcessor {
        private final Map<String, String> sources;
        private boolean generated;

        private SourceGenerator(Map<String, String> sources) {
            this.sources = sources;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (!generated) {
                generated = true;
                sources.forEach((name, text) -> {
                    try (Writer out = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                        out.write(text);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            return false;
        }
    }

}
