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

/**
 * A nested {@code @Join} model with {@code @JoinSource} and {@code @JoinEntity} of its own: the outer model registers
 * them under each {@code @Join}'s prefix (processor/31 R-GEN-43, processor/30 R-PROC-44, R-PROC-48, D-214).
 */
class NestedJoinModelTest {

    private static final JavaFileObject ORDER = source("shop.OrderEntity", """
            package shop;
            import jakarta.persistence.*;
            import java.math.BigDecimal;
            @Entity public class OrderEntity {
                @Id Long id;
                @Column(nullable = false) Long customerId;
                BigDecimal total;
                @ManyToOne CustomerEntity customer;
                @ManyToOne CustomerEntity referrer;
            }
            """);
    private static final JavaFileObject CUSTOMER = source("shop.CustomerEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class CustomerEntity {
                @Id Long id;
                String name;
                @ManyToOne AddressEntity address;
                @ManyToOne PlaceEntity place;
            }
            """);
    private static final JavaFileObject ADDRESS = source("shop.AddressEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class AddressEntity {
                @Id Long id;
                String city;
                @ManyToOne CityEntity cityRow;
            }
            """);
    private static final JavaFileObject PLACE = source("shop.PlaceEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity @Inheritance public class PlaceEntity {
                @Id Long id;
            }
            """);
    private static final JavaFileObject CAMPUS = source("shop.CampusEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class CampusEntity extends PlaceEntity {
                String dean;
            }
            """);
    private static final JavaFileObject CITY = source("shop.CityEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class CityEntity {
                @Id Long id;
                String name;
                @ManyToOne CityEntity capital;
            }
            """);
    private static final JavaFileObject LABEL = source("shop.LabelEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class LabelEntity {
                @Id Long id;
                String name;
                @ManyToOne CityEntity city;
            }
            """);
    private static final JavaFileObject SPEND = source("shop.Spend", """
            package shop;
            import com.rey.modelquery.annotations.*;
            import java.math.BigDecimal;
            @SourceModel(root = OrderEntity.class)
            public record Spend(
                    @PrimaryKey @GroupBy Long customerId,
                    @Aggregate(fn = AggregateFunction.SUM, attribute = "total") BigDecimal spent) {}
            """);
    private static final JavaFileObject TALLY = source("shop.Tally", """
            package shop;
            import com.rey.modelquery.annotations.*;
            @SourceModel(root = OrderEntity.class)
            public record Tally(
                    @PrimaryKey @GroupBy Long customerId,
                    @Aggregate(fn = AggregateFunction.COUNT) Long orders) {}
            """);
    private static final JavaFileObject BUYER = source("shop.Buyer", """
            package shop;
            import com.rey.modelquery.annotations.*;
            import java.math.BigDecimal;
            @QueryModel(root = CustomerEntity.class)
            @JoinSource(name = "spend", source = Spend.class,
                    on = @JoinSource.On(column = "id", key = "customerId"))
            @JoinEntity(name = "label", entity = LabelEntity.class, on = @JoinEntity.On(column = "id"))
            public record Buyer(
                    @PrimaryKey Long id, String name,
                    @SourceColumn(join = "spend", field = "spent") BigDecimal spent,
                    @Column(entityJoin = "label", attribute = "name") String labelName) {}
            """);

    private static Compilation compileOrder(String declaration, JavaFileObject... more) {
        var sources = new java.util.ArrayList<>(java.util.List.of(ORDER, CUSTOMER, ADDRESS, PLACE, CAMPUS, CITY, LABEL,
                SPEND, TALLY, BUYER));
        sources.addAll(java.util.List.of(more));
        sources.add(source("shop.OrderView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                """ + declaration));
        return compile(sources.toArray(JavaFileObject[]::new));
    }

    @Test
    void ac_gen_37_two_fields_of_one_nested_type_register_their_joins_under_their_prefixes() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(
                        @PrimaryKey Long id,
                        @Join Optional<Buyer> customer,
                        @Join Optional<Buyer> referrer) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated)
                .contains("CUSTOMER_SPEND = SourceJoin.of(\"customerSpend\", Spend.class, JoinKind.LEFT")
                .contains("SourceJoin.on(ColumnField.of(OrderView.class, CUSTOMER_TABLE, \"id\", Long.class), "
                        + "QSpend.CUSTOMER_ID)")
                .contains(".within(CUSTOMER_TABLE)")
                .contains("REFERRER_SPEND = SourceJoin.of(\"referrerSpend\"")
                .contains("CUSTOMER_LABEL = EntityJoin.of(\"customerLabel\", LabelEntity.class, JoinKind.LEFT, "
                        + "List.of(EntityJoin.on(CUSTOMER_ID, \"id\"))).within(CUSTOMER_TABLE)")
                .contains("CUSTOMER_SPENT = QBuyer.SPENT.withTable(OrderView.class, "
                        + "CUSTOMER_SPEND.root().named(\"customer\"))")
                .contains("CUSTOMER_LABEL_NAME = QBuyer.LABEL_NAME.withTable(OrderView.class, "
                        + "CUSTOMER_LABEL.root().named(\"customer\"))")
                .contains("REFERRER_LABEL_NAME = QBuyer.LABEL_NAME.withTable(OrderView.class, "
                        + "REFERRER_LABEL.root().named(\"referrer\"))")
                .contains("CUSTOMER_SCOPE = JoinScope.of(CUSTOMER_TABLE).source(QBuyer.SPEND, CUSTOMER_SPEND)"
                        + ".entity(QBuyer.LABEL, CUSTOMER_LABEL)")
                .contains("REFERRER_SCOPE = JoinScope.of(REFERRER_TABLE).source(QBuyer.SPEND, REFERRER_SPEND)")
                .contains("Row customer = row.scoped(CUSTOMER_SCOPE)")
                .contains("Row referrer = row.scoped(REFERRER_SCOPE)")
                .contains("query( SourceQuery<Spend> customerSpend, SourceQuery<Spend> referrerSpend)")
                .contains(".joinSource(CUSTOMER_SPEND, customerSpend).joinSource(REFERRER_SPEND, referrerSpend)"
                        + ".joinEntity(CUSTOMER_LABEL).joinEntity(REFERRER_LABEL)");
    }

    @Test
    void ac_gen_38_a_nested_model_without_joins_reads_its_table_and_takes_no_parameter() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<Plain> customer) {}
                """, source("shop.Plain", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = CustomerEntity.class)
                public record Plain(@PrimaryKey Long id, String name) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated).contains("Row customer = row.scoped(CUSTOMER_TABLE)")
                .contains("query()").doesNotContain("CUSTOMER_SCOPE");
    }

    @Test
    void ac_gen_39_two_levels_register_the_inner_joins_in_the_nested_model_and_the_outer_one() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<Holder> customer) {}
                """, source("shop.Holder", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = CustomerEntity.class)
                public record Holder(@PrimaryKey Long id, @Join(attribute = "address") Optional<Home> address) {}
                """), source("shop.Home", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = AddressEntity.class)
                @JoinSource(name = "tally", source = Tally.class,
                        on = @JoinSource.On(column = "id", key = "customerId"))
                public record Home(@PrimaryKey Long id,
                        @SourceColumn(join = "tally", field = "orders") Long orders) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String holder = generatedFlat(compilation, "shop.QHolder");
        assertThat(holder).contains("ADDRESS_TALLY = SourceJoin.of(\"addressTally\", Tally.class, JoinKind.LEFT")
                .contains(".within(ADDRESS_TABLE)")
                .contains("ADDRESS_SCOPE = JoinScope.of(ADDRESS_TABLE).source(QHome.TALLY, ADDRESS_TALLY)")
                .contains("query( SourceQuery<Tally> addressTally)");
        String order = generatedFlat(compilation, "shop.QOrderView");
        assertThat(order).contains("CUSTOMER_ADDRESS_TALLY = SourceJoin.of(\"customerAddressTally\"")
                .contains(".within(CUSTOMER_ADDRESS_TABLE)")
                .contains("CUSTOMER_ADDRESS_ORDERS = QHolder.ADDRESS_ORDERS.withTable(OrderView.class, "
                        + "CUSTOMER_ADDRESS_TALLY.root().named(\"customer.address\"))")
                .contains("CUSTOMER_SCOPE = JoinScope.of(CUSTOMER_TABLE) .source(QHolder.ADDRESS_TALLY, "
                        + "CUSTOMER_ADDRESS_TALLY)")
                .contains("query( SourceQuery<Tally> customerAddressTally)");
    }

    @Test
    void ac_gen_40_a_grouped_outer_model_keeps_its_group_keys_and_takes_the_parameters() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(
                        @PrimaryKey @GroupBy Long id,
                        @Join Optional<Buyer> customer,
                        @Aggregate(fn = AggregateFunction.COUNT) Long orders) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated).contains("query( SourceQuery<Spend> customerSpend)")
                .contains(".groupBy(GROUP_KEYS)").contains(".joinSource(CUSTOMER_SPEND, customerSpend)");
    }

    @Test
    void ac_proc_53_a_nested_join_producing_the_constant_of_a_declared_join_is_mq3044() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                @JoinSource(name = "customerSpend", source = Spend.class,
                        on = @JoinSource.On(column = "id", key = "customerId"))
                public record OrderView(@PrimaryKey Long id, @Join Optional<Buyer> customer) {}
                """);
        assertThat(errors(compilation)).anySatisfy(error -> assertThat(error)
                .startsWith(DiagnosticCode.MQ3044.code() + ": ").contains("customerSpend"));
    }

    private static final String LABELLED = """
            package shop;
            import com.rey.modelquery.annotations.*;
            @QueryModel(root = CustomerEntity.class)
            @JoinEntity(name = "label", entity = LabelEntity.class, type = JoinKind.%s,
                    on = @JoinEntity.On(column = "id"))
            public record Labelled(@PrimaryKey Long id, String name,
                    @Column(entityJoin = "label", attribute = "name") String labelName) {}
            """;

    @Test
    void ac_proc_54_a_nested_source_join_below_a_source_anchored_entity_join_is_mq3044() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                @JoinSource(name = "tally", source = Tally.class,
                        on = @JoinSource.On(column = "customerId", key = "customerId"))
                @JoinEntity(name = "buyer", source = "tally", entity = CustomerEntity.class,
                        on = @JoinEntity.On(field = "customerId"))
                public record OrderView(@PrimaryKey Long id, Long customerId,
                        @Join(entityJoin = "buyer") Optional<Buyer> buyer) {}
                """);
        // Only a column-anchored entity join anchors a nested source (D-218); a source-anchored one anchors neither.
        assertThat(errors(compilation)).containsExactly(DiagnosticCode.MQ3044.code() + ": OrderView.buyer: Buyer "
                + "joins 'spend', 'label' below the entity join 'buyer', which is anchored on a joined source; only a "
                + "join anchored on the root's columns may anchor another");
    }

    @Test
    void ac_proc_61_ac_gen_49_a_nested_source_join_below_a_column_anchored_entity_join_is_generated_on_its_root() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "buyer", entity = CustomerEntity.class, on = @JoinEntity.On(column = "customerId"))
                public record OrderView(@PrimaryKey Long id, Long customerId,
                        @Join(entityJoin = "buyer") Optional<Buyer> buyer) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        // Re-rooted onto the entity join's root and registered within its table, after the join it reads.
        assertThat(generated)
                .contains("BUYER_SPEND = SourceJoin.of(\"buyerSpend\", Spend.class, JoinKind.LEFT, "
                        + "List.of(SourceJoin.on(ColumnField.of(OrderView.class, BUYER_TABLE, \"id\", Long.class), "
                        + "QSpend.CUSTOMER_ID))).within(BUYER_TABLE)")
                .contains("BUYER_SPENT = QBuyer.SPENT.withTable(OrderView.class, "
                        + "BUYER_SPEND.root().named(\"buyer\"))")
                .contains("BUYER_SCOPE = JoinScope.of(BUYER_TABLE).source(QBuyer.SPEND, BUYER_SPEND)"
                        + ".entity(QBuyer.LABEL, BUYER_LABEL)")
                // The outer model's sources first, then the nested ones depth-first (D-214): here only 'buyerSpend'.
                .contains("ModelQuery.Builder<OrderEntity, Long, OrderView> query( SourceQuery<Spend> buyerSpend)")
                .contains(".joinSource(BUYER_SPEND, buyerSpend).joinEntity(BUYER).joinEntity(BUYER_LABEL)");
        assertThat(java.util.List.of(generated.indexOf(" BUYER ="), generated.indexOf(" BUYER_TABLE ="),
                generated.indexOf(" BUYER_SPEND ="), generated.indexOf(" BUYER_SPENT ="),
                generated.indexOf(" BUYER_SCOPE =")))
                .doesNotContain(-1).isSortedAccordingTo(Integer::compare);
    }

    @Test
    void ac_proc_61_an_inner_nested_source_join_below_a_left_entity_join_is_mq3044() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "buyer", entity = CustomerEntity.class, on = @JoinEntity.On(column = "customerId"))
                public record OrderView(@PrimaryKey Long id, Long customerId,
                        @Join(entityJoin = "buyer") Optional<InnerBuyer> buyer) {}
                """, source("shop.InnerBuyer", INNER_BUYER));
        assertThat(errors(compilation)).containsExactly(DiagnosticCode.MQ3044.code() + ": OrderView.buyer: "
                + "InnerBuyer join 'spend' is INNER below the LEFT @Join 'buyer', which would drop the outer row "
                + "when it misses; make it LEFT, or the @Join INNER");
    }

    @Test
    void ac_proc_61_an_inner_nested_source_join_below_an_inner_entity_join_is_generated() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "buyer", entity = CustomerEntity.class, type = JoinKind.INNER,
                        on = @JoinEntity.On(column = "customerId"))
                public record OrderView(@PrimaryKey Long id, Long customerId,
                        @Join(entityJoin = "buyer") Optional<InnerBuyer> buyer) {}
                """, source("shop.InnerBuyer", INNER_BUYER));
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QOrderView"))
                .contains("BUYER_SPEND = SourceJoin.of(\"buyerSpend\", Spend.class, JoinKind.INNER, ");
    }

    @Test
    void ac_proc_61_a_child_over_a_model_with_a_nested_source_join_below_an_entity_join_is_mq3039() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = CustomerEntity.class)
                public record OrderView(@PrimaryKey Long id,
                        @Child(foreignKey = "customerId") java.util.List<Wrapper> orders) {}
                """, source("shop.Wrapper", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "buyer", entity = CustomerEntity.class, on = @JoinEntity.On(column = "customerId"))
                public record Wrapper(@PrimaryKey Long id, Long customerId,
                        @Join(entityJoin = "buyer") Optional<Buyer> buyer) {}
                """));
        // Wrapper itself compiles; a fetch plan's through load has no entity join and no source to pass.
        assertThat(errors(compilation)).noneMatch(error -> error.startsWith(DiagnosticCode.MQ3044.code()))
                .anySatisfy(error -> assertThat(error)
                        .startsWith(DiagnosticCode.MQ3039.code() + ": OrderView.orders: "));
    }

    @Test
    void ac_proc_60_a_nested_entity_join_below_a_source_anchored_entity_join_is_mq3044() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                @JoinSource(name = "tally", source = Tally.class,
                        on = @JoinSource.On(column = "customerId", key = "customerId"))
                @JoinEntity(name = "buyer", source = "tally", entity = CustomerEntity.class,
                        on = @JoinEntity.On(field = "customerId"))
                public record OrderView(@PrimaryKey Long id, Long customerId,
                        @Join(entityJoin = "buyer") Optional<Labelled> buyer) {}
                """, source("shop.Labelled", LABELLED.formatted("LEFT")));
        assertThat(errors(compilation)).containsExactly(DiagnosticCode.MQ3044.code() + ": OrderView.buyer: Labelled "
                + "joins 'label' below the entity join 'buyer', which is anchored on a joined source; only a join "
                + "anchored on the root's columns may anchor another");
    }

    @Test
    void ac_proc_60_an_inner_nested_entity_join_below_a_left_entity_join_is_mq3044() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "buyer", entity = CustomerEntity.class, on = @JoinEntity.On(column = "customerId"))
                public record OrderView(@PrimaryKey Long id, Long customerId,
                        @Join(entityJoin = "buyer") Optional<Labelled> buyer) {}
                """, source("shop.Labelled", LABELLED.formatted("INNER")));
        assertThat(errors(compilation)).containsExactly(DiagnosticCode.MQ3044.code() + ": OrderView.buyer: "
                + "Labelled join 'label' is INNER below the LEFT @Join 'buyer', which would drop the outer row when "
                + "it misses; make it LEFT, or the @Join INNER");
    }

    @Test
    void ac_gen_47_a_nested_entity_join_below_an_entity_join_anchors_on_its_root_after_it() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "buyer", entity = CustomerEntity.class, type = JoinKind.INNER,
                        on = @JoinEntity.On(column = "customerId"))
                public record OrderView(@PrimaryKey Long id, Long customerId,
                        @Join(entityJoin = "buyer") Optional<Labelled> buyer) {}
                """, source("shop.Labelled", LABELLED.formatted("INNER")));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated)
                .contains("BUYER_LABEL = EntityJoin.of(\"buyerLabel\", LabelEntity.class, JoinKind.INNER, "
                        + "List.of(EntityJoin.on(BUYER_ID, \"id\"))).within(BUYER_TABLE)")
                .contains("BUYER_LABEL_NAME = QLabelled.LABEL_NAME.withTable(OrderView.class, "
                        + "BUYER_LABEL.root().named(\"buyer\"))")
                .contains("BUYER_SCOPE = JoinScope.of(BUYER_TABLE).entity(QLabelled.LABEL, BUYER_LABEL)")
                .contains(".joinEntity(BUYER).joinEntity(BUYER_LABEL)");
        assertThat(java.util.List.of(generated.indexOf(" BUYER ="), generated.indexOf(" BUYER_ID ="),
                generated.indexOf(" BUYER_LABEL ="), generated.indexOf(" BUYER_LABEL_NAME =")))
                .doesNotContain(-1).isSortedAccordingTo(Integer::compare);
    }

    @Test
    void ac_gen_48_a_nested_chain_registers_each_join_after_the_one_it_reads() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<Chained> customer) {}
                """, source("shop.Chained", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = CustomerEntity.class)
                @JoinEntity(name = "label", entity = LabelEntity.class, on = @JoinEntity.On(column = "id"))
                @JoinEntity(name = "town", entity = CityEntity.class, on = @JoinEntity.On(column = "labelId"))
                public record Chained(@PrimaryKey Long id,
                        @Column(entityJoin = "label", attribute = "id") Long labelId,
                        @Column(entityJoin = "town", attribute = "name") String townName) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated)
                .contains("CUSTOMER_TOWN = EntityJoin.of(\"customerTown\", CityEntity.class, JoinKind.LEFT, "
                        + "List.of(EntityJoin.on(CUSTOMER_LABEL_ID, \"id\"))).within(CUSTOMER_TABLE)")
                .contains(".joinEntity(CUSTOMER_LABEL).joinEntity(CUSTOMER_TOWN)");
        assertThat(java.util.List.of(generated.indexOf(" CUSTOMER_LABEL ="), generated.indexOf(" CUSTOMER_LABEL_ID ="),
                generated.indexOf(" CUSTOMER_TOWN ="), generated.indexOf(" CUSTOMER_TOWN_NAME =")))
                .doesNotContain(-1).isSortedAccordingTo(Integer::compare);
    }

    @Test
    void ac_proc_55_a_nested_entity_join_column_through_an_association_is_mq3044() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<Deep> customer) {}
                """, source("shop.Deep", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = CustomerEntity.class)
                @JoinEntity(name = "label", entity = LabelEntity.class, on = @JoinEntity.On(column = "id"))
                public record Deep(@PrimaryKey Long id,
                        @Column(entityJoin = "label", attribute = "city.name") String cityName) {}
                """));
        assertThat(errors(compilation)).anySatisfy(error -> assertThat(error)
                .startsWith(DiagnosticCode.MQ3044.code() + ": OrderView.customer: Deep.CITY_NAME reads 'city.name' "
                        + "of the entity join 'label' through an association"));
    }

    @Test
    void ac_proc_57_a_child_over_a_model_with_a_nested_model_that_joins_a_source_is_mq3039() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = CustomerEntity.class)
                public record OrderView(@PrimaryKey Long id,
                        @Child(foreignKey = "customerId") java.util.List<Wrapper> orders) {}
                """, source("shop.Wrapper", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = OrderEntity.class)
                public record Wrapper(@PrimaryKey Long id, @Join Optional<Buyer> customer) {}
                """));
        // Wrapper nests a model with joins: that alone is no longer MQ3044, only the @Child over it is MQ3039.
        assertThat(errors(compilation)).noneMatch(error -> error.startsWith(DiagnosticCode.MQ3044.code()))
                .anySatisfy(error -> assertThat(error)
                .startsWith(DiagnosticCode.MQ3039.code() + ": OrderView.orders: ")
                .contains("Buyer joins 'spend'"));
    }

    @Test
    void ac_gen_45_a_subtype_below_a_nested_model_with_joins_composes_the_scope_instead_of_refusing_it() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<Holder> customer) {}
                """, source("shop.Holder", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = CustomerEntity.class)
                public record Holder(@PrimaryKey Long id, @Join(attribute = "place") Optional<Spot> place) {}
                """), source("shop.Spot", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = PlaceEntity.class)
                @JoinSource(name = "tally", source = Tally.class,
                        on = @JoinSource.On(column = "id", key = "customerId"))
                public record Spot(@PrimaryKey Long id, @Subtype Class<? extends PlaceEntity> kind,
                        @SourceColumn(join = "tally", field = "orders") Long orders) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated).contains("scoped.scoped(scope)").contains("CUSTOMER_PLACE_TALLY")
                .contains("row.scoped(CUSTOMER_SCOPE)").doesNotContain("UnsupportedOperationException");
    }

    @Test
    void ac_proc_56_a_nested_entity_join_anchored_on_a_filter_column_is_mq3044() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<Keyed> customer) {}
                """, source("shop.Keyed", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = CustomerEntity.class)
                @FilterColumn(name = "CODE", path = "id")
                @JoinEntity(name = "label", entity = LabelEntity.class, on = @JoinEntity.On(column = "CODE"))
                public record Keyed(@PrimaryKey Long id,
                        @Column(entityJoin = "label", attribute = "name") String labelName) {}
                """));
        assertThat(errors(compilation)).anySatisfy(error -> assertThat(error)
                .startsWith(DiagnosticCode.MQ3044.code() + ": OrderView.customer: Keyed join 'label' is anchored on "
                        + "the @FilterColumn 'CODE'"));
    }

    @Test
    void ac_proc_58_a_field_named_like_a_nested_join_constant_is_mq3015() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<Buyer> customer, Long customerSpend) {}
                """);
        assertThat(errors(compilation)).anySatisfy(error -> assertThat(error)
                .startsWith(DiagnosticCode.MQ3015.code() + ": OrderView.customerSpend: constant CUSTOMER_SPEND "
                        + "is also generated for join 'spend' of customer"));
    }

    private static final String INNER_BUYER = """
            package shop;
            import com.rey.modelquery.annotations.*;
            @QueryModel(root = CustomerEntity.class)
            @JoinSource(name = "spend", source = Spend.class, type = JoinKind.INNER,
                    on = @JoinSource.On(column = "id", key = "customerId"))
            public record InnerBuyer(@PrimaryKey Long id) {}
            """;

    @Test
    void ac_proc_59_an_inner_nested_join_below_a_left_join_is_mq3044() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<InnerBuyer> customer) {}
                """, source("shop.InnerBuyer", INNER_BUYER));
        assertThat(errors(compilation)).containsExactly(DiagnosticCode.MQ3044.code() + ": OrderView.customer: "
                + "InnerBuyer join 'spend' is INNER below the LEFT @Join 'customer', which would drop the outer row "
                + "when it misses; make it LEFT, or the @Join INNER");
    }

    @Test
    void ac_proc_59_an_inner_nested_join_below_an_inner_join_is_generated() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id,
                        @Join(type = JoinKind.INNER) Optional<InnerBuyer> customer) {}
                """, source("shop.InnerBuyer", INNER_BUYER));
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QOrderView")).contains("JoinKind.INNER");
    }

    @Test
    void ac_gen_46_a_nested_entity_join_anchored_on_a_source_column_is_built_in_place() {
        Compilation compilation = compileOrder("""
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<Twin> customer) {}
                """, source("shop.Twin", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = CustomerEntity.class)
                @JoinSource(name = "spend", source = Spend.class,
                        on = @JoinSource.On(column = "id", key = "customerId"))
                @JoinEntity(name = "twin", source = "spend", entity = CustomerEntity.class,
                        on = @JoinEntity.On(field = "customerId"))
                public record Twin(@PrimaryKey Long id,
                        @Column(entityJoin = "twin", attribute = "name") String twinName) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QOrderView"))
                .contains("ColumnField.over(OrderView.class, CUSTOMER_SPEND.root(), QSpend.CUSTOMER_ID)")
                .contains("CUSTOMER_TWIN = EntityJoin.of(\"customerTwin\"")
                .contains(".source(QTwin.SPEND, CUSTOMER_SPEND)").contains(".entity(QTwin.TWIN, CUSTOMER_TWIN)");
    }
}
