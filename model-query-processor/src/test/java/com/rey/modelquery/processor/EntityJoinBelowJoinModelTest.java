package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Join(entityJoin)} inside a model that is itself joined, under an entity join or an attribute join
 * (processor/31 R-GEN-43, AC-GEN-50, AC-GEN-51, D-227).
 */
class EntityJoinBelowJoinModelTest {

    private static final JavaFileObject ORDER_ENTITY = source("shop.OrderEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class OrderEntity {
                @Id Long id;
                Long customerId;
                @ManyToOne CustomerEntity customerRel;
            }
            """);

    private static final JavaFileObject CUSTOMER_ENTITY = source("shop.CustomerEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class CustomerEntity {
                @Id Long id;
                String name;
                Long regionId;
            }
            """);

    private static final JavaFileObject REGION_ENTITY = source("shop.RegionEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class RegionEntity {
                @Id Long id;
                String name;
                Long countryId;
            }
            """);

    private static final JavaFileObject COUNTRY_ENTITY = source("shop.CountryEntity", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class CountryEntity {
                @Id Long id;
                String name;
            }
            """);

    private static final JavaFileObject REGION_SUMMARY = source("shop.RegionSummary", """
            package shop;
            import com.rey.modelquery.annotations.*;
            @QueryModel(root = RegionEntity.class)
            public record RegionSummary(@PrimaryKey Long id, String name) {}
            """);

    private static final JavaFileObject CUSTOMER_SUMMARY = source("shop.CustomerSummary", """
            package shop;
            import com.rey.modelquery.annotations.*;
            import java.util.Optional;
            @QueryModel(root = CustomerEntity.class)
            @JoinEntity(name = "region", entity = RegionEntity.class, on = @JoinEntity.On(column = "regionRef"))
            public record CustomerSummary(
                    @PrimaryKey Long id,
                    String name,
                    @Column(attribute = "regionId") Long regionRef,
                    @Join(entityJoin = "region") Optional<RegionSummary> regionRow) {}
            """);

    @Test
    void ac_gen_50_an_entity_join_below_an_entity_join_is_generated_on_its_root() {
        Compilation compilation = compile(ORDER_ENTITY, CUSTOMER_ENTITY, REGION_ENTITY, REGION_SUMMARY,
                CUSTOMER_SUMMARY, source("shop.OrderView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "customer", entity = CustomerEntity.class,
                        on = @JoinEntity.On(column = "customerRef"))
                public record OrderView(
                        @PrimaryKey Long id,
                        @Column(attribute = "customerId") Long customerRef,
                        @Join(entityJoin = "customer") Optional<CustomerSummary> customer) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated)
                .contains("CUSTOMER_REGION_ROW_TABLE = CUSTOMER_REGION.root().named(\"regionRow\")"
                        + ".presentBy(QRegionSummary.KEY)")
                .contains("CUSTOMER_SCOPE = JoinScope.of(CUSTOMER_TABLE)")
                .contains(".entity(QCustomerSummary.REGION, CUSTOMER_REGION)");
        assertThat(List.of(
                generated.indexOf(" CUSTOMER ="),
                generated.indexOf(" CUSTOMER_REGION ="),
                generated.indexOf(" CUSTOMER_REGION_ROW_TABLE ="),
                generated.indexOf(" CUSTOMER_REGION_ROW_NAME =")))
                .doesNotContain(-1).isSortedAccordingTo(Integer::compare);
    }

    @Test
    void ac_gen_50_an_entity_join_below_an_attribute_join_is_generated_on_its_root() {
        Compilation compilation = compile(ORDER_ENTITY, CUSTOMER_ENTITY, REGION_ENTITY, REGION_SUMMARY,
                CUSTOMER_SUMMARY, source("shop.OrderView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = OrderEntity.class)
                public record OrderView(
                        @PrimaryKey Long id,
                        @Join(attribute = "customerRel") Optional<CustomerSummary> customer) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated)
                .contains("CUSTOMER_REGION_ROW_TABLE = CUSTOMER_REGION.root().named(\"regionRow\")"
                        + ".presentBy(QRegionSummary.KEY)")
                .contains("CUSTOMER_SCOPE = JoinScope.of(CUSTOMER_TABLE)")
                .contains(".entity(QCustomerSummary.REGION, CUSTOMER_REGION)");
        assertThat(List.of(
                generated.indexOf(" CUSTOMER_TABLE ="),
                generated.indexOf(" CUSTOMER_REGION ="),
                generated.indexOf(" CUSTOMER_REGION_ROW_TABLE ="),
                generated.indexOf(" CUSTOMER_REGION_ROW_NAME =")))
                .doesNotContain(-1).isSortedAccordingTo(Integer::compare);
    }

    @Test
    void ac_gen_50_three_levels_of_entity_joins_are_generated_in_declaration_order() {
        JavaFileObject countrySummary = source("shop.CountrySummary", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = CountryEntity.class)
                public record CountrySummary(@PrimaryKey Long id, String name) {}
                """);
        JavaFileObject regionWithCountry = source("shop.RegionSummary", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = RegionEntity.class)
                @JoinEntity(name = "country", entity = CountryEntity.class,
                        on = @JoinEntity.On(column = "countryRef"))
                public record RegionSummary(
                        @PrimaryKey Long id,
                        String name,
                        @Column(attribute = "countryId") Long countryRef,
                        @Join(entityJoin = "country") Optional<CountrySummary> countryRow) {}
                """);
        JavaFileObject customerSummaryWith3Levels = source("shop.CustomerSummary", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = CustomerEntity.class)
                @JoinEntity(name = "region", entity = RegionEntity.class, on = @JoinEntity.On(column = "regionRef"))
                public record CustomerSummary(
                        @PrimaryKey Long id,
                        String name,
                        @Column(attribute = "regionId") Long regionRef,
                        @Join(entityJoin = "region") Optional<RegionSummary> regionRow) {}
                """);
        Compilation compilation = compile(ORDER_ENTITY, CUSTOMER_ENTITY, REGION_ENTITY, COUNTRY_ENTITY,
                countrySummary, regionWithCountry, customerSummaryWith3Levels, source("shop.OrderView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "customer", entity = CustomerEntity.class,
                        on = @JoinEntity.On(column = "customerRef"))
                public record OrderView(
                        @PrimaryKey Long id,
                        @Column(attribute = "customerId") Long customerRef,
                        @Join(entityJoin = "customer") Optional<CustomerSummary> customer) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated)
                .contains("CUSTOMER_REGION_ROW_TABLE = CUSTOMER_REGION.root().named(\"regionRow\")"
                        + ".presentBy(QRegionSummary.KEY)")
                .contains("CUSTOMER_REGION_ROW_COUNTRY_ROW_TABLE = CUSTOMER_REGION_ROW_COUNTRY.root()"
                        + ".named(\"countryRow\").presentBy(QCountrySummary.KEY)");
        assertThat(List.of(
                generated.indexOf(" CUSTOMER ="),
                generated.indexOf(" CUSTOMER_REGION ="),
                generated.indexOf(" CUSTOMER_REGION_ROW_TABLE ="),
                generated.indexOf(" CUSTOMER_REGION_ROW_COUNTRY ="),
                generated.indexOf(" CUSTOMER_REGION_ROW_COUNTRY_ROW_TABLE ="),
                generated.indexOf(" CUSTOMER_REGION_ROW_COUNTRY_ROW_NAME =")))
                .doesNotContain(-1).isSortedAccordingTo(Integer::compare);
    }

    @Test
    void ac_gen_51_a_nested_field_named_like_its_entity_join_generates_set_constant_without_collision() {
        JavaFileObject customerSameName = source("shop.CustomerSummary", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = CustomerEntity.class)
                @JoinEntity(name = "region", entity = RegionEntity.class, on = @JoinEntity.On(column = "regionRef"))
                public record CustomerSummary(
                        @PrimaryKey Long id,
                        String name,
                        @Column(attribute = "regionId") Long regionRef,
                        @Join(entityJoin = "region") Optional<RegionSummary> region) {}
                """);
        Compilation compilation = compile(ORDER_ENTITY, CUSTOMER_ENTITY, REGION_ENTITY, REGION_SUMMARY,
                customerSameName, source("shop.OrderView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = OrderEntity.class)
                @JoinEntity(name = "customer", entity = CustomerEntity.class,
                        on = @JoinEntity.On(column = "customerRef"))
                public record OrderView(
                        @PrimaryKey Long id,
                        @Column(attribute = "customerId") Long customerRef,
                        @Join(entityJoin = "customer") Optional<CustomerSummary> customer) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated)
                .contains("CUSTOMER_REGION_SET = SelectSet.of(")
                .contains("CUSTOMER_REGION_TABLE = CUSTOMER_REGION.root().named(\"region\")"
                        + ".presentBy(QRegionSummary.KEY)");
    }
}
