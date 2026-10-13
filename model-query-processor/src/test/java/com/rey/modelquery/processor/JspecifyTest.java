package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.compileWithOptions;
import static com.rey.modelquery.processor.ProcessorHarness.compileWithout;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** The {@code modelquery.jspecify} option and the nullness annotations it adds (processor/30 R-PROC-32, R-GEN-36). */
class JspecifyTest {

    private static final List<String> ON = List.of("-A" + ModelQueryProcessor.JSPECIFY_OPTION + "=true");

    private static JavaFileObject[] invoiceSources() {
        return Stream.of(Stream.of(ShopSources.INVOICE_SOURCES),
                        Stream.of(ShopSources.CUSTOMER_WITH_INVOICES, ShopSources.INVOICE_VIEW))
                .flatMap(sources -> sources)
                .toArray(JavaFileObject[]::new);
    }

    @Test
    void ac_gen_31_class_query_model_matches_its_golden_file() {
        Compilation compilation = compileWithOptions(ON, ShopSources.ADDRESS, ShopSources.ORDER_ENTITY,
                ShopSources.ORDER_VIEW);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QOrderView")).isEqualTo(resource("golden/jspecify/QOrderView.java"));
    }

    @Test
    void ac_gen_31_join_and_child_fields_match_their_golden_files() {
        Compilation compilation = compileWithOptions(ON, invoiceSources());

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QInvoiceView"))
                .isEqualTo(resource("golden/jspecify/QInvoiceView.java"));
        assertThat(generated(compilation, "shop.QCustomerView"))
                .isEqualTo(resource("golden/jspecify/QCustomerViewWithInvoices.java"));
    }

    @Test
    void ac_gen_31_change_set_matches_its_golden_file() {
        Compilation compilation = compileWithOptions(ON, UpdateModelSources.ORDER_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "patch.OrderPatchChanges"))
                .isEqualTo(resource("golden/jspecify/OrderPatchChanges.java"));
    }

    @Test
    void ac_gen_31_insert_model_matches_its_golden_file() {
        Compilation compilation = compileWithOptions(ON, InsertModelSources.NEW_ORDER_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "ins.QNewOrder")).isEqualTo(resource("golden/jspecify/QNewOrder.java"));
    }

    @Test
    void ac_gen_31_nested_and_import_clashing_types_are_annotated_where_the_name_is_qualified() {
        Compilation compilation = compileWithOptions(ON,
                source("other.Outer", """
                        package other;

                        public class Outer {
                            public enum Kind { A, B }
                        }
                        """),
                source("other.Nullable", """
                        package other;

                        public enum Nullable { YES, NO }
                        """),
                source("tags.TagEntity", """
                        package tags;

                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;
                        import java.util.List;
                        import other.Nullable;
                        import other.Outer;

                        @Entity
                        public class TagEntity {
                            @Id
                            Long id;
                            Outer.Kind kind;
                            Nullable flag;
                            byte[] blob;
                        }
                        """),
                source("tags.TagPatch", """
                        package tags;

                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.UpdateModel;
                        import other.Nullable;
                        import other.Outer;

                        @UpdateModel(root = TagEntity.class)
                        public record TagPatch(@PrimaryKey Long id, Outer.Kind kind, Nullable flag, byte[] blob) {}
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "tags.TagPatchChanges"))
                .contains("private Outer. @Nullable Kind kind;")
                .contains("private other. @Nullable Nullable flag;")
                .contains("private byte @Nullable [] blob;")
                .contains("import org.jspecify.annotations.Nullable;");
    }

    @Test
    void ac_proc_27_option_off_adds_no_annotation_and_leaves_the_output_unchanged() {
        Compilation compilation = compile(UpdateModelSources.ORDER_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "patch.OrderPatchChanges")).doesNotContain("jspecify");
        assertThat(generated(compilation, "patch.QOrderPatch")).doesNotContain("jspecify");
    }

    @Test
    void ac_proc_27_option_false_adds_no_annotation() {
        Compilation compilation = compileWithOptions(List.of("-A" + ModelQueryProcessor.JSPECIFY_OPTION + "=false"),
                UpdateModelSources.ORDER_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "patch.OrderPatchChanges")).doesNotContain("jspecify");
    }

    @Test
    void ac_proc_27_option_without_jspecify_on_the_classpath_is_mq3027_once_and_generates_no_annotation() {
        Compilation compilation = compileWithout("jspecify", ON, UpdateModelSources.ORDER_PATCH_SOURCES);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).hasSize(1);
        assertThat(errors(compilation).get(0)).contains("MQ3027").contains("modelquery.jspecify");
        // One error only: an annotation named in a generated class would add "package org.jspecify does not exist".
    }
}
