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

/** {@code @FilterColumn} and the joins it generates ({@code processor/30} §5), and the checks it can fail. */
class FilterColumnTest {

    private static final JavaFileObject LINE_ENTITY = source("shop.LineEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class LineEntity {
                @Id
                Long id;
                String sku;
                int qty;
                @ManyToOne
                CountryEntity origin;
            }
            """);

    /** A root with a to-one association, two collection associations, a collection of values and an embedded value. */
    private static final JavaFileObject BASKET_ENTITY = source("shop.BasketEntity", """
            package shop;

            import jakarta.persistence.ElementCollection;
            import jakarta.persistence.Embedded;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToMany;
            import jakarta.persistence.ManyToOne;
            import jakarta.persistence.OneToMany;
            import java.util.List;
            import java.util.Set;

            @Entity
            public class BasketEntity {
                @Id
                Long id;
                String status;
                @Embedded
                Address address;
                @ManyToOne
                CustomerEntity customer;
                @OneToMany
                List<LineEntity> lines;
                @ManyToMany
                Set<LineEntity> extras;
                @ElementCollection
                List<String> tags;
            }
            """);

    private static final String IMPORTS = """
            package shop;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.FilterColumn;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.JoinKind;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.util.Optional;

            """;

    /** {@code shop.BasketView} as {@code declaration} writes it, with everything it may name. */
    private static JavaFileObject[] basketSources(String declaration) {
        return new JavaFileObject[] {ShopSources.ADDRESS, ShopSources.COUNTRY_ENTITY, ShopSources.CUSTOMER_ENTITY,
                ShopSources.COUNTRY_VIEW, ShopSources.CUSTOMER_VIEW, ShopSources.INVOICE_STATUS, LINE_ENTITY,
                BASKET_ENTITY, source("shop.BasketView", IMPORTS + declaration)};
    }

    private static Compilation compileBasket(String declaration) {
        return compile(basketSources(declaration));
    }

    /** The generated QModel on one line, a wrapped method chain closed up. */
    private static String basket(Compilation compilation) {
        return generatedFlat(compilation, "shop.QBasketView").replace(" .", ".");
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }

    @Test
    void ac_proc_06_a_path_under_a_join_reuses_its_table_at_every_depth() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "CUSTOMER_EMAIL", path = "customer.email")
                @FilterColumn(name = "COUNTRY", path = "customer.country.name")
                @FilterColumn(name = "CITY", path = "address.city")
                public record BasketView(@PrimaryKey Long id, @Join Optional<CustomerView> customer) {}
                """);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningCount(1);
        assertThat(compilation).hadWarningContaining(
                "MQ3022: BasketView @FilterColumn(COUNTRY): its key 'customer.country.name' is already held by "
                        + "the column 'customer.country.name'");
        String generated = basket(compilation);
        assertThat(generated)
                .contains("ColumnField<BasketView, CustomerEntity, String> CUSTOMER_EMAIL = "
                        + "ColumnField.of(BasketView.class, CUSTOMER_TABLE, \"email\", String.class);")
                .contains("ColumnField<BasketView, CountryEntity, String> COUNTRY = "
                        + "ColumnField.of(BasketView.class, CUSTOMER_COUNTRY_TABLE, \"name\", String.class);")
                .contains("ColumnField<BasketView, BasketEntity, String> CITY = "
                        + "ColumnField.of(BasketView.class, ROOT, \"address.city\", String.class);")
                // Not mapped, and in no column set.
                .contains("SelectSet<BasketView> ALL = SelectSet.of(ID);")
                .contains("SelectSet<BasketView> CUSTOMER = SelectSet.of(CUSTOMER_ID, CUSTOMER_NAME);")
                .doesNotContain("row.get(CITY)");
        // The joins are the @Join's own: neither is declared a second time.
        assertThat(generated.split("join\\(", -1)).hasSize(1 + 3);
        assertThat(generated).contains("CUSTOMER_TABLE = TableField.<BasketEntity, CustomerEntity>join(ROOT, "
                + "\"customer\", JoinType.LEFT).presentBy(QCustomerView.KEY).named(\"customer\");")
                .contains("CUSTOMER_COUNTRY_TABLE = QCustomerView.COUNTRY_TABLE.withParent(CUSTOMER_TABLE);");
    }

    @Test
    void ac_proc_06_a_path_no_join_covers_creates_its_own_table_with_the_join_type() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "CUSTOMER_EMAIL", path = "customer.email", joinType = JoinKind.INNER)
                @FilterColumn(name = "COUNTRY", path = "customer.country.name", joinType = JoinKind.INNER)
                @FilterColumn(name = "STATUS", path = "status", converter = InvoiceStatus.Converter.class)
                public record BasketView(@PrimaryKey Long id) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(basket(compilation))
                .contains("TableField<BasketEntity, CustomerEntity> CUSTOMER_TABLE = "
                        + "TableField.<BasketEntity, CustomerEntity>join(ROOT, \"customer\", JoinType.INNER);")
                .contains("TableField<CustomerEntity, CountryEntity> CUSTOMER_COUNTRY_TABLE = "
                        + "TableField.<CustomerEntity, CountryEntity>join(CUSTOMER_TABLE, \"country\", "
                        + "JoinType.INNER);")
                .contains("ColumnField<BasketView, CustomerEntity, String> CUSTOMER_EMAIL = "
                        + "ColumnField.of(BasketView.class, CUSTOMER_TABLE, \"email\", String.class);")
                .contains("ColumnField<BasketView, CountryEntity, String> COUNTRY = "
                        + "ColumnField.of(BasketView.class, CUSTOMER_COUNTRY_TABLE, \"name\", String.class);")
                // The constant's type is the converter's model type (R-PROC-10).
                .contains("ColumnField<BasketView, BasketEntity, InvoiceStatus> STATUS = ColumnField.of("
                        + "BasketView.class, ROOT, \"status\", InvoiceStatus.class, String.class, "
                        + "InvoiceStatus.Converter.INSTANCE);");
    }

    @Test
    void ac_proc_07_filter_columns_sharing_an_alias_share_one_join_and_another_alias_is_another_join() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "SKU_A", path = "lines.sku", joinType = JoinKind.INNER, alias = "lineA")
                @FilterColumn(name = "QTY_A", path = "lines.qty", joinType = JoinKind.INNER, alias = "lineA")
                @FilterColumn(name = "SKU_B", path = "lines.sku", joinType = JoinKind.LEFT, alias = "lineB")
                @FilterColumn(name = "ORIGIN_B", path = "lines.origin.name", joinType = JoinKind.LEFT, alias = "lineB")
                @FilterColumn(name = "SKU", path = "lines.sku", joinType = JoinKind.INNER)
                public record BasketView(@PrimaryKey Long id) {}
                """);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningCount(2);
        assertThat(compilation).hadWarningContaining(
                "MQ3022: BasketView @FilterColumn(SKU_B): its key 'lines.sku' is already held by @FilterColumn(SKU_A)");
        assertThat(compilation).hadWarningContaining(
                "MQ3022: BasketView @FilterColumn(SKU): its key 'lines.sku' is already held by @FilterColumn(SKU_A)");
        String generated = basket(compilation);
        assertThat(generated)
                .contains("TableField<BasketEntity, LineEntity> LINE_A_TABLE = "
                        + "TableField.<BasketEntity, LineEntity>join(ROOT, \"lines\", JoinType.INNER).as(\"lineA\");")
                .contains("TableField<BasketEntity, LineEntity> LINE_B_TABLE = "
                        + "TableField.<BasketEntity, LineEntity>join(ROOT, \"lines\", JoinType.LEFT).as(\"lineB\");")
                // Below an aliased join, a join is already that alias's own.
                .contains("TableField<LineEntity, CountryEntity> LINE_B_ORIGIN_TABLE = "
                        + "TableField.<LineEntity, CountryEntity>join(LINE_B_TABLE, \"origin\", JoinType.LEFT);")
                // Without alias, an INNER path is on a join beside the collection's own table, which stays LEFT.
                .contains("LINES_TABLE = TableField.<BasketEntity, LineEntity>join(ROOT, \"lines\", JoinType.LEFT);")
                .contains("TableField<BasketEntity, LineEntity> LINES_INNER_TABLE = TableField."
                        + "<BasketEntity, LineEntity>join(ROOT, \"lines\", JoinType.INNER).as(\"linesInner\");")
                .contains("SKU_A = ColumnField.of(BasketView.class, LINE_A_TABLE, \"sku\", String.class);")
                .contains("ColumnField<BasketView, LineEntity, Integer> QTY_A = "
                        + "ColumnField.of(BasketView.class, LINE_A_TABLE, \"qty\", Integer.class);")
                .contains("SKU_B = ColumnField.of(BasketView.class, LINE_B_TABLE, \"sku\", String.class);")
                .contains("ORIGIN_B = ColumnField.of(BasketView.class, LINE_B_ORIGIN_TABLE, \"name\", String.class);")
                .contains("SKU = ColumnField.of(BasketView.class, LINES_INNER_TABLE, \"sku\", String.class);");
        // lineA once, lineB once, the two collections of the root, the join below lineB, and the INNER one of SKU.
        assertThat(generated.split("join\\(", -1)).hasSize(1 + 6);
    }

    @Test
    void ac_proc_07_an_alias_that_is_a_joins_alias_selects_that_join() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "BUYER_EMAIL", path = "customer.email")
                @FilterColumn(name = "PAYER_EMAIL", path = "customer.email", alias = "payer")
                @FilterColumn(name = "PAYER_LAND", path = "customer.country.name", alias = "payer",
                        joinType = JoinKind.LEFT)
                @FilterColumn(name = "AGENT_EMAIL", path = "customer.email", alias = "rep")
                @FilterColumn(name = "OTHER_EMAIL", path = "customer.email", alias = "other")
                @FilterColumn(name = "OTHER_COUNTRY", path = "customer.country.name", alias = "other")
                public record BasketView(
                        @PrimaryKey Long id,
                        @Join(attribute = "customer") Optional<CustomerView> buyer,
                        @Join(attribute = "customer") Optional<CustomerView> payer,
                        @Join(attribute = "customer", alias = "rep") Optional<CustomerView> agent) {}
                """);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningCount(4);
        assertThat(compilation).hadWarningContaining(
                "MQ3022: BasketView @FilterColumn(PAYER_EMAIL): its key 'customer.email' is already held by "
                        + "@FilterColumn(BUYER_EMAIL)");
        String generated = basket(compilation);
        assertThat(generated)
                // No alias is the first @Join on the attribute; an alias is the @Join it names, written or automatic.
                .contains("BUYER_EMAIL = ColumnField.of(BasketView.class, BUYER_TABLE, \"email\", String.class);")
                .contains("PAYER_EMAIL = ColumnField.of(BasketView.class, PAYER_TABLE, \"email\", String.class);")
                .contains("PAYER_LAND = "
                        + "ColumnField.of(BasketView.class, PAYER_COUNTRY_TABLE, \"name\", String.class);")
                .contains("AGENT_EMAIL = ColumnField.of(BasketView.class, AGENT_TABLE, \"email\", String.class);")
                // An alias no @Join has is a join of its own, as it was.
                .contains("TableField<BasketEntity, CustomerEntity> OTHER_TABLE = TableField."
                        + "<BasketEntity, CustomerEntity>join(ROOT, \"customer\", JoinType.LEFT).as(\"other\");")
                .contains("OTHER_EMAIL = ColumnField.of(BasketView.class, OTHER_TABLE, \"email\", String.class);")
                .contains("OTHER_COUNTRY = "
                        + "ColumnField.of(BasketView.class, OTHER_COUNTRY_TABLE, \"name\", String.class);");
        // The three @Joins, the alias 'other' and the join below it, and the two collections of the root.
        assertThat(generated.split("join\\(", -1)).hasSize(1 + 7);
    }

    @Test
    void ac_proc_08_a_filter_through_a_root_collection_never_retypes_its_table() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "SKU", path = "lines.sku", joinType = JoinKind.LEFT)
                @FilterColumn(name = "ORIGIN", path = "lines.origin.name", joinType = JoinKind.LEFT)
                @FilterColumn(name = "QTY", path = "lines.qty", joinType = JoinKind.INNER)
                @FilterColumn(name = "QTY_ORIGIN", path = "lines.origin.name", joinType = JoinKind.INNER)
                public record BasketView(@PrimaryKey Long id) {}
                """);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningCount(1);
        assertThat(compilation).hadWarningContaining(
                "MQ3022: BasketView @FilterColumn(QTY_ORIGIN): its key 'lines.origin.name' is already held by "
                        + "@FilterColumn(ORIGIN)");
        String generated = basket(compilation);
        assertThat(generated)
                // A LEFT path is on the collection's constant, which no filter column replaces.
                .contains("LINES_TABLE = TableField.<BasketEntity, LineEntity>join(ROOT, \"lines\", JoinType.LEFT);")
                .contains("LINES_ORIGIN_TABLE = "
                        + "TableField.<LineEntity, CountryEntity>join(LINES_TABLE, \"origin\", JoinType.LEFT);")
                .contains("SKU = ColumnField.of(BasketView.class, LINES_TABLE, \"sku\", String.class);")
                .contains("ORIGIN = ColumnField.of(BasketView.class, LINES_ORIGIN_TABLE, \"name\", String.class);")
                // An INNER path is on a table of its own, whose alias keeps the two joins apart.
                .contains("LINES_INNER_TABLE = TableField.<BasketEntity, LineEntity>join(ROOT, \"lines\", "
                        + "JoinType.INNER).as(\"linesInner\");")
                .contains("LINES_INNER_ORIGIN_TABLE = TableField.<LineEntity, CountryEntity>join("
                        + "LINES_INNER_TABLE, \"origin\", JoinType.INNER);")
                .contains("QTY = ColumnField.of(BasketView.class, LINES_INNER_TABLE, \"qty\", Integer.class);")
                .contains("QTY_ORIGIN = "
                        + "ColumnField.of(BasketView.class, LINES_INNER_ORIGIN_TABLE, \"name\", String.class);");
        // The two collections of the root, the INNER join of 'lines', and 'origin' below each join of 'lines'.
        assertThat(generated.split("join\\(", -1)).hasSize(1 + 5);
    }

    @Test
    void ac_proc_08_every_collection_association_of_the_root_has_a_table_constant() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                public record BasketView(@PrimaryKey Long id) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(basket(compilation))
                .contains("public static final TableField<BasketEntity, LineEntity> LINES_TABLE = "
                        + "TableField.<BasketEntity, LineEntity>join(ROOT, \"lines\", JoinType.LEFT);")
                .contains("public static final TableField<BasketEntity, LineEntity> EXTRAS_TABLE = "
                        + "TableField.<BasketEntity, LineEntity>join(ROOT, \"extras\", JoinType.LEFT);")
                // A collection of values is no association, and a to-one is joined only when asked for.
                .doesNotContain("TAGS_TABLE")
                .doesNotContain("CUSTOMER_TABLE");
    }

    @Test
    void ac_diag_01_mq3011_filter_column_path_does_not_resolve_or_crosses_a_collection_without_join_type() {
        var processor = new ProcessorHarness.Recording();
        Compilation compilation = processor.compile(basketSources("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.contry")
                @FilterColumn(name = "SKU", path = "lines.sku")
                @FilterColumn(name = "LINES", path = "lines")
                @FilterColumn(name = "LENGTH", path = "status.length")
                @FilterColumn(name = "TAG", path = "tags.value", joinType = JoinKind.INNER)
                @FilterColumn(name = "CITY", path = "address.city")
                public record BasketView(@PrimaryKey Long id) {}
                """));

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3011,
                        "BasketView @FilterColumn(CUSTOMER_COUNTRY): no attribute 'contry' on CustomerEntity"),
                message(DiagnosticCode.MQ3011, "BasketView @FilterColumn(SKU): 'lines' on BasketEntity is a "
                        + "collection, which multiplies rows when joined; set joinType, or filter on it with "
                        + "Filters.exists"),
                message(DiagnosticCode.MQ3011, "BasketView @FilterColumn(LINES): 'lines' on BasketEntity is a "
                        + "collection, which a column can't read; filter on it with Filters.exists"),
                message(DiagnosticCode.MQ3011, "BasketView @FilterColumn(LENGTH): 'status' on BasketEntity is not "
                        + "an embedded value, so it has no attribute 'length'"),
                message(DiagnosticCode.MQ3011, "BasketView @FilterColumn(TAG): 'tags' on BasketEntity has no entity "
                        + "to join, so 'tags.value' can't go through it"));
        // Every path is reported, on the model's type, and the model that failed is not generated.
        assertThat(processor.messages()).hasSize(5)
                .allSatisfy(reported ->
                        assertThat(reported.element().getSimpleName().toString()).isEqualTo("BasketView"));
        assertThat(processor.originating()).containsOnlyKeys("shop.QCountryView", "shop.QCustomerView");
    }

    @Test
    void ac_diag_01_mq3016_a_filter_column_ending_at_a_to_one_association_raises_no_warning() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "CUSTOMER_ENTITY", path = "customer")
                public record BasketView(@PrimaryKey Long id) {}
                """);

        // Unlike a model column on a to-one association, a filter column is never selected: nothing to warn of.
        assertThat(compilation).succeededWithoutWarnings();
    }

    @Test
    void ac_diag_01_mq3012_one_join_with_two_join_types() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "QTY_B", path = "lines.qty", joinType = JoinKind.LEFT, alias = "lineB")
                @FilterColumn(name = "SKU_B", path = "lines.sku", joinType = JoinKind.INNER, alias = "lineB")
                @FilterColumn(name = "SKU_C", path = "lines.sku", joinType = JoinKind.INNER, alias = "lineC")
                @FilterColumn(name = "CUSTOMER_EMAIL", path = "customer.email")
                @FilterColumn(name = "COUNTRY", path = "customer.country.name", joinType = JoinKind.INNER)
                public record BasketView(@PrimaryKey Long id) {}
                """);

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3012,
                        "BasketView @FilterColumn(SKU_B): alias 'lineB' is INNER here, LEFT on QTY_B"),
                message(DiagnosticCode.MQ3012,
                        "BasketView @FilterColumn(COUNTRY): join 'customer' is INNER here, LEFT on CUSTOMER_EMAIL"));
    }

    @Test
    void ac_diag_01_mq3012_a_written_join_type_differs_from_the_reused_join() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "BUYER_EMAIL", path = "customer.email", joinType = JoinKind.LEFT)
                @FilterColumn(name = "F_BUYER_NAME", path = "customer.name")
                @FilterColumn(name = "F_BUYER_COUNTRY", path = "customer.country.name", joinType = JoinKind.INNER)
                @FilterColumn(name = "PAYER_EMAIL", path = "customer.email", joinType = JoinKind.INNER,
                        alias = "payer")
                @FilterColumn(name = "F_PAYER_NAME", path = "customer.name", joinType = JoinKind.LEFT, alias = "payer")
                @FilterColumn(name = "F_PAYER_COUNTRY", path = "customer.country.name", alias = "payer")
                public record BasketView(
                        @PrimaryKey Long id,
                        @Join(attribute = "customer", type = JoinKind.INNER) Optional<CustomerView> buyer,
                        @Join(attribute = "customer") Optional<CustomerView> payer) {}
                """);

        // A joinType left out never conflicts; one written is checked against each @Join the path reuses.
        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3012, "BasketView @FilterColumn(BUYER_EMAIL): joinType is LEFT, but the "
                        + "path reuses the join of @Join field 'buyer', which is INNER; drop joinType, or give the "
                        + "path another alias"),
                message(DiagnosticCode.MQ3012, "BasketView @FilterColumn(F_BUYER_COUNTRY): joinType is INNER, but the "
                        + "path reuses the join of @Join field 'buyer.country', which is LEFT; drop joinType, or "
                        + "give the path another alias"),
                message(DiagnosticCode.MQ3012, "BasketView @FilterColumn(PAYER_EMAIL): joinType is INNER, but the "
                        + "path reuses the join of @Join field 'payer', which is LEFT; drop joinType, or give the "
                        + "path another alias"));
    }

    @Test
    void ac_diag_01_mq3012_the_inner_join_of_a_root_collection_shares_its_alias() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "SKU", path = "lines.sku", joinType = JoinKind.INNER)
                @FilterColumn(name = "QTY", path = "lines.qty", joinType = JoinKind.LEFT, alias = "linesInner")
                public record BasketView(@PrimaryKey Long id) {}
                """);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3012,
                "BasketView @FilterColumn(QTY): alias 'linesInner' is LEFT here, INNER on SKU"));
    }

    @Test
    void ac_diag_01_mq3012_the_generated_inner_alias_is_not_named_when_the_user_wrote_none() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "QTY", path = "lines.qty", joinType = JoinKind.LEFT, alias = "linesInner")
                @FilterColumn(name = "SKU", path = "lines.sku", joinType = JoinKind.INNER)
                public record BasketView(@PrimaryKey Long id) {}
                """);

        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3012,
                "BasketView @FilterColumn(SKU): join 'lines' is INNER here, LEFT on QTY"));
    }

    @Test
    void ac_diag_01_mq3013_filter_column_name_is_taken_reserved_or_no_identifier() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "STATUS", path = "status")
                @FilterColumn(name = "CUSTOMER_NAME", path = "customer.name")
                @FilterColumn(name = "CITY", path = "address.city")
                @FilterColumn(name = "CITY", path = "address.city")
                @FilterColumn(name = "KEY", path = "id")
                @FilterColumn(name = "basket-id", path = "id")
                @FilterColumn(name = "class", path = "id")
                public record BasketView(
                        @PrimaryKey Long id, String status, @Join Optional<CustomerView> customer) {}
                """);

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3013, "BasketView @FilterColumn(STATUS): name already used by field 'status'"),
                message(DiagnosticCode.MQ3013,
                        "BasketView @FilterColumn(CUSTOMER_NAME): name already used by customer.name"),
                message(DiagnosticCode.MQ3013, "BasketView @FilterColumn(CITY): name already used by another "
                        + "@FilterColumn"),
                message(DiagnosticCode.MQ3013,
                        "BasketView @FilterColumn(KEY): name is reserved by the generated class"),
                message(DiagnosticCode.MQ3013, "BasketView @FilterColumn(basket-id): name 'basket-id' can't be a "
                        + "constant's name; use a Java identifier"),
                message(DiagnosticCode.MQ3013, "BasketView @FilterColumn(class): name 'class' can't be a "
                        + "constant's name; use a Java identifier"));
    }

    @Test
    void ac_diag_01_mq3014_filter_column_converter_does_not_convert_to_the_attribute_type() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "BASKET", path = "id", converter = InvoiceStatus.Converter.class)
                @FilterColumn(name = "STATUS", path = "status", converter = String.class)
                public record BasketView(@PrimaryKey Long id) {}
                """);

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3014, "BasketView @FilterColumn(BASKET): Converter converts InvoiceStatus "
                        + "to String, entity attribute type Long"),
                message(DiagnosticCode.MQ3014,
                        "BasketView @FilterColumn(STATUS): String is not a ColumnConverter<?, String>"));
    }

    @Test
    void ac_diag_01_mq3015_constant_of_a_filter_join_or_a_collection_is_taken_or_its_alias_is_no_identifier() {
        Compilation compilation = compileBasket("""
                @QueryModel(root = BasketEntity.class)
                @FilterColumn(name = "SKU", path = "lines.sku", joinType = JoinKind.INNER, alias = "customer")
                @FilterColumn(name = "QTY", path = "lines.qty", joinType = JoinKind.INNER, alias = "line-b")
                @FilterColumn(name = "QTY_NEW", path = "lines.qty", joinType = JoinKind.INNER, alias = "new")
                @FilterColumn(name = "EXTRA_SKU", path = "extras.sku", joinType = JoinKind.INNER, alias = "linesInner")
                @FilterColumn(name = "LINE_SKU", path = "lines.sku", joinType = JoinKind.INNER)
                public record BasketView(
                        @PrimaryKey Long id,
                        @Join Optional<CustomerView> customer,
                        @Join(attribute = "customer", prefix = "EXTRAS") Optional<CustomerView> payer,
                        @Join(attribute = "customer", prefix = "class") Optional<CustomerView> agent,
                        @Column(attribute = "status") String linesTable) {}
                """);

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3015, "BasketView.agent: @Join(prefix = \"class\") can't start a "
                        + "constant's name; use a Java identifier such as AGENT"),
                message(DiagnosticCode.MQ3015, "BasketView: constant EXTRAS_TABLE of the collection 'extras' is also "
                        + "generated for the join payer; set another @Join(prefix) or @FilterColumn(alias)"),
                message(DiagnosticCode.MQ3015, "BasketView @FilterColumn(SKU): constant CUSTOMER_TABLE of the join "
                        + "'lines' of @FilterColumn(SKU) is also generated for the join customer; set another "
                        + "@Join(prefix) or @FilterColumn(alias)"),
                message(DiagnosticCode.MQ3015, "BasketView @FilterColumn(QTY): alias 'line-b' can't start the name "
                        + "of its join's constant; use a Java identifier"),
                message(DiagnosticCode.MQ3015, "BasketView @FilterColumn(QTY_NEW): alias 'new' can't start the name "
                        + "of its join's constant; use a Java identifier"),
                message(DiagnosticCode.MQ3015, "BasketView @FilterColumn(LINE_SKU): constant LINES_INNER_TABLE of "
                        + "the join 'lines' of @FilterColumn(LINE_SKU) is also generated for the join 'extras' of "
                        + "@FilterColumn(EXTRA_SKU); set another @Join(prefix) or @FilterColumn(alias)"),
                message(DiagnosticCode.MQ3015, "BasketView.linesTable: constant LINES_TABLE is also generated for "
                        + "the collection 'lines'; rename the field"));
    }
}
