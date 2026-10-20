package com.rey.modelquery.processor;

import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Inheritance declaration checks (R-PROC-41..43, D-154); generated expressions are covered by AC-GEN-34..35. */
class TreatModelDiagnosticsTest {

    private static final JavaFileObject ENTITIES = source("treat.Payment", """
            package treat;
            import jakarta.persistence.*;
            @Entity public class Payment {
                @Id Long id;
                String reference;
                @ManyToOne Payment payment;
                @ManyToOne CardPayment card;
                @ManyToOne BankPayment bank;
            }
            @Entity class CardPayment extends Payment {
                String last4;
                Integer attempts;
                @Embedded Details details;
                @ManyToOne Customer issuer;
            }
            @Entity class PremiumCardPayment extends CardPayment { String reward; }
            @Entity class BankPayment extends Payment { String iban; }
            class PlainPayment extends Payment { String last4; }
            @MappedSuperclass class MappedPayment extends Payment { String last4; }
            @Embeddable class EmbeddedPayment extends Payment { String last4; }
            @Entity class Customer {
                @Id Long id;
                String name;
                @ManyToOne BankPayment bank;
            }
            @Embeddable class Details { String city; }
            @Entity @Access(AccessType.PROPERTY) class PropertyCard extends Payment {
                public String getLabel() { return null; }
            }
            """);

