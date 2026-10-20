package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.core.TreatUse;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.SecondaryTable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.DiscriminatorFormula;
import org.hibernate.annotations.DiscriminatorOptions;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The mapping variants of AC-VND-18: each keeps the table less the cells its runs didn't measure (R-VND-26). */
class HibernateTreatVariantTest {

    @Entity(name = "VtForcePayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorOptions(force = true)
    static class ForcePayment {
        @Id Long id;
    }

    @Entity(name = "VtForceCard")
    static class ForceCard extends ForcePayment {
        String number;
        @Column(name = "code") String code;
    }

    @Entity(name = "VtForceBank")
    static class ForceBank extends ForcePayment {
        @Column(name = "code") String code;
    }

    @Entity(name = "VtFormulaPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorFormula("kind")
    @DiscriminatorValue("P")
    static class FormulaPayment {
        @Id Long id;
        String kind;
    }

    @Entity(name = "VtFormulaCard")
    @DiscriminatorValue("C")
    static class FormulaCard extends FormulaPayment {
        String number;
    }

    @Entity(name = "VtSecondaryPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class SecondaryPayment {
        @Id Long id;
    }

    @Entity(name = "VtSecondaryCard")
    @SecondaryTable(name = "vt_secondary_card_extra")
    static class SecondaryCard extends SecondaryPayment {
        @Column(table = "vt_secondary_card_extra") String number;
    }

    @Entity(name = "VtExplicitPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind")
    static class ExplicitPayment {
        @Id Long id;
    }

    @Entity(name = "VtExplicitCard")
    static class ExplicitCard extends ExplicitPayment {
        String number;
    }

    @Entity(name = "VtJoinedPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    static class JoinedPayment {
        @Id Long id;
    }

    @Entity(name = "VtJoinedCard")
    static class JoinedCard extends JoinedPayment {
        String number;
    }

    @Entity(name = "VtForcedJoinedPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind")
    @DiscriminatorOptions(force = true)
    static class ForcedJoinedPayment {
        @Id Long id;
    }

    @Entity(name = "VtForcedJoinedCard")
    static class ForcedJoinedCard extends ForcedJoinedPayment {
        String number;
    }

    @Entity(name = "VtUnforcedPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorOptions(force = false, insert = false)
    static class UnforcedPayment {
        @Id Long id;
        @Column(name = "dtype") String dtype;
    }

    @Entity(name = "VtUnforcedCard")
    static class UnforcedCard extends UnforcedPayment {
        String number;
    }

    private static SessionFactory variants;
    private static SessionFactory implicit;
    private static SessionFactory ignoring;

    @BeforeAll
    static void start() {
        variants = factory("jdbc:h2:mem:treat-variants", null, ForcePayment.class, ForceCard.class, ForceBank.class,
                FormulaPayment.class, FormulaCard.class, SecondaryPayment.class, SecondaryCard.class,
                ExplicitPayment.class, ExplicitCard.class, JoinedPayment.class, JoinedCard.class,
                ForcedJoinedPayment.class, ForcedJoinedCard.class, UnforcedPayment.class, UnforcedCard.class);
        implicit = factory("jdbc:h2:mem:treat-variants-implicit", "hibernate.discriminator.implicit_for_joined",
                JoinedPayment.class, JoinedCard.class);
        ignoring = factory("jdbc:h2:mem:treat-variants-ignoring",
                "hibernate.discriminator.ignore_explicit_for_joined", ExplicitPayment.class, ExplicitCard.class);
    }

    @AfterAll
    static void stop() {
        variants.close();
        implicit.close();
        ignoring.close();
    }

    private static SessionFactory factory(String url, String setting, Class<?>... entities) {
        Configuration cfg = new Configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, url)
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        if (setting != null) {
            cfg.setProperty(setting, "true");
        }
        for (Class<?> entity : entities) {
            cfg.addAnnotatedClass(entity);
        }
        return cfg.buildSessionFactory();
    }

    private static HibernateTreatRenderer renderer(SessionFactory factory) {
        return new HibernateTreatRenderer(factory.unwrap(SessionFactoryImplementor.class));
    }

    private static TreatSite site(Class<?> entity, Class<?> subtype, String attribute, StatementShape shape,
            TreatUse... uses) {
        return new TreatSite(entity, subtype, Optional.of(attribute), shape, true, Set.of(uses));
    }

    private record Variant(SessionFactory factory, Class<?> entity, Class<?> subtype) {}

    @Test
    void ac_vnd_18_each_mapping_variant_keeps_the_table_less_its_unmeasured_cells() {
        for (var variant : List.of(new Variant(variants, ForcePayment.class, ForceCard.class),
                new Variant(variants, FormulaPayment.class, FormulaCard.class),
                new Variant(variants, SecondaryPayment.class, SecondaryCard.class),
                new Variant(variants, ExplicitPayment.class, ExplicitCard.class),
                new Variant(implicit, JoinedPayment.class, JoinedCard.class),
                new Variant(ignoring, ExplicitPayment.class, ExplicitCard.class))) {
            var renderer = renderer(variant.factory());
            String name = variant.subtype().getSimpleName() + " in " + variant.factory();
            for (var admitted : List.of(
                    site(variant.entity(), variant.subtype(), "number", StatementShape.CONTENT, TreatUse.SELECT),
                    site(variant.entity(), variant.subtype(), "number", StatementShape.CONTENT, TreatUse.NULL_TEST),
                    site(variant.entity(), variant.subtype(), "number", StatementShape.CONTENT, TreatUse.SELECT,
                            TreatUse.AGGREGATE, TreatUse.SUBQUERY),
                    site(variant.entity(), variant.subtype(), "number", StatementShape.COUNT, TreatUse.PREDICATE),
                    site(variant.entity(), variant.subtype(), "number", StatementShape.COUNT, TreatUse.NULL_TEST,
                            TreatUse.DISJUNCTION))) {
                assertThat(renderer.refusal(admitted)).as(name + " " + admitted).isEmpty();
            }
            for (var refused : List.of(
                    site(variant.entity(), variant.subtype(), "number", StatementShape.COUNT, TreatUse.NULL_TEST),
                    site(variant.entity(), variant.subtype(), "number", StatementShape.COUNT, TreatUse.NULL_TEST,
                            TreatUse.NEGATED),
                    site(variant.entity(), variant.subtype(), "number", StatementShape.COUNT, TreatUse.PREDICATE,
                            TreatUse.NULL_TEST, TreatUse.DISJUNCTION))) {
                assertThat(renderer.refusal(refused)).as(name + " " + refused)
                        .hasValueSatisfying(text -> assertThat(text).contains("mapping variant"));
            }
        }
    }

    @Test
    void ac_col_49_a_joined_type_order_is_admitted_only_where_the_persister_maps_a_discriminator() {
        var order = new TreatSite(JoinedPayment.class, JoinedPayment.class, Optional.empty(), StatementShape.CONTENT,
                true, Set.of(TreatUse.TYPE, TreatUse.ORDER));
        assertThat(renderer(implicit).refusal(order)).isEmpty();
        assertThat(renderer(variants).refusal(new TreatSite(ExplicitPayment.class, ExplicitPayment.class,
                Optional.empty(), StatementShape.CONTENT, true, Set.of(TreatUse.TYPE, TreatUse.ORDER)))).isEmpty();
        // Without a setting, or with the explicit column ignored, the type is a CASE over the subtype tables.
        assertThat(renderer(variants).refusal(order))
                .hasValueSatisfying(text -> assertThat(text).contains("without a discriminator"));
        assertThat(renderer(ignoring).refusal(new TreatSite(ExplicitPayment.class, ExplicitPayment.class,
                Optional.empty(), StatementShape.CONTENT, true, Set.of(TreatUse.TYPE, TreatUse.ORDER))))
                .hasValueSatisfying(text -> assertThat(text).contains("without a discriminator"));
    }

    @Test
    void ac_vnd_18_the_core_mappings_keep_the_cells_a_variant_lacks() {
        // Without either setting a JOINED hierarchy is no variant: its root-id-counted null tests are admitted.
        var renderer = renderer(variants);
        assertThat(renderer.refusal(site(JoinedPayment.class, JoinedCard.class, "number", StatementShape.COUNT,
                TreatUse.NULL_TEST))).isEmpty();
        assertThat(renderer.refusal(site(JoinedPayment.class, JoinedCard.class, "number", StatementShape.CONTENT,
                TreatUse.SELECT, TreatUse.AGGREGATE, TreatUse.SUBQUERY))).isEmpty();
    }

    @Test
    void ac_vnd_18_a_shared_column_in_a_variant_and_a_variant_off_single_table_are_refused() {
        var renderer = renderer(variants);
        assertThat(renderer.refusal(site(ForcePayment.class, ForceCard.class, "code", StatementShape.CONTENT,
                TreatUse.SELECT))).hasValueSatisfying(text -> assertThat(text).contains("shared column"));
        assertThat(renderer.refusal(site(ForcedJoinedPayment.class, ForcedJoinedCard.class, "number",
                StatementShape.CONTENT, TreatUse.SELECT)))
                .hasValueSatisfying(text -> assertThat(text).contains("SINGLE_TABLE only"));
        assertThat(renderer.refusal(site(UnforcedPayment.class, UnforcedCard.class, "number",
                StatementShape.CONTENT, TreatUse.SELECT)))
                .hasValueSatisfying(text -> assertThat(text).contains("other than force = true"));
    }
}
