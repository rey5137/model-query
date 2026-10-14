package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
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
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The models a bound model nests through {@code @Join} and holds through {@code @Child} (R-PROC-31). */
class BoundNestedModelTest {

    /** {@link ShopSources#CUSTOMER_WITH_INVOICES} less its {@code @QueryModel}, as a model module writes it. */
    private static final JavaFileObject CUSTOMER_VIEW = unannotated(ShopSources.CUSTOMER_WITH_INVOICES);

    private static final JavaFileObject INVOICE_VIEW = unannotated(ShopSources.INVOICE_VIEW);

    private static final JavaFileObject COUNTRY_VIEW = unannotated(ShopSources.COUNTRY_VIEW);

    /** The entities the persistence module compiles its bindings with. */
    private static final JavaFileObject[] ENTITIES =
            {ShopSources.COUNTRY_ENTITY, ShopSources.CUSTOMER_ENTITY, ShopSources.INVOICE_ENTITY};

    private static final String BIND_CUSTOMER =
            "@BindQueryModel(model = CustomerView.class, root = CustomerEntity.class)\n";

    private static final String BIND_ALL = BIND_CUSTOMER
            + "@BindQueryModel(model = InvoiceView.class, root = InvoiceEntity.class)\n"
            + "@BindQueryModel(model = CountryView.class, root = CountryEntity.class)\n";

    @TempDir
    Path classes;

    @Test
    void ac_proc_26_bound_model_with_bound_nested_models_matches_its_golden_apart_from_packages() {
        compileToClasspath(classes, ShopSources.INVOICE_STATUS, CUSTOMER_VIEW, INVOICE_VIEW, COUNTRY_VIEW);

        Compilation compilation = compileAgainst(classes, new ModelQueryProcessor(),
                with(ENTITIES, binding(BIND_ALL)));

        assertThat(compilation).succeededWithoutWarnings();
        String customer = generated(compilation, "persistence.QCustomerView");
        assertThat(customer).isEqualTo(resource("golden/bound/QCustomerViewWithInvoices.java"));
        // The outer QModel names QInvoiceView and QCountryView in the binding's package, so it imports neither.
        assertThat(inShop(customer)).isEqualTo(resource("golden/QCustomerViewWithInvoices.java"));
        assertThat(inShop(generated(compilation, "persistence.QInvoiceView")))
                .isEqualTo(resource("golden/QInvoiceView.java"));
        assertThat(compilation.generatedSourceFile("persistence.QCountryView")).isPresent();
    }

    @Test
    void ac_proc_26_bound_model_with_annotated_nested_models_matches_its_golden_apart_from_packages() {
        // An annotated nested model on the classpath, generated with its module (D-45).
        compileToClasspath(classes, ShopSources.COUNTRY_ENTITY, ShopSources.COUNTRY_VIEW);

        // An annotated nested model in the compilation, which itself joins the bound model back.
        Compilation compilation = compileAgainst(classes, new ModelQueryProcessor(), with(ENTITIES,
                ShopSources.INVOICE_STATUS, CUSTOMER_VIEW, ShopSources.INVOICE_VIEW, binding(BIND_CUSTOMER)));

        assertThat(compilation).succeededWithoutWarnings();
        String customer = generated(compilation, "persistence.QCustomerView");
        assertThat(customer).contains("import shop.QCountryView;\n", "import shop.QInvoiceView;\n");
        assertThat(inShop(customer)).isEqualTo(resource("golden/QCustomerViewWithInvoices.java"));
        assertThat(generated(compilation, "shop.QInvoiceView")).contains("import persistence.QCustomerView;\n");
        assertThat(compilation.generatedSourceFile("shop.QCustomerView")).isEmpty();
    }

    @Test
    void ac_proc_26_nested_model_neither_annotated_nor_bound_is_mq3026() {
        compileToClasspath(classes, ShopSources.INVOICE_STATUS, CUSTOMER_VIEW, INVOICE_VIEW, COUNTRY_VIEW);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, with(ENTITIES, binding(BIND_CUSTOMER)));

        String bind = " is neither annotated @QueryModel nor bound by @BindQueryModel in this compilation; bind it "
                + "with @BindQueryModel, as persistence.Models binds CustomerView";
        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                "MQ3026: CustomerView.invoices: InvoiceView" + bind,
                "MQ3026: CustomerView.country: CountryView" + bind);
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_26_nested_model_not_public_is_mq3024() {
        // Annotated, so each QModel is generated in the model's own package, which reaches the model; a binding in
        // another package could not name it.
        compileToClasspath(classes, ShopSources.COUNTRY_ENTITY,
                notPublic(ShopSources.COUNTRY_VIEW, "public record CountryView"));
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, with(ENTITIES, ShopSources.INVOICE_STATUS,
                CUSTOMER_VIEW, notPublic(ShopSources.INVOICE_VIEW, "public record InvoiceView"),
                binding(BIND_CUSTOMER)));

        String generated = " is not public, but QCustomerView is generated in persistence; make it public";
        assertThat(errors(compilation)).contains(
                "MQ3024: CustomerView.invoices: its @Child model InvoiceView" + generated,
                "MQ3024: CustomerView.country: its @Join model CountryView" + generated);
        assertThat(processor.originating()).doesNotContainKey("persistence.QCustomerView");
    }

    /** {@code generated}, a QModel of package {@code persistence}, as if generated in {@code shop}. */
    private static String inShop(String generated) {
        return generated.replace("package persistence;", "package shop;").replaceAll("import shop\\.\\w+;\n", "");
    }

    /** {@code sources} followed by {@code more}. */
    private static JavaFileObject[] with(JavaFileObject[] sources, JavaFileObject... more) {
        var all = new JavaFileObject[sources.length + more.length];
        System.arraycopy(sources, 0, all, 0, sources.length);
        System.arraycopy(more, 0, all, sources.length, more.length);
        return all;
    }

    /** {@code persistence.Models} carrying {@code annotations}, which see every type of {@code shop}. */
    private static JavaFileObject binding(String annotations) {
        return source("persistence.Models", """
                package persistence;

                import com.rey.modelquery.annotations.BindQueryModel;
                import shop.*;

                %sfinal class Models {}
                """.formatted(annotations));
    }

    /** {@code model} with {@code declaration} written without its {@code public}. */
    private static JavaFileObject notPublic(JavaFileObject model, String declaration) {
        String text = text(model);
        assertThat(text).contains(declaration);
        return source(model.getName().replaceAll("^/|\\.java$", "").replace('/', '.'),
                text.replace(declaration, declaration.substring("public ".length())));
    }

    /** {@code model} with its {@code @QueryModel} and the import of it removed. */
    private static JavaFileObject unannotated(JavaFileObject model) {
        String bare = text(model).replace("import com.rey.modelquery.annotations.QueryModel;\n", "")
                .replaceAll("@QueryModel\\([^)]*\\)\n", "");
        assertThat(bare).doesNotContain("QueryModel");
        return source(model.getName().replaceAll("^/|\\.java$", "").replace('/', '.'), bare);
    }

    private static String text(JavaFileObject model) {
        try {
            return model.getCharContent(false).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
