package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.testing.compile.Compilation;
import javax.lang.model.element.ElementKind;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** The checks of {@code processor/32} §1 that columns and joins can fail, and how they are reported. */
class DiagnosticsTest {

    private static final String IMPORTS = """
            package models;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.processor.fixture.OrderEntity;
            import java.math.BigDecimal;

            @QueryModel(root = OrderEntity.class)
            """;

    /** {@code models.<name>}, declared by {@code declaration} under the imports and a {@code @QueryModel}. */
    private static JavaFileObject model(String name, String declaration) {
        return source("models." + name, IMPORTS + declaration);
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }

    /** Three problems that do not depend on each other: an unknown attribute, a wrong type and no key. */
    private static final JavaFileObject THREE_ERRORS = model("OrderView", """
            public class OrderView {
                private Long totl;
                private Integer total;
                private String status;
            }
            """);

    @Test
    void ac_diag_02_three_independent_errors_are_reported_in_one_compilation() {
        Compilation compilation = compile(THREE_ERRORS);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message(DiagnosticCode.MQ3004, "OrderView: no @PrimaryKey; paging, export and @Join presence need one"),
                message(DiagnosticCode.MQ3001, "OrderView.totl: no attribute 'totl' on OrderEntity"),
                message(DiagnosticCode.MQ3002,
                        "OrderView.total: model type Integer, entity attribute type BigDecimal"));
    }

    @Test
    void ac_diag_03_class_diagnostics_are_attached_to_the_annotated_element() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(THREE_ERRORS);

        assertThat(processor.messages())
                .extracting(
                        ProcessorHarness.Recording.Message::kind,
                        message -> message.text().substring(0, 6),
                        message -> message.element().getKind(),
                        message -> message.element().toString())
                .containsExactlyInAnyOrder(
                        tuple(Diagnostic.Kind.ERROR, DiagnosticCode.MQ3004.code(), ElementKind.CLASS,
                                "models.OrderView"),
                        tuple(Diagnostic.Kind.ERROR, DiagnosticCode.MQ3001.code(), ElementKind.FIELD, "totl"),
                        tuple(Diagnostic.Kind.ERROR, DiagnosticCode.MQ3002.code(), ElementKind.FIELD, "total"));
        // The element is what places the error in the model's source, on the field's own line.
        assertThat(compilation).hadErrorContaining(DiagnosticCode.MQ3001.code())
                .inFile(THREE_ERRORS).onLineContaining("private Long totl;");
        assertThat(compilation).hadErrorContaining(DiagnosticCode.MQ3002.code())
                .inFile(THREE_ERRORS).onLineContaining("private Integer total;");
        assertThat(compilation).hadErrorContaining(DiagnosticCode.MQ3004.code())
                .inFile(THREE_ERRORS).onLineContaining("public class OrderView");
    }

    @Test
    void ac_diag_03_record_diagnostics_are_attached_to_the_component() {
        var processor = new ProcessorHarness.Recording();
        JavaFileObject model = model("OrderRow", """
                public record OrderRow(
                        @PrimaryKey Long id,
                        String totl) {}
                """);

        Compilation compilation = processor.compile(model);

        assertThat(processor.messages()).singleElement().satisfies(message -> {
            assertThat(message.kind()).isEqualTo(Diagnostic.Kind.ERROR);
            assertThat(message.element().getSimpleName()).hasToString("totl");
            assertThat(message.element().getEnclosingElement()).hasToString("models.OrderRow");
        });
        assertThat(compilation).hadErrorContaining(DiagnosticCode.MQ3001.code())
                .inFile(model).onLineContaining("String totl");
    }

    @Test
    void ac_diag_04_a_model_with_an_error_produces_no_qmodel_file() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(
                THREE_ERRORS,
                model("OrderRow", "public record OrderRow(@PrimaryKey Long id, String status, String missing) {}"),
                model("GoodRow", "public record GoodRow(@PrimaryKey Long id, String status) {}"));

        assertThat(compilation).failed();
        // One wrong column is enough to hold back the whole file; a sound model beside it is still written.
        assertThat(processor.originating()).containsOnlyKeys("models.QGoodRow");
    }

    @Test
    void ac_diag_01_mq3001_unknown_attribute_names_the_type_the_lookup_failed_on() {
        Compilation compilation = compile(model("OrderRow", """
                public record OrderRow(
                        @PrimaryKey Long id,
                        String totl,
                        @Column(attribute = "address.cty") String city,
                        @Column(attribute = "status.length") Integer statusLength) {}
                """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3001, "OrderRow.totl: no attribute 'totl' on OrderEntity"),
                message(DiagnosticCode.MQ3001, "OrderRow.city: no attribute 'cty' on Address"),
                message(DiagnosticCode.MQ3001, "OrderRow.statusLength: 'status' on OrderEntity is not an embedded "
                        + "value, so it has no attribute 'length'"));
    }

    @Test
    void ac_diag_01_mq3002_model_type_differs_from_the_entity_attribute_type() {
        Compilation compilation = compile(model("OrderView", """
                public class OrderView {
                    @PrimaryKey
                    private Integer id;
                    // A supertype is a mismatch too: the engine compares the types for identity (MQ1001).
                    private Number total;
                    // A primitive and its wrapper are one type to a column.
                    private Integer quantity;
                    private Boolean paid;
                    private java.sql.Date createdAt;
                    private String address;
                }
                """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3002, "OrderView.id: model type Integer, entity attribute type Long"),
                message(DiagnosticCode.MQ3002, "OrderView.total: model type Number, entity attribute type BigDecimal"),
                message(DiagnosticCode.MQ3002, "OrderView.createdAt: model type Date, entity attribute type Instant"),
                message(DiagnosticCode.MQ3002, "OrderView.address: model type String, entity attribute type Address"));
    }

    @Test
    void ac_diag_01_mq3004_model_without_a_primary_key() {
        Compilation compilation = compile(model("OrderRow", "public record OrderRow(Long id, String status) {}"));

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3004,
                "OrderRow: no @PrimaryKey; paging, export and @Join presence need one"));
    }

    @Test
    void ac_diag_01_mq3008_class_without_a_visible_no_arg_constructor() {
        Compilation compilation = compile(
                model("Args", """
                        public class Args {
                            @PrimaryKey
                            private Long id;

                            public Args(Long id) {
                                this.id = id;
                            }
                        }
                        """),
                model("Hidden", """
                        public class Hidden {
                            @PrimaryKey
                            private Long id;

                            private Hidden() {}
                        }
                        """),
                // Package-private is visible from the QModel, which is generated into the model's package.
                model("Visible", """
                        public class Visible {
                            @PrimaryKey
                            private Long id;

                            Visible() {}

                            void setId(Long id) {
                                this.id = id;
                            }
                        }
                        """));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message(DiagnosticCode.MQ3008, "Args: needs a no-arg constructor for setter mapping"),
                message(DiagnosticCode.MQ3008, "Hidden: needs a no-arg constructor for setter mapping"));
    }

    @Test
    void ac_diag_01_mq3009_primitive_record_component_that_is_not_a_key() {
        Compilation compilation = compile(model("OrderRow", """
                public record OrderRow(@PrimaryKey long id, int quantity, boolean paid) {}
                """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3009,
                        "OrderRow.quantity: primitive components can't be null when not selected; use Integer"),
                message(DiagnosticCode.MQ3009,
                        "OrderRow.paid: primitive components can't be null when not selected; use Boolean"));
    }

    @Test
    void ac_diag_01_mq3010_generic_record() {
        Compilation compilation = compile(model("OrderRow", """
                public record OrderRow<T>(@PrimaryKey Long id, String status) {}
                """));

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3010,
                "OrderRow: a generic record can't be mapped through its canonical constructor; remove the type "
                        + "parameters"));
    }

    @Test
    void ac_diag_01_mq3015_constant_name_taken_by_another_field_or_reserved() {
        Compilation compilation = compile(model("OrderView", """
                public class OrderView {
                    @PrimaryKey
                    private Long id;
                    private String status;
                    @Column(attribute = "status")
                    private String STATUS;
                    @Column(attribute = "status")
                    private String all;
                    @Column(attribute = "status")
                    private String groupKeys;
                }
                """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3015, "OrderView.STATUS: constant STATUS is also generated for field "
                        + "'status'; rename the field"),
                message(DiagnosticCode.MQ3015,
                        "OrderView.all: constant ALL is reserved by the generated class; rename the field"),
                message(DiagnosticCode.MQ3015, "OrderView.groupKeys: constant GROUP_KEYS is reserved by the "
                        + "generated class; rename the field"));
    }

    private static final String JOIN_IMPORTS = """
            package models;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.core.ColumnConverter;
            import com.rey.modelquery.processor.fixture.*;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;
            import java.util.List;
            import java.util.Optional;

            """;

    /** {@code models.<name>}, declared in full by {@code declaration}: its own {@code @QueryModel}, if any. */
    private static JavaFileObject type(String name, String declaration) {
        return source("models." + name, JOIN_IMPORTS + declaration);
    }

    private static final JavaFileObject CUSTOMER_ROW = type("CustomerRow", """
            @QueryModel(root = CustomerEntity.class)
            public record CustomerRow(@PrimaryKey Long id, String name) {}
            """);

    @Test
    void ac_diag_01_mq3003_join_attribute_is_not_a_to_one_association_to_the_nested_root() {
        Compilation compilation = compile(CUSTOMER_ROW,
                type("ItemRow", """
                        @QueryModel(root = ItemEntity.class)
                        public record ItemRow(@PrimaryKey Long id) {}
                        """),
                type("OrderView", """
                        @QueryModel(root = OrderEntity.class)
                        public record OrderView(
                                @PrimaryKey Long id,
                                @Join Optional<CustomerRow> items,
                                @Join Optional<CustomerRow> status,
                                @Join(attribute = "customer") Optional<ItemRow> buyer,
                                @Join(attribute = "customer.name") Optional<CustomerRow> named) {}
                        """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3003, "OrderView.items: 'items' on OrderEntity is a collection, which a "
                        + "@Join can't select; filter on it with Filters.exists"),
                message(DiagnosticCode.MQ3003, "OrderView.status: 'status' on OrderEntity is not a to-one association"),
                message(DiagnosticCode.MQ3003,
                        "OrderView.buyer: ItemRow.root is ItemEntity, association targets CustomerEntity"),
                message(DiagnosticCode.MQ3003, "OrderView.named: 'customer.name' is a path; @Join takes a to-one "
                        + "association of OrderEntity itself"));
    }

    @Test
    void ac_diag_01_mq3005_join_field_is_not_an_optional_of_a_query_model() {
        Compilation compilation = compile(CUSTOMER_ROW, type("OrderView", """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(
                        @PrimaryKey Long id,
                        @Join CustomerRow customer,
                        @Join Optional<String> payer,
                        @Join(attribute = "customer") List<CustomerRow> many) {}
                """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3005,
                        "OrderView.customer: @Join field must be Optional<CustomerRow>, found CustomerRow"),
                message(DiagnosticCode.MQ3005, "OrderView.payer: String is not a @QueryModel"),
                message(DiagnosticCode.MQ3005, "OrderView.many: @Join field must be Optional<X> of a @QueryModel X, "
                        + "found List<CustomerRow>"));
    }

    @Test
    void ac_diag_01_mq3006_nested_model_without_a_primary_key() {
        Compilation compilation = compile(
                type("NoKeyRow", """
                        @QueryModel(root = CustomerEntity.class)
                        public record NoKeyRow(Long id, String name) {}
                        """),
                type("OrderView", """
                        @QueryModel(root = OrderEntity.class)
                        public record OrderView(@PrimaryKey Long id, @Join Optional<NoKeyRow> customer) {}
                        """));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message(DiagnosticCode.MQ3004, "NoKeyRow: no @PrimaryKey; paging, export and @Join presence need one"),
                message(DiagnosticCode.MQ3006,
                        "OrderView.customer: NoKeyRow needs a @PrimaryKey to be used in @Join"));
    }

    @Test
    void ac_diag_01_mq3007_join_cycle_between_nested_models() {
        Compilation compilation = compile(
                type("AccountEntity", """
                        @Entity
                        public class AccountEntity {
                            @Id
                            Long id;
                            @ManyToOne
                            ProfileEntity profile;
                        }
                        """),
                type("ProfileEntity", """
                        @Entity
                        public class ProfileEntity {
                            @Id
                            Long id;
                            @ManyToOne
                            AccountEntity account;
                        }
                        """),
                type("AccountView", """
                        @QueryModel(root = AccountEntity.class)
                        public record AccountView(@PrimaryKey Long id, @Join Optional<ProfileView> profile) {}
                        """),
                type("ProfileView", """
                        @QueryModel(root = ProfileEntity.class)
                        public record ProfileView(@PrimaryKey Long id, @Join Optional<AccountView> account) {}
                        """),
                // Not on the cycle itself, but its join leads into it.
                type("EntryView", """
                        @QueryModel(root = AccountEntity.class)
                        public record EntryView(
                                @PrimaryKey Long id, @Join(attribute = "profile") Optional<ProfileView> owner) {}
                        """));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                message(DiagnosticCode.MQ3007, "AccountView.profile → ProfileView.account → AccountView"),
                message(DiagnosticCode.MQ3007, "ProfileView.account → AccountView.profile → ProfileView"),
                message(DiagnosticCode.MQ3007,
                        "EntryView.owner → ProfileView.account → AccountView.profile → ProfileView"));
    }

    @Test
    void ac_diag_01_mq3014_converter_does_not_fit_the_column_or_cannot_be_obtained() {
        Compilation compilation = compile(
                type("Status", "public enum Status { NEW }"),
                type("ToInteger", """
                        public class ToInteger implements ColumnConverter<Status, Integer> {
                            public Status toModel(Integer attribute) {
                                return Status.NEW;
                            }

                            public Integer toAttribute(Status model) {
                                return 0;
                            }
                        }
                        """),
                type("Hidden", """
                        public class Hidden implements ColumnConverter<Status, String> {
                            static final Hidden INSTANCE = new Hidden();

                            private Hidden() {}

                            public Status toModel(String attribute) {
                                return Status.NEW;
                            }

                            public String toAttribute(Status model) {
                                return "NEW";
                            }
                        }
                        """),
                type("OrderView", """
                        @QueryModel(root = OrderEntity.class)
                        public record OrderView(
                                @PrimaryKey Long id,
                                @Column(converter = ToInteger.class) Status status,
                                @Column(attribute = "status", converter = ToInteger.class) String text,
                                @Column(attribute = "status", converter = String.class) Status plain,
                                @Column(attribute = "status", converter = Hidden.class) Status hidden) {}
                        """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3014,
                        "OrderView.status: ToInteger converts Status to Integer, entity attribute type String"),
                message(DiagnosticCode.MQ3014, "OrderView.text: ToInteger converts Status to Integer, model type "
                        + "String, entity attribute type String"),
                message(DiagnosticCode.MQ3014, "OrderView.plain: String is not a ColumnConverter<Status, String>"),
                message(DiagnosticCode.MQ3014, "OrderView.hidden: Hidden needs a public static INSTANCE or a no-arg "
                        + "constructor visible to QOrderView"));
    }

    @Test
    void ac_diag_01_mq3015_constant_of_a_join_taken_by_a_field_another_join_or_reserved() {
        Compilation compilation = compile(CUSTOMER_ROW, type("OrderView", """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(
                        @PrimaryKey Long id,
                        @Column(attribute = "status") String customerName,
                        @Join Optional<CustomerRow> customer,
                        @Join(prefix = "CUSTOMER") Optional<CustomerRow> payer,
                        @Join(attribute = "payer") Optional<CustomerRow> key) {}
                """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3015, "OrderView.payer: constant CUSTOMER_TABLE is also generated for the "
                        + "join customer; rename the field or set @Join(prefix)"),
                message(DiagnosticCode.MQ3015, "OrderView.key: constant KEY is reserved by the generated class; "
                        + "rename the field or set @Join(prefix)"),
                message(DiagnosticCode.MQ3015, "OrderView.customerName: constant CUSTOMER_NAME is also generated for "
                        + "customer.name; rename the field or set @Join(prefix)"));
    }

    @Test
    void ac_diag_01_mq3015_join_prefix_that_is_no_identifier() {
        Compilation compilation = compile(CUSTOMER_ROW, type("OrderView", """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(
                        @PrimaryKey Long id,
                        @Join(prefix = "my-customer") Optional<CustomerRow> customer) {}
                """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3015, "OrderView.customer: @Join(prefix = \"my-customer\") can't start a "
                        + "constant's name; use a Java identifier such as CUSTOMER"));
    }
}