    private static final String CUSTOMER_VIEW = """
            @QueryModel(root = Customer.class)
            record CustomerView(@PrimaryKey Long id, String name) {}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"PlainPayment", "MappedPayment", "EmbeddedPayment", "Customer", "Payment", "int"})
    void ac_proc_42_treat_requires_a_strict_entity_subtype(String target) {
        Compilation compilation = view("@Column(treat = " + target + ".class) String last4");

        assertCode(compilation, DiagnosticCode.MQ3042);
        assertThat(errors(compilation)).anySatisfy(message -> assertThat(message)
                .contains("View.last4", target, "strict @Entity subtype of Payment"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@Column(treat = CardPayment.class) String reference",
            "@Join(treat = CardPayment.class) Optional<View> payment",
            "@Column(treat = CardPayment.class) @PrimaryKey Long id",
            "@Column(treat = CardPayment.class) @Discriminator(\"CARD\") String last4"})
    void ac_proc_42_redundant_treat_keys_and_discriminators_are_rejected(String field) {
        assertCode(view(field), DiagnosticCode.MQ3042);
    }

    @Test
    void ac_proc_42_a_child_parent_key_cannot_be_treated() {
        Compilation compilation = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                public record View(@PrimaryKey Long id, @Column(treat = CardPayment.class) String last4,
                        @Child(key = "last4", foreignKey = "name") List<CustomerView> customers) {}
                """ + CUSTOMER_VIEW));

        assertCode(compilation, DiagnosticCode.MQ3042);
        assertThat(errors(compilation)).anySatisfy(message -> assertThat(message).contains("View.last4", "key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UpdateModel", "InsertModel"})
    void ac_proc_42_write_models_reject_treat_on_every_annotation(String annotation) {
        Compilation compilation = compile(ENTITIES, model("""
                @%s(root = Payment.class)
                @FilterColumn(name = "LAST4", path = "last4", treat = CardPayment.class)
                public record View(@PrimaryKey Long id, @Column(treat = CardPayment.class) String last4,
                        @Join(treat = CardPayment.class) Optional<CustomerView> issuer) {}
                %s
                """.formatted(annotation, CUSTOMER_VIEW)));

        assertCode(compilation, DiagnosticCode.MQ3042);
        assertThat(errors(compilation)).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"generateChanges", "generateInserts"})
    void ac_proc_42_generated_writes_cannot_drop_a_treated_or_subtype_field(String flag) {
        Compilation treated = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class, %s = true)
                @FilterColumn(name = "LAST4_FILTER", path = "last4", treat = CardPayment.class)
                public record View(@PrimaryKey Long id,
                        @ExcludeFromInserts @Column(treat = CardPayment.class) String last4,
                        @Join(treat = CardPayment.class) Optional<CustomerView> issuer) {}
                %s
                """.formatted(flag, CUSTOMER_VIEW)));
        Compilation subtype = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class, %s = true)
                public record View(@PrimaryKey Long id, @Subtype Class<? extends Payment> kind) {}
                """.formatted(flag)));

        assertCode(treated, DiagnosticCode.MQ3042);
        assertThat(errors(treated)).hasSize(3);
        assertCode(subtype, DiagnosticCode.MQ3042);
    }

    @Test
    void ac_proc_42_source_rooted_models_reject_every_treat_member() {
        Compilation compilation = compile(ENTITIES, model("""
                @SourceModel(root = Payment.class)
                record PaymentSource(@PrimaryKey Long id) {}
                @QueryModel(root = PaymentSource.class)
                @FilterColumn(name = "LAST4_FILTER", path = "last4", treat = CardPayment.class)
                public record View(@PrimaryKey Long id, @Column(treat = CardPayment.class) String last4,
                        @Join(treat = CardPayment.class) Optional<CustomerView> issuer) {}
                """ + CUSTOMER_VIEW));

        assertCode(compilation, DiagnosticCode.MQ3042);
        assertThat(errors(compilation)).hasSize(3);
    }

    @Test
    void ac_proc_42_filter_treat_is_not_redundant_and_void_means_none() {
        assertCode(compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                @FilterColumn(name = "REF", path = "reference", treat = CardPayment.class)
                public record View(@PrimaryKey Long id) {}
                """)), DiagnosticCode.MQ3042);
        Compilation compilation = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                @FilterColumn(name = "REF", path = "reference", treat = void.class)
                public record View(@PrimaryKey Long id, @Column(treat = void.class) String reference,
                        @Join(attribute = "payment", treat = void.class) Optional<Nested> nested) {}
                @QueryModel(root = Payment.class) record Nested(@PrimaryKey Long id) {}
                """));

        assertThat(errors(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"String", "Class", "Class<?>", "Class<Payment>", "Class<? extends CardPayment>",
            "Class<? super Payment>"})
    void ac_proc_43_subtype_requires_class_extends_the_root(String type) {
        assertCode(view("@Subtype " + type + " kind"), DiagnosticCode.MQ3043);
    }

    @ParameterizedTest
    @ValueSource(strings = {"@Column", "@Computed", "@Aggregate(fn = AggregateFunction.COUNT)", "@PrimaryKey"})
    void ac_proc_43_subtype_rejects_combined_annotations(String annotation) {
        assertCode(view("@Subtype " + annotation + " Class<? extends Payment> kind"), DiagnosticCode.MQ3043);
    }

    @Test
    void ac_proc_43_a_second_subtype_names_the_first_field() {
        Compilation compilation = view("@Subtype Class<? extends Payment> kind, "
                + "@Subtype Class<? extends Payment> other");

        assertCode(compilation, DiagnosticCode.MQ3043);
        assertThat(errors(compilation)).singleElement().asString().contains("View.other", "first is kind");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UpdateModel", "InsertModel"})
    void ac_proc_43_subtype_is_not_a_write_field(String annotation) {
        assertCode(compile(ENTITIES, model("""
                @%s(root = Payment.class)
                public record View(@PrimaryKey Long id, @Subtype Class<? extends Payment> kind) {}
                """.formatted(annotation))), DiagnosticCode.MQ3043);
    }

    @Test
    void ac_proc_43_subtype_is_not_a_source_rooted_field() {
        assertCode(compile(ENTITIES, model("""
                @SourceModel(root = Payment.class) record PaymentSource(@PrimaryKey Long id) {}
                @QueryModel(root = PaymentSource.class)
                public record View(@PrimaryKey Long id, @Subtype Class<? extends PaymentSource> kind) {}
                """)), DiagnosticCode.MQ3043);
    }

    @Test
    void ac_proc_42_a_source_model_over_a_polymorphic_root_keeps_its_treat_checks() {
        Compilation compilation = compile(ENTITIES, model("""
                @SourceModel(root = Payment.class)
                @FilterColumn(name = "NAME_FILTER", path = "name", treat = Customer.class)
                public record View(@PrimaryKey Long id, @Column(treat = Payment.class) String reference,
                        @Column(treat = BankPayment.class) String iban) {}
                """));

        assertCode(compilation, DiagnosticCode.MQ3042);
        assertThat(errors(compilation)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Class<? extends CardPayment>", "Class<?>"})
    void ac_proc_43_a_source_model_subtype_must_be_class_extends_its_root(String type) {
        assertCode(compile(ENTITIES, model("""
                @SourceModel(root = Payment.class)
                public record View(@PrimaryKey Long id, @Subtype %s kind) {}
                """.formatted(type))), DiagnosticCode.MQ3043);
    }

    @Test
    void ac_proc_43_grouped_subtype_compiles() {
        Compilation compilation = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                public record View(@GroupBy @Subtype Class<? extends Payment> kind,
                        @Aggregate(fn = AggregateFunction.COUNT) Long count) {}
                """));

        assertThat(errors(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CardPayment", "Payment"})
    void ac_proc_44_nested_roots_accept_subtypes_and_supertypes(String nestedRoot) {
        String association = nestedRoot.equals("CardPayment") ? "payment" : "card";
        Compilation compilation = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                public record View(@PrimaryKey Long id,
                        @Join(attribute = "%s") Optional<Nested> nested) {}
                @QueryModel(root = %s.class) record Nested(@PrimaryKey Long id) {}
                """.formatted(association, nestedRoot)));

        assertThat(errors(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @Test
    void ac_proc_44_an_unrelated_nested_root_is_rejected() {
        Compilation compilation = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                public record View(@PrimaryKey Long id, @Join(attribute = "payment") Optional<CustomerView> customer) {}
                """ + CUSTOMER_VIEW));

        assertCode(compilation, DiagnosticCode.MQ3003);
        assertThat(errors(compilation)).singleElement().asString().contains("Customer", "Payment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"column", "filter", "join"})
    void ac_proc_44_sibling_treat_targets_name_the_field_on_every_annotation(String annotation) {
        Compilation compilation = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                public record View(@PrimaryKey Long id, @Join(attribute = "bank") Optional<Nested> nested) {}
                """ + nested(annotation) + CUSTOMER_VIEW));

        assertCode(compilation, DiagnosticCode.MQ3003);
        assertThat(errors(compilation)).anySatisfy(message -> assertThat(message)
                .contains("Nested", annotation.equals("filter") ? "LAST4" : "value", "CardPayment", "BankPayment"));
    }

    @Test
    void ac_proc_44_sibling_treats_are_checked_below_each_join(@TempDir Path classes) {
        ProcessorHarness.compileToClasspath(classes, ENTITIES);
        Compilation compilation = ProcessorHarness.compileAgainst(classes, new ModelQueryProcessor(), model("""
                @QueryModel(root = CardPayment.class)
                public record View(@PrimaryKey Long id, @Join(attribute = "issuer") Optional<CustomerView> customer) {}
                @QueryModel(root = Customer.class)
                record CustomerView(@PrimaryKey Long id, @Join(attribute = "bank") Optional<Nested> bank) {}
                """ + nested("column")));

        assertCode(compilation, DiagnosticCode.MQ3003);
        assertThat(errors(compilation)).anySatisfy(message -> assertThat(message)
                .contains("View.customer", "Nested.value", "CardPayment", "BankPayment"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"column", "filter", "join"})
    void ac_proc_45_missing_attributes_name_the_subtype(String annotation) {
        String declaration = switch (annotation) {
            case "filter" -> """
                    @QueryModel(root = Payment.class)
                    @FilterColumn(name = "MISSING", path = "missing", treat = CardPayment.class)
                    public record View(@PrimaryKey Long id) {}
                    """;
            case "join" -> """
                    @QueryModel(root = Payment.class)
                    public record View(@PrimaryKey Long id,
                            @Join(treat = CardPayment.class) Optional<CustomerView> missing) {}
                    """ + CUSTOMER_VIEW;
            default -> """
                    @QueryModel(root = Payment.class)
                    public record View(@PrimaryKey Long id, @Column(treat = CardPayment.class) String missing) {}
                    """;
        };
        Compilation compilation = compile(ENTITIES, model(declaration));

        assertCode(compilation, DiagnosticCode.MQ3001);
        assertThat(errors(compilation)).anySatisfy(message -> assertThat(message)
                .contains("no attribute 'missing' on CardPayment"));
    }

    @Test
    void ac_proc_45_treated_filter_navigation_keeps_its_path_diagnostic() {
        Compilation compilation = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                @FilterColumn(name = "BROKEN", path = "last4.city", treat = CardPayment.class)
                public record View(@PrimaryKey Long id) {}
                """));

        assertCode(compilation, DiagnosticCode.MQ3011);
    }

    @Test
    void ac_proc_45_treated_filter_typo_past_the_first_segment_is_a_path_diagnostic() {
        Compilation compilation = compile(ENTITIES, model("""
                @QueryModel(root = Payment.class)
                @FilterColumn(name = "BROKEN", path = "issuer.nmae", treat = CardPayment.class)
                public record View(@PrimaryKey Long id) {}
                """));

        assertCode(compilation, DiagnosticCode.MQ3011);
    }

    @Test
    void ac_proc_45_primitive_treated_record_components_are_rejected() {
        assertCode(view("@Column(treat = CardPayment.class) int attempts"), DiagnosticCode.MQ3009);
    }

    @Test
    void ac_proc_45_subtype_hierarchies_embedded_paths_and_property_access_resolve() {
        Compilation compilation = view("@Column(treat = PremiumCardPayment.class) String last4, "
                + "@Column(attribute = \"details.city\", treat = CardPayment.class) String city, "
                + "@Column(treat = PropertyCard.class) String label");

        assertThat(errors(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    private static String nested(String annotation) {
        String filter = annotation.equals("filter")
                ? "@FilterColumn(name = \"LAST4\", path = \"last4\", treat = CardPayment.class)" : "";
        String field = switch (annotation) {
            case "filter" -> "";
            case "join" -> ", @Join(attribute = \"issuer\", treat = CardPayment.class) Optional<CustomerView> value";
            default -> ", @Column(attribute = \"last4\", treat = CardPayment.class) String value";
        };
        return "@QueryModel(root = Payment.class) " + filter + " record Nested(@PrimaryKey Long id" + field + ") {}";
    }

    private static Compilation view(String fields) {
        return compile(ENTITIES, model("@QueryModel(root = Payment.class) public record View("
                + "@PrimaryKey @Column(attribute = \"id\") Long paymentId, " + fields + ") {}"));
    }

    private static JavaFileObject model(String declaration) {
        return source("treat.View", """
                package treat;
                import com.rey.modelquery.annotations.*;
                import java.util.List;
                import java.util.Optional;
                """ + declaration);
    }

    private static void assertCode(Compilation compilation, DiagnosticCode... codes) {
        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation)).extracting(message -> message.substring(0, message.indexOf(':')))
                .containsOnly(java.util.Arrays.stream(codes).map(DiagnosticCode::code).toArray(String[]::new));
    }
}
