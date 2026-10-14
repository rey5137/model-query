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
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Update and insert models bound by {@code @BindUpdateModel} and {@code @BindInsertModel}, and a bound query model's
 * change set and insert members, in the module that sees the entity (R-PROC-28, R-GEN-35).
 */
class BoundWriteModelTest {

    @TempDir
    Path classes;

    @Test
    void ac_gen_30_bound_update_model_matches_the_annotated_goldens_apart_from_their_package() {
        compileToClasspath(classes, UpdateModelSources.ORDER_STATUS,
                unannotated(UpdateModelSources.ORDER_PATCH, "patch.OrderPatch", "UpdateModel"));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, UpdateModelSources.ADDRESS,
                UpdateModelSources.CUSTOMER_ENTITY, UpdateModelSources.ORDER_ENTITY,
                binding("patch", "@BindUpdateModel(model = OrderPatch.class, root = OrderEntity.class)"));

        assertThat(compilation).succeededWithoutWarnings();
        assertBound(compilation, "patch", "QOrderPatch");
        assertBound(compilation, "patch", "OrderPatchChanges");
        assertThat(processor.originating())
                .containsOnlyKeys("persistence.QOrderPatch", "persistence.OrderPatchChanges");
        processor.originating().values().forEach(elements ->
                assertThat(elements).singleElement().hasToString("persistence.Models"));
    }

    @Test
    void ac_gen_30_bound_insert_model_matches_the_annotated_golden_apart_from_its_package() {
        compileToClasspath(classes, InsertModelSources.ORDER_STATUS,
                unannotated(InsertModelSources.NEW_ORDER, "ins.NewOrder", "InsertModel"));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, InsertModelSources.ADDRESS,
                InsertModelSources.CUSTOMER_ENTITY, InsertModelSources.ORDER_ENTITY,
                binding("ins", "@BindInsertModel(model = NewOrder.class, root = OrderEntity.class)"));

        assertThat(compilation).succeededWithoutWarnings();
        assertBound(compilation, "ins", "QNewOrder");
        assertThat(processor.originating()).containsOnlyKeys("persistence.QNewOrder");
        assertThat(processor.originating().get("persistence.QNewOrder")).singleElement()
                .hasToString("persistence.Models");
    }

    @Test
    void ac_gen_30_bound_query_model_generates_the_change_set_and_insert_members_the_annotated_one_does() {
        JavaFileObject annotated = source("shop.OrderSummary", text(ShopSources.ORDER_SUMMARY).replace(
                "@QueryModel(root = OrderEntity.class)",
                "@QueryModel(root = OrderEntity.class, generateChanges = true, generateInserts = true)"));
        Compilation expected = compile(ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, annotated);
        assertThat(expected).succeededWithoutWarnings();
        compileToClasspath(classes, unannotated(ShopSources.ORDER_SUMMARY, "shop.OrderSummary", "QueryModel"));

        Compilation compilation = compileAgainst(classes, new ProcessorHarness.Recording(), ShopSources.ADDRESS,
                ShopSources.ORDER_ENTITY, binding("shop", "@BindQueryModel(model = OrderSummary.class, "
                        + "root = OrderEntity.class, generateChanges = true, generateInserts = true)"));

        assertThat(compilation).succeededWithoutWarnings();
        for (String name : List.of("QOrderSummary", "OrderSummaryChanges")) {
            assertThat(moved(generated(compilation, "persistence." + name), "shop"))
                    .isEqualTo(generated(expected, "shop." + name));
        }
        assertThat(generated(compilation, "persistence.QOrderSummary")).contains("INSERT_COLUMNS");
    }

    @Test
    void ac_proc_23_model_bound_once_per_kind_generates_each() {
        compileToClasspath(classes, UpdateModelSources.ORDER_STATUS,
                unannotated(UpdateModelSources.ORDER_PATCH, "patch.OrderPatch", "UpdateModel"));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, UpdateModelSources.ADDRESS,
                UpdateModelSources.CUSTOMER_ENTITY, UpdateModelSources.ORDER_ENTITY,
                binding("patch", "@BindUpdateModel(model = OrderPatch.class, root = OrderEntity.class)\n"
                        + "@BindQueryModel(model = OrderPatch.class, root = OrderEntity.class, prefix = \"V\")"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(processor.originating()).containsOnlyKeys(
                "persistence.QOrderPatch", "persistence.OrderPatchChanges", "persistence.VOrderPatch");
    }

    @Test
    void ac_proc_23_bindings_of_two_kinds_generating_one_qmodel_name_report_mq3023_on_each() {
        compileToClasspath(classes, UpdateModelSources.ORDER_STATUS,
                unannotated(UpdateModelSources.ORDER_PATCH, "patch.OrderPatch", "UpdateModel"));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, UpdateModelSources.ADDRESS,
                UpdateModelSources.CUSTOMER_ENTITY, UpdateModelSources.ORDER_ENTITY,
                binding("patch", "@BindUpdateModel(model = OrderPatch.class, root = OrderEntity.class)\n"
                        + "@BindQueryModel(model = OrderPatch.class, root = OrderEntity.class)"));

        String message = "MQ3023: OrderPatch: @BindQueryModel on persistence.Models and @BindUpdateModel on "
                + "persistence.Models each generate persistence.QOrderPatch; give one another prefix or package";
        assertThat(errors(compilation)).containsExactly(message, message);
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_23_bindings_of_two_kinds_generating_one_change_set_name_report_mq3023_on_each() {
        compileToClasspath(classes, UpdateModelSources.ORDER_STATUS,
                unannotated(UpdateModelSources.ORDER_PATCH, "patch.OrderPatch", "UpdateModel"));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, UpdateModelSources.ADDRESS,
                UpdateModelSources.CUSTOMER_ENTITY, UpdateModelSources.ORDER_ENTITY,
                binding("patch", "@BindUpdateModel(model = OrderPatch.class, root = OrderEntity.class)"),
                binding("patch", "MoreModels", "@BindQueryModel(model = OrderPatch.class, root = OrderEntity.class,"
                        + " prefix = \"V\", generateChanges = true)"));

        String message = "MQ3023: OrderPatch: @BindQueryModel on persistence.MoreModels and @BindUpdateModel on "
                + "persistence.Models each generate persistence.OrderPatchChanges; bind one in another package";
        assertThat(errors(compilation)).containsExactly(message, message);
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_diag_13_mq3506_on_a_bound_model_names_the_binding() {
        compileToClasspath(classes, UpdateModelSources.ORDER_STATUS, source("patch.OrderPatch",
                text(unannotated(UpdateModelSources.ORDER_PATCH, "patch.OrderPatch", "UpdateModel"))
                        .replace("String note", "@com.rey.modelquery.annotations.ExcludeFromInserts String note")));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, UpdateModelSources.ADDRESS,
                UpdateModelSources.CUSTOMER_ENTITY, UpdateModelSources.ORDER_ENTITY,
                binding("patch", "@BindQueryModel(model = OrderPatch.class, root = OrderEntity.class)"));

        assertThat(errors(compilation)).containsExactly(
                "MQ3506: OrderPatch.note: @ExcludeFromInserts needs generateInserts = true on the @BindQueryModel");
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_23_model_bound_twice_for_one_kind_reports_mq3023_on_each_binding() {
        compileToClasspath(classes, UpdateModelSources.ORDER_STATUS,
                unannotated(UpdateModelSources.ORDER_PATCH, "patch.OrderPatch", "UpdateModel"));
        String bind = "@BindUpdateModel(model = OrderPatch.class, root = OrderEntity.class)";
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, UpdateModelSources.ADDRESS,
                UpdateModelSources.CUSTOMER_ENTITY, UpdateModelSources.ORDER_ENTITY,
                binding("patch", "Models", bind), binding("patch", "MoreModels", bind));

        String message = "MQ3023: OrderPatch: @BindUpdateModel binds it more than once, on "
                + "persistence.Models and persistence.MoreModels; keep one";
        assertThat(errors(compilation)).containsExactly(message, message);
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_23_annotated_model_bound_for_another_kind_is_mq3023() {
        compileToClasspath(classes, InsertModelSources.ADDRESS, InsertModelSources.CUSTOMER_ENTITY,
                InsertModelSources.ORDER_ENTITY, InsertModelSources.ORDER_STATUS, InsertModelSources.NEW_ORDER);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor,
                binding("ins", "@BindUpdateModel(model = NewOrder.class, root = OrderEntity.class)"));

        assertThat(errors(compilation)).containsExactly("MQ3023: NewOrder: carries @InsertModel and is bound by "
                + "@BindUpdateModel on persistence.Models; keep one");
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_24_insert_model_getter_not_public_is_mq3024() {
        compileToClasspath(classes, rowClass("public Long getId()", "String getNotes()"));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ShopSources.ADDRESS,
                ShopSources.ORDER_ENTITY,
                binding("shop", "@BindInsertModel(model = OrderRow.class, root = OrderEntity.class)"));

        assertThat(errors(compilation)).containsExactly("MQ3024: OrderRow.getNotes is not public, but QOrderRow is "
                + "generated in persistence and calls it; make it public");
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_24_update_model_calls_no_accessor_of_its_class() {
        compileToClasspath(classes, rowClass("Long getId()", "String getNotes()"));

        Compilation compilation = compileAgainst(classes, new ProcessorHarness.Recording(), ShopSources.ADDRESS,
                ShopSources.ORDER_ENTITY,
                binding("shop", "@BindUpdateModel(model = OrderRow.class, root = OrderEntity.class)"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation.generatedSourceFile("persistence.OrderRowChanges")).isPresent();
    }

    /** A class row of {@code shop.OrderEntity} whose {@code getId} and {@code getNotes} are declared as given. */
    private static JavaFileObject rowClass(String id, String notes) {
        return source("shop.OrderRow", """
                package shop;

                import com.rey.modelquery.annotations.PrimaryKey;

                public class OrderRow {
                    @PrimaryKey
                    private Long id;
                    private String status;
                    private String notes;

                    %s {
                        return id;
                    }

                    public String getStatus() {
                        return status;
                    }

                    %s {
                        return notes;
                    }
                }
                """.formatted(id, notes));
    }

    /** {@code persistence.<name>}'s source moved back to {@code pkg}: its package line, less its imports of it. */
    private static String moved(String bound, String pkg) {
        return bound.replace("package persistence;", "package " + pkg + ";")
                .replaceAll("(?m)^import " + pkg + "\\.[\\w.]+;\\n", "");
    }

    /** That {@code persistence.<name>} is {@code golden/bound/<name>.java} and moved back, {@code golden/<name>}. */
    private static void assertBound(Compilation compilation, String pkg, String name) {
        String bound = generated(compilation, "persistence." + name);
        assertThat(bound).isEqualTo(resource("golden/bound/" + name + ".java"));
        assertThat(moved(bound, pkg)).isEqualTo(resource("golden/" + name + ".java"));
    }

    private static JavaFileObject binding(String pkg, String annotations) {
        return binding(pkg, "Models", annotations);
    }

    /** A type {@code persistence.<name>} carrying {@code annotations}, which see every type of {@code pkg}. */
    private static JavaFileObject binding(String pkg, String name, String annotations) {
        return source("persistence." + name, """
                package persistence;

                import com.rey.modelquery.annotations.*;
                import %s.*;

                %s
                final class %s {}
                """.formatted(pkg, annotations, name));
    }

    /** {@code model} with its {@code @<annotation>} and the import of it removed. */
    private static JavaFileObject unannotated(JavaFileObject model, String name, String annotation) {
        String bare = text(model).replace("import com.rey.modelquery.annotations." + annotation + ";\n", "")
                .replaceAll("@" + annotation + "\\([^)]*\\)\n", "");
        assertThat(bare).doesNotContain("@" + annotation);
        return source(name, bare);
    }

    private static String text(JavaFileObject source) {
        try {
            return source.getCharContent(false).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
