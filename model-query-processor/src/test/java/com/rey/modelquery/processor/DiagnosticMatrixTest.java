package com.rey.modelquery.processor;

import static com.google.testing.compile.Compiler.javac;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The diagnostic matrix (R-DIAG-05): every live code of {@code processor/32} §1, with its message text, for a class
 * and a record model where the check applies to both, compiled with Lombok's processor off and on.
 *
 * <p>Exclusions: {@code MQ3008} needs a class (a record's canonical constructor is never absent); {@code MQ3009} and
 * {@code MQ3010} are record checks (a class has no components, and a generic class is allowed). Every other code is
 * raised for both shapes. Lombok adds accessors and constructors to class models only, so on a record its runs
 * repeat the plain ones with Lombok's processor in the chain. The update-model codes {@code MQ3301}..{@code MQ3307}
 * raise on an {@code @UpdateModel}, and {@code MQ3306} on a {@code generateChanges} query model too. The
 * {@code @Child} codes {@code MQ3401}..{@code MQ3406} raise on a query model with a child over another root.
 * {@code MQ3017} raises on a {@code root} of {@code int.class}, since a missing class adds javac's own errors;
 * {@link RoundDeferralTest} covers that one.
 */
class DiagnosticMatrixTest {

    private enum Shape { CLASS, RECORD }

    /** What a case compiles, and the {@code MQ} errors, or the warnings when {@code warning}, that must come out. */
    private record Scenario(List<JavaFileObject> sources, boolean warning, List<String> messages) {}

    private interface Build {
        Scenario apply(Ctx ctx);
    }

    private record Case(String code, Set<Shape> shapes, Build build) {}

    /** One run's shape and Lombok setting, and the builders of model sources under them. */
    private record Ctx(Shape shape, boolean lombok) {

        private static final String IMPORTS = """
                package models;

                import com.rey.modelquery.annotations.Aggregate;
                import com.rey.modelquery.annotations.AggregateFunction;
                import com.rey.modelquery.annotations.Child;
                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.FilterColumn;
                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.Join;
                import com.rey.modelquery.annotations.JoinKind;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import com.rey.modelquery.annotations.UpdateModel;
                import com.rey.modelquery.processor.fixture.CustomerEntity;
                import com.rey.modelquery.processor.fixture.ItemEntity;
                import com.rey.modelquery.processor.fixture.OrderEntity;
                import java.math.BigDecimal;
                import java.util.List;
                import java.util.Optional;

                """;

        /**
         * {@code models.<name>} with {@code annotations} above it and one field or component per {@code fields}
         * entry, each as annotations, then type, then name. A class gets setters, or Lombok's for the run with it.
         */
        JavaFileObject model(String name, String annotations, String... fields) {
            String simple = name.replaceAll("<.*", "");
            var text = new StringBuilder(IMPORTS).append(annotations).append('\n');
            if (shape == Shape.RECORD) {
                text.append("public record ").append(name).append("(\n        ")
                        .append(String.join(",\n        ", fields)).append(") {}\n");
            } else {
                if (lombok) {
                    text.append("@lombok.Getter\n@lombok.Setter\n@lombok.NoArgsConstructor\n");
                }
                text.append("public class ").append(name).append(" {\n");
                for (String field : fields) {
                    text.append("    private ").append(field).append(";\n");
                }
                if (!lombok) {
                    for (String field : fields) {
                        String type = field.substring(0, field.lastIndexOf(' '));
                        type = type.substring(type.lastIndexOf(' ') + 1);
                        String var = field.substring(field.lastIndexOf(' ') + 1);
                        text.append("    public void set").append(Character.toUpperCase(var.charAt(0)))
                                .append(var.substring(1)).append('(').append(type).append(' ').append(var)
                                .append(") { this.").append(var).append(" = ").append(var).append("; }\n");
                    }
                }
                text.append("}\n");
            }
            return source("models." + simple, text.toString());
        }

        JavaFileObject customerView() {
            return model("CustomerView", "@QueryModel(root = CustomerEntity.class)", "@PrimaryKey Long id",
                    "String name");
        }
    }

    private static final String ORDER = "@QueryModel(root = OrderEntity.class)";
    private static final String CUSTOMER = "@QueryModel(root = CustomerEntity.class)";
    private static final String ITEM = "@QueryModel(root = ItemEntity.class)";
    private static final String SALES = "@QueryModel(root = SaleEntity.class)";
    private static final String SINGLE = "@QueryModel(root = SaleEntity.class, singleGroup = true)";
    private static final String ID = "@PrimaryKey Long id";
    private static final String COUNT = "@Aggregate(fn = AggregateFunction.COUNT)";

    private static final JavaFileObject SALE_ENTITY = source("models.SaleEntity", """
            package models;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;

            @Entity
            public class SaleEntity {
                @Id
                Long id;
                String region;
                BigDecimal amount;
                Integer units;
                Double weight;
            }
            """);

    private static final String PATCH = "@UpdateModel(root = TicketEntity.class)";

    /** An entity with what an update model can't write: a version, a read-only column and an inverse to-one. */
    private static final JavaFileObject TICKET_ENTITY = source("models.TicketEntity", """
            package models;

            import com.rey.modelquery.processor.fixture.CustomerEntity;
            import com.rey.modelquery.processor.fixture.ItemEntity;
            import com.rey.modelquery.processor.fixture.OrderEntity;
            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;
            import jakarta.persistence.OneToMany;
            import jakarta.persistence.OneToOne;
            import jakarta.persistence.Version;
            import java.util.List;

            @Entity
            public class TicketEntity {
                @Id
                Long id;
                String code;
                @Version
                Long version;
                @Column(updatable = false)
                String createdBy;
                @ManyToOne
                CustomerEntity customer;
                @OneToOne(mappedBy = "ticket")
                OrderEntity order;
                @OneToMany
                List<ItemEntity> items;
            }
            """);

    private static final Set<Shape> BOTH = Set.of(Shape.CLASS, Shape.RECORD);

    private static Scenario fails(List<JavaFileObject> sources, String... messages) {
        return new Scenario(sources, false, List.of(messages));
    }

    private static Scenario fails(JavaFileObject source, String... messages) {
        return fails(List.of(source), messages);
    }

    private static Case of(String code, Build build) {
        return new Case(code, BOTH, build);
    }

    private static List<JavaFileObject> with(JavaFileObject first, JavaFileObject... others) {
        var all = new ArrayList<JavaFileObject>(List.of(first));
        all.addAll(List.of(others));
        return all;
    }

    private static final JavaFileObject ACCOUNT_ENTITY = source("models.AccountEntity", """
            package models;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class AccountEntity {
                @Id
                Long id;
                @ManyToOne
                ProfileEntity profile;
            }
            """);

    private static final JavaFileObject PROFILE_ENTITY = source("models.ProfileEntity", """
            package models;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class ProfileEntity {
                @Id
                Long id;
                @ManyToOne
                AccountEntity account;
            }
            """);

    private static final JavaFileObject STATUS = source("models.Status", "package models;\npublic enum Status { NEW }");

    private static final JavaFileObject TO_INTEGER = source("models.ToInteger", """
            package models;

            import com.rey.modelquery.core.ColumnConverter;

            public class ToInteger implements ColumnConverter<Status, Integer> {
                public Status toModel(Integer attribute) {
                    return Status.NEW;
                }

                public Integer toAttribute(Status model) {
                    return 0;
                }
            }
            """);

    private static final List<Case> CASES = List.of(
            of("MQ3001", c -> fails(c.model("OrderView", ORDER, ID, "BigDecimal totl"),
                    "MQ3001: OrderView.totl: no attribute 'totl' on OrderEntity")),
            of("MQ3002", c -> fails(c.model("OrderView", ORDER, ID, "Integer total"),
                    "MQ3002: OrderView.total: model type Integer, entity attribute type BigDecimal")),
            of("MQ3003", c -> fails(
                    with(c.model("ItemView", "@QueryModel(root = ItemEntity.class)", ID),
                            c.model("OrderView", ORDER, ID,
                                    "@Join(attribute = \"customer\") Optional<ItemView> buyer")),
                    "MQ3003: OrderView.buyer: ItemView.root is ItemEntity, association targets CustomerEntity")),
            of("MQ3004", c -> fails(c.model("OrderView", ORDER, "Long id", "String status"),
                    "MQ3004: OrderView: no @PrimaryKey; paging, export and @Join presence need one")),
            of("MQ3005", c -> fails(
                    with(c.customerView(), c.model("OrderView", ORDER, ID, "@Join CustomerView customer")),
                    "MQ3005: OrderView.customer: @Join field must be Optional<CustomerView>, found CustomerView")),
            of("MQ3006", c -> fails(
                    with(c.model("NoKeyView", "@QueryModel(root = CustomerEntity.class)", "Long id", "String name"),
                            c.model("OrderView", ORDER, ID, "@Join Optional<NoKeyView> customer")),
                    "MQ3004: NoKeyView: no @PrimaryKey; paging, export and @Join presence need one",
                    "MQ3006: OrderView.customer: NoKeyView needs a @PrimaryKey to be used in @Join")),
            of("MQ3007", c -> fails(
                    with(ACCOUNT_ENTITY, PROFILE_ENTITY,
                            c.model("AccountView", "@QueryModel(root = AccountEntity.class)", ID,
                                    "@Join Optional<ProfileView> profile"),
                            c.model("ProfileView", "@QueryModel(root = ProfileEntity.class)", ID,
                                    "@Join Optional<AccountView> account")),
                    "MQ3007: AccountView.profile → ProfileView.account → AccountView",
                    "MQ3007: ProfileView.account → AccountView.profile → ProfileView")),
            new Case("MQ3008", Set.of(Shape.CLASS), c -> fails(source("models.OrderView", Ctx.IMPORTS + (c.lombok()
                    ? "@lombok.Getter\n@lombok.Setter\n@lombok.AllArgsConstructor\n" : "") + ORDER + """

                    public class OrderView {
                        @PrimaryKey
                        private Long id;
                    """ + (c.lombok() ? "" : "\n    public OrderView(Long id) {\n        this.id = id;\n    }\n")
                    + "}\n"),
                    "MQ3008: OrderView: needs a no-arg constructor for setter mapping")),
            new Case("MQ3009", Set.of(Shape.RECORD), c -> fails(
                    c.model("OrderView", ORDER, "@PrimaryKey long id", "int quantity"),
                    "MQ3009: OrderView.quantity: primitive components can't be null when not selected; use Integer")),
            new Case("MQ3010", Set.of(Shape.RECORD), c -> fails(
                    c.model("OrderView<T>", ORDER, ID, "String status"),
                    "MQ3010: OrderView: a generic record can't be mapped through its canonical constructor; "
                            + "remove the type parameters")),
            of("MQ3011", c -> fails(
                    c.model("OrderView",
                            ORDER + "\n@FilterColumn(name = \"CUSTOMER_COUNTRY\", path = \"customer.contry\")", ID),
                    "MQ3011: OrderView @FilterColumn(CUSTOMER_COUNTRY): no attribute 'contry' on CustomerEntity")),
            of("MQ3012", c -> fails(
                    c.model("OrderView", ORDER
                            + "\n@FilterColumn(name = \"ITEM_A\", path = \"items.id\", joinType = JoinKind.LEFT, "
                            + "alias = \"itemB\")"
                            + "\n@FilterColumn(name = \"ITEM_B\", path = \"items.id\", joinType = JoinKind.INNER, "
                            + "alias = \"itemB\")", ID),
                    "MQ3012: OrderView @FilterColumn(ITEM_B): alias 'itemB' is INNER here, LEFT on ITEM_A")),
            of("MQ3013", c -> fails(
                    c.model("OrderView", ORDER + "\n@FilterColumn(name = \"STATUS\", path = \"status\")", ID,
                            "String status"),
                    "MQ3013: OrderView @FilterColumn(STATUS): name already used by field 'status'")),
            of("MQ3014", c -> fails(
                    with(STATUS, TO_INTEGER,
                            c.model("OrderView", ORDER, ID, "@Column(converter = ToInteger.class) Status status")),
                    "MQ3014: OrderView.status: ToInteger converts Status to Integer, entity attribute type "
                            + "String")),
            of("MQ3015", c -> fails(
                    with(c.customerView(), c.model("OrderView", ORDER, ID,
                            "@Column(attribute = \"status\") String customerName",
                            "@Join Optional<CustomerView> customer")),
                    "MQ3015: OrderView.customerName: constant CUSTOMER_NAME is also generated for customer.name; "
                            + "rename the field or set @Join(prefix)")),
            of("MQ3016", c -> new Scenario(
                    List.of(c.model("OrderView", ORDER, ID, "CustomerEntity customer")), true,
                    List.of("MQ3016: OrderView.customer: selects the whole CustomerEntity entity; use @Join with a "
                            + "query model of CustomerEntity to select only its columns"))),
            of("MQ3017", c -> fails(c.model("OrderView", "@QueryModel(root = int.class)", ID, "String status"),
                    "MQ3017: OrderView: root does not name a class, and no annotation processor generated one")),
            of("MQ3201", c -> fails(
                    with(SALE_ENTITY, c.model("SalesSummary", SINGLE,
                            "@Aggregate(fn = AggregateFunction.SUM, attribute = \"weight\") double weight")),
                    "MQ3201: SalesSummary.weight: SUM is NULL over zero rows; use Double, not a primitive")),
            of("MQ3202", c -> fails(
                    with(SALE_ENTITY, c.model("SalesSummary", SINGLE, COUNT + " Integer lines")),
                    "MQ3202: SalesSummary.lines: COUNT returns Long, field is Integer")),
            of("MQ3203", c -> fails(
                    with(SALE_ENTITY, c.model("SalesSummary", SALES, COUNT + " Long lines")),
                    "MQ3203: SalesSummary: has @Aggregate fields but no @GroupBy; add one or set "
                            + "@QueryModel(singleGroup = true)")),
            of("MQ3204", c -> fails(
                    with(SALE_ENTITY, c.model("SalesSummary", SALES, "@GroupBy String region",
                            "@GroupBy " + COUNT + " Long lines")),
                    "MQ3204: SalesSummary.lines: @GroupBy can't be combined with @Aggregate")),
            of("MQ3205", c -> fails(
                    with(SALE_ENTITY, c.model("SalesSummary", SINGLE,
                            "@Aggregate(fn = AggregateFunction.SUM, attribute = \"units\") Integer units")),
                    "MQ3205: SalesSummary.units: SUM over Integer returns Long; declare the field as Long")),
            of("MQ3206", c -> fails(
                    with(SALE_ENTITY, c.model("SalesSummary", SINGLE,
                            "@Aggregate(fn = AggregateFunction.SUM, attribute = \"amount\", distinct = true) "
                                    + "BigDecimal revenue")),
                    "MQ3206: SalesSummary.revenue: distinct only applies to COUNT, found SUM")),
            of("MQ3207", c -> fails(
                    with(SALE_ENTITY, c.model("SalesSummary", SINGLE, "@GroupBy String region",
                            COUNT + " Long lines")),
                    "MQ3207: SalesSummary: singleGroup = true can't be combined with @GroupBy fields; remove "
                            + "one")),
            of("MQ3301", c -> fails(
                    with(TICKET_ENTITY, c.model("TicketPatch", PATCH, ID,
                            "@Column(attribute = \"customer.name\") String customerName",
                            "@Column(attribute = \"items\") String items")),
                    "MQ3301: TicketPatch.customerName: update models can only write attributes of TicketEntity; "
                            + "'customer.name' needs a join",
                    "MQ3301: TicketPatch.items: update models can only write attributes of TicketEntity; 'items' "
                            + "is a collection")),
            of("MQ3302", c -> fails(
                    with(TICKET_ENTITY, c.customerView(), c.model("TicketPatch", PATCH, ID,
                            "@Join Optional<CustomerView> customer", COUNT + " Long lines", "@GroupBy String code")),
                    "MQ3302: TicketPatch.customer: @Join isn't allowed on @UpdateModel; write the foreign key with "
                            + "@Column(attribute = \"customer\") Long customerId",
                    "MQ3302: TicketPatch.lines: @Aggregate isn't allowed on @UpdateModel; an update writes columns, "
                            + "not groups",
                    "MQ3302: TicketPatch.code: @GroupBy isn't allowed on @UpdateModel; an update writes columns, "
                            + "not groups")),
            of("MQ3303", c -> fails(
                    with(TICKET_ENTITY, c.model("TicketPatch", PATCH, ID, "Long version",
                            "@Column(attribute = \"id\") Long ticketId")),
                    "MQ3303: TicketPatch.version: the @Version attribute is managed by the engine (keepVersion, "
                            + "expectVersion)",
                    "MQ3303: TicketPatch.ticketId: 'id' is TicketEntity's id, which an update can't write; mark the "
                            + "field @PrimaryKey to key on it")),
            of("MQ3304", c -> fails(
                    with(TICKET_ENTITY, c.model("TicketPatch", PATCH, ID, "String createdBy",
                            "@Column(attribute = \"order\") Long orderId")),
                    "MQ3304: TicketPatch.createdBy: TicketEntity.createdBy is @Column(updatable = false)",
                    "MQ3304: TicketPatch.orderId: TicketEntity.order is the inverse side of a to-one (mappedBy = "
                            + "\"ticket\"); write it from the owning side")),
            of("MQ3305", c -> fails(
                    with(TICKET_ENTITY, c.model("TicketPatch", PATCH, ID,
                            "@Column(attribute = \"customer\") String customerId")),
                    "MQ3305: TicketPatch.customerId: CustomerEntity's id is Long, found String")),
            of("MQ3306", c -> fails(
                    with(TICKET_ENTITY, c.model("TicketPatch", PATCH, "@PrimaryKey String code"),
                            c.model("TicketView", "@QueryModel(root = TicketEntity.class, generateChanges = true)",
                                    "@PrimaryKey String code", "String createdBy")),
                    "MQ3306: TicketPatch.code: @PrimaryKey must be TicketEntity's id 'id'; bulk writes key on the "
                            + "entity id",
                    "MQ3306: TicketView.code: @PrimaryKey must be TicketEntity's id 'id'; bulk writes key on the "
                            + "entity id")),
            of("MQ3307", c -> fails(
                    with(TICKET_ENTITY, c.model("TicketPatch", PATCH, ID,
                            "@Column(attribute = \"code\") String empty",
                            "@Column(attribute = \"code\") String unset")),
                    "MQ3307: TicketPatch.empty: generates getEmpty() and setEmpty(...), which clash with "
                            + "Changes.isEmpty() as property 'empty'; rename the field",
                    "MQ3307: TicketPatch.unset: generates unset(String), which clashes with Changes.unset(...); "
                            + "rename the field")),
            of("MQ3401", c -> fails(c.model("CustomerView", CUSTOMER, ID, "@Child String note"),
                    "MQ3401: CustomerView.note: @Child needs a List or Optional of a @QueryModel, found String")),
            of("MQ3402", c -> fails(
                    with(c.model("OrderView", ORDER, ID), c.model("CustomerView", CUSTOMER, ID,
                            "@Child(foreignKey = \"customer.idx\") List<OrderView> orders")),
                    "MQ3402: CustomerView.orders: OrderEntity has no attribute 'customer.idx'")),
            of("MQ3403", c -> fails(
                    with(c.model("OrderView", ORDER, ID), c.model("CustomerView", CUSTOMER, ID,
                            "@Child(key = \"name\", foreignKey = \"customer.id\") List<OrderView> orders")),
                    "MQ3403: CustomerView.orders: key String name and foreignKey Long customer.id differ")),
            of("MQ3404", c -> fails(
                    with(c.model("OrderView", ORDER, ID), c.model("CustomerView", CUSTOMER, ID,
                            "@Child(foreignKey = {\"customer.id\", \"status\"}) List<OrderView> orders")),
                    "MQ3404: CustomerView.orders: @Child takes one key attribute each side; foreignKey names 2")),
            of("MQ3405", c -> fails(
                    with(c.model("OrderView", ORDER, ID),
                            c.model("CustomerView", CUSTOMER, ID, "@Child List<OrderView> orders")),
                    "MQ3405: CustomerView.orders: a List @Child needs foreignKey, the attribute of OrderEntity "
                            + "that holds the parent's key")),
            of("MQ3406", c -> fails(
                    with(c.model("ItemView", ITEM, ID), c.model("OrderView", ORDER, ID,
                            "@Child(through = \"customer\") List<ItemView> items")),
                    "MQ3406: OrderView.items: through 'customer' ends at CustomerEntity, not at ItemEntity, the root "
                            + "of ItemView")));

    static Stream<Arguments> matrix() {
        var runs = new ArrayList<Arguments>();
        for (Case matrixCase : CASES) {
            for (Shape shape : matrixCase.shapes()) {
                for (boolean lombok : new boolean[] {false, true}) {
                    runs.add(Arguments.of(matrixCase.code() + " " + shape + " lombok=" + (lombok ? "on" : "off"),
                            matrixCase.build().apply(new Ctx(shape, lombok)), lombok));
                }
            }
        }
        return runs.stream();
    }

    @DisplayName("every live code, with its message")
    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void ac_diag_01_each_live_code_reports_its_message(String name, Scenario scenario, boolean lombok) {
        var compiler = lombok
                ? javac().withProcessors(ProcessorHarness.lombok(), new ModelQueryProcessor())
                : javac().withProcessors(new ModelQueryProcessor());

        Compilation compilation = compiler.compile(scenario.sources());

        if (scenario.warning()) {
            // MQ3016 warns and the QModel is still written (R-DIAG-02).
            assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
            assertThat(compilation.warnings().stream().map(w -> w.getMessage(Locale.ROOT))
                    .filter(text -> text.startsWith("MQ")))
                    .containsExactlyInAnyOrderElementsOf(scenario.messages());
            assertThat(compilation.generatedSourceFile("models.QOrderView")).isPresent();
        } else {
            assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
            assertThat(errors(compilation)).containsExactlyInAnyOrderElementsOf(scenario.messages());
        }
    }
}
