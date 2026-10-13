package com.rey.modelquery.processor;

import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * The generated code under {@code -Amodelquery.jspecify=true} is accepted by NullAway in JSpecify mode (processor/31
 * R-GEN-36, AC-GEN-32). The flags below are the ones docs/site/docs/models.md shows.
 */
class NullAwayTest {

    private static final List<String> OPTIONS = List.of(
            "-A" + ModelQueryProcessor.JSPECIFY_OPTION + "=true",
            "-XDcompilePolicy=simple",
            "-XDaddTypeAnnotationsToSymbol=true",
            "--should-stop=ifError=FLOW",
            "-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR"
                    + " -XepOpt:NullAway:AnnotatedPackages=shop,patch,ins"
                    + " -XepOpt:NullAway:JSpecifyMode=true"
                    + " -XepOpt:NullAway:ExcludedClassAnnotations="
                    + "jakarta.persistence.Entity,jakarta.persistence.Embeddable,com.rey.modelquery.annotations.QueryModel");

    private static Compilation compile(JavaFileObject[] sources, JavaFileObject... extra) {
        List<JavaFileObject> all = new ArrayList<>(List.of(sources));
        all.addAll(List.of(extra));
        List<File> path = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(File::new).toList();
        return javac().withProcessors(new ModelQueryProcessor()).withAnnotationProcessorPath(path)
                .withOptions(OPTIONS).compile(all);
    }

    private static final JavaFileObject[] SHOP = Stream.of(ShopSources.INVOICE_SOURCES,
            new JavaFileObject[] {ShopSources.ADDRESS, ShopSources.ORDER_ENTITY, ShopSources.ORDER_VIEW,
                    ShopSources.CUSTOMER_WITH_INVOICES, ShopSources.INVOICE_VIEW})
            .flatMap(Stream::of).distinct().toArray(JavaFileObject[]::new);

    @Test
    void ac_gen_32_shop_query_models_report_no_nullaway_error() {
        Compilation compilation = compile(SHOP);

        assertThat(errors(compilation)).isEmpty();
    }

    @Test
    void ac_gen_32_update_change_set_reports_no_nullaway_error() {
        Compilation compilation = compile(UpdateModelSources.ORDER_PATCH_SOURCES, source("patch.Use", """
                package patch;

                import org.jspecify.annotations.Nullable;

                final class Use {
                    @Nullable String note(OrderPatchChanges changes) {
                        changes.note(null);
                        changes.setCity(null);
                        return changes.getNote();
                    }
                }
                """));

        assertThat(errors(compilation)).isEmpty();
    }

    @Test
    void ac_gen_32_insert_model_reports_no_nullaway_error() {
        Compilation compilation = compile(InsertModelSources.NEW_ORDER_SOURCES);

        assertThat(errors(compilation)).isEmpty();
    }

    @Test
    void ac_gen_32_a_dereferenced_nullable_getter_is_flagged() {
        Compilation compilation = compile(UpdateModelSources.ORDER_PATCH_SOURCES, source("patch.Misuse", """
                package patch;

                final class Misuse {
                    int length(OrderPatchChanges changes) {
                        return changes.getNote().length();
                    }
                }
                """));

        assertThat(errors(compilation)).anyMatch(message -> message.contains("NullAway")
                && message.contains("dereferenced expression changes.getNote() is @Nullable"));
    }
}
