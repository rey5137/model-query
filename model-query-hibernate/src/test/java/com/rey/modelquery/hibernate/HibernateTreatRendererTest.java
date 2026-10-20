package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.core.TreatUse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.Root;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Hibernate treat allow-list, shared-column keying, treat dispatch and the line/compliance gate (R-VND-26). */
class HibernateTreatRendererTest {

    @Entity(name = "StPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class StPayment {
        @Id Long id;
        String status;
    }

    @Entity(name = "StCard")
    static class StCard extends StPayment {
        String number;
        @Column(name = "reference") String last4;
        String cvv;
        @ManyToOne @JoinColumn(name = "owner_id") StHolder owner;
    }

    @Entity(name = "StBank")
    static class StBank extends StPayment {
        String number;
        @Column(name = "reference") String iban;
        @ManyToOne @JoinColumn(name = "owner_id") StHolder owner;
    }

    @Entity(name = "StHolder")
    static class StHolder {
        @Id Long id;
        String name;
    }

    @Entity(name = "JdPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    static class JdPayment {
        @Id Long id;
        String status;
    }

    @Entity(name = "JdCard")
    static class JdCard extends JdPayment {
        String number;
        @ManyToOne JdHolder owner;
    }

    @Entity(name = "JdBank")
    static class JdBank extends JdPayment {
        String iban;
    }

    @Entity(name = "JdHolder")
    static class JdHolder {
        @Id Long id;
        @OneToMany List<JdPayment> payments;
    }

    private static SessionFactory joined;
    private static SessionFactory single;

    @BeforeAll
    static void start() {
        joined = factory("jdbc:h2:mem:treat-renderer-joined", JdPayment.class, JdCard.class, JdBank.class,
                JdHolder.class);
        single = factory("jdbc:h2:mem:treat-renderer-single", StPayment.class, StCard.class, StBank.class,
                StHolder.class);
    }

    @AfterAll
    static void stop() {
        joined.close();
        single.close();
    }

    private static SessionFactory factory(String url, Class<?>... entities) {
        Configuration cfg = new Configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, url)
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        for (Class<?> entity : entities) {
            cfg.addAnnotatedClass(entity);
        }
        return cfg.buildSessionFactory();
    }

    private static HibernateTreatRenderer renderer(SessionFactory factory) {
        return new HibernateTreatRenderer(factory.unwrap(SessionFactoryImplementor.class));
    }

    private static TreatSite site(Class<?> entity, Class<?> subtype, String attribute, StatementShape shape,
            boolean anchored, TreatUse... uses) {
        return new TreatSite(entity, subtype, Optional.ofNullable(attribute), shape, anchored, Set.of(uses));
    }

    private static TreatSite card(StatementShape shape, TreatUse... uses) {
        return site(JdPayment.class, JdCard.class, "number", shape, true, uses);
    }

    private static void assertRefused(TreatRenderer renderer, TreatSite site, String reason) {
        assertThat(renderer.refusal(site)).hasValueSatisfying(text -> assertThat(text).contains(reason));
    }

    @Test
    void ac_vnd_17_content_cells_are_admitted_by_use_set() {
        var renderer = renderer(joined);
        for (var uses : List.of(EnumSet.of(TreatUse.SELECT), EnumSet.of(TreatUse.ORDER),
                EnumSet.of(TreatUse.SELECT, TreatUse.EXPRESSION), EnumSet.of(TreatUse.ORDER, TreatUse.EXPRESSION),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.EXPRESSION), EnumSet.of(TreatUse.PREDICATE),
                EnumSet.of(TreatUse.NULL_TEST, TreatUse.NEGATED),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.NEGATED, TreatUse.DISJUNCTION, TreatUse.SUBQUERY),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.NULL_TEST),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.NULL_TEST, TreatUse.SUBQUERY),
                EnumSet.of(TreatUse.SELECT, TreatUse.LEFT_JOIN), EnumSet.of(TreatUse.ORDER, TreatUse.INNER_JOIN),
                EnumSet.of(TreatUse.NULL_TEST, TreatUse.NEGATED, TreatUse.LEFT_JOIN),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.NULL_TEST, TreatUse.INNER_JOIN),
                EnumSet.of(TreatUse.SELECT, TreatUse.AGGREGATE, TreatUse.SUBQUERY))) {
            assertThat(renderer.refusal(card(StatementShape.CONTENT, uses.toArray(TreatUse[]::new))))
                    .as(uses.toString()).isEmpty();
        }
    }

    @Test
    void ac_vnd_17_a_content_use_set_the_spike_did_not_measure_is_refused() {
        var renderer = renderer(joined);
        assertRefused(renderer, card(StatementShape.CONTENT, TreatUse.SELECT, TreatUse.AGGREGATE), "unmeasured");
        assertRefused(renderer, card(StatementShape.CONTENT, TreatUse.SELECT, TreatUse.EXPRESSION,
                TreatUse.LEFT_JOIN), "unmeasured");
        assertRefused(renderer, card(StatementShape.CONTENT, TreatUse.PREDICATE, TreatUse.SUBQUERY,
                TreatUse.INNER_JOIN), "unmeasured");
        assertRefused(renderer, card(StatementShape.CONTENT, TreatUse.SELECT, TreatUse.AGGREGATE,
                TreatUse.SUBQUERY, TreatUse.INNER_JOIN), "unmeasured");
        // Anchored, a LEFT-joined correlated count treats twice: the spike's count read it from an untreated root.
        assertRefused(renderer, card(StatementShape.CONTENT, TreatUse.SELECT, TreatUse.AGGREGATE,
                TreatUse.SUBQUERY, TreatUse.LEFT_JOIN), "unmeasured");
        assertRefused(renderer, site(JdPayment.class, JdCard.class, null, StatementShape.CONTENT, true,
                TreatUse.INNER_JOIN), "treated join");
    }

    @Test
    void ac_vnd_19_a_null_test_with_subquery_alone_has_no_tck_case_and_is_refused() {
        // A null test under a path exists also reports the exists' PREDICATE, so no definition reaches these.
        for (var shape : List.of(StatementShape.CONTENT, StatementShape.COUNT)) {
            for (var uses : List.of(EnumSet.of(TreatUse.NULL_TEST, TreatUse.SUBQUERY),
                    EnumSet.of(TreatUse.NULL_TEST, TreatUse.NEGATED, TreatUse.SUBQUERY),
                    EnumSet.of(TreatUse.NULL_TEST, TreatUse.DISJUNCTION, TreatUse.SUBQUERY),
                    EnumSet.of(TreatUse.NULL_TEST, TreatUse.NEGATED, TreatUse.DISJUNCTION, TreatUse.SUBQUERY))) {
                assertRefused(renderer(joined), card(shape, uses.toArray(TreatUse[]::new)), "unmeasured");
                for (String attribute : List.of("cvv", "number")) {
                    assertRefused(renderer(single), site(StPayment.class, StCard.class, attribute, shape, true,
                            uses.toArray(TreatUse[]::new)), "unmeasured");
                }
            }
        }
    }

    @Test
    void ac_vnd_17_count_cells_admit_predicates_and_kept_joins_but_not_selection_or_expression() {
        var renderer = renderer(joined);
        for (var uses : List.of(EnumSet.of(TreatUse.PREDICATE), EnumSet.of(TreatUse.NULL_TEST),
                EnumSet.of(TreatUse.NULL_TEST, TreatUse.NEGATED),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.NULL_TEST),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.NEGATED, TreatUse.DISJUNCTION, TreatUse.SUBQUERY),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.LEFT_JOIN),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.NULL_TEST, TreatUse.INNER_JOIN))) {
            assertThat(renderer.refusal(card(StatementShape.COUNT, uses.toArray(TreatUse[]::new))))
                    .as(uses.toString()).isEmpty();
        }
        assertThat(renderer.refusal(site(JdPayment.class, JdCard.class, null, StatementShape.COUNT, true,
                TreatUse.INNER_JOIN))).isEmpty();
        assertThat(renderer.refusal(site(JdPayment.class, JdCard.class, null, StatementShape.COUNT, true,
                TreatUse.LEFT_JOIN))).isEmpty();
        assertRefused(renderer, card(StatementShape.COUNT, TreatUse.SELECT), "unmeasured");
        assertRefused(renderer, card(StatementShape.COUNT, TreatUse.ORDER), "unmeasured");
        assertRefused(renderer, card(StatementShape.COUNT, TreatUse.PREDICATE, TreatUse.EXPRESSION), "unmeasured");
        assertRefused(renderer, card(StatementShape.COUNT, TreatUse.SELECT, TreatUse.AGGREGATE, TreatUse.SUBQUERY),
                "unmeasured");
    }

    @Test
    void ac_vnd_17_unanchored_other_shapes_type_and_correlation_are_refused() {
        var renderer = renderer(joined);
        assertRefused(renderer, site(JdPayment.class, JdCard.class, "number", StatementShape.CONTENT, false,
                TreatUse.SELECT), "only through the treat");
        assertRefused(renderer, site(JdPayment.class, JdCard.class, "number", StatementShape.COUNT, false,
                TreatUse.PREDICATE), "only through the treat");
        for (var shape : List.of(StatementShape.GROUPED, StatementShape.GROUPED_COUNT, StatementShape.SOURCE_BODY,
                StatementShape.CHILD, StatementShape.WRITE)) {
            assertRefused(renderer, card(shape, TreatUse.PREDICATE), "no " + shape + " cell");
        }
        assertRefused(renderer, card(StatementShape.CONTENT, TreatUse.SELECT, TreatUse.SUBQUERY,
                TreatUse.CORRELATION), "correlated on a treated outer node");
    }

    private static TreatSite type(Class<?> entity, StatementShape shape, boolean anchored, TreatUse... uses) {
        var all = EnumSet.of(TreatUse.TYPE, uses);
        return new TreatSite(entity, entity, Optional.empty(), shape, anchored, Set.copyOf(all));
    }

    @Test
    void ac_col_49_type_cells_are_admitted_by_shape_and_use_set_as_measured() {
        var left = new TreatUse[] {TreatUse.PREDICATE, TreatUse.NEGATED, TreatUse.NULL_TEST, TreatUse.DISJUNCTION,
            TreatUse.LEFT_JOIN};
        for (var factory : List.of(single, joined)) {
            var renderer = renderer(factory);
            Class<?> root = factory == single ? StPayment.class : JdPayment.class;
            for (var admitted : List.of(type(root, StatementShape.CONTENT, true, TreatUse.SELECT),
                    type(root, StatementShape.CONTENT, true, TreatUse.PREDICATE),
                    type(root, StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION),
                    type(root, StatementShape.CONTENT, true, left),
                    type(root, StatementShape.COUNT, true, TreatUse.PREDICATE),
                    type(root, StatementShape.COUNT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION),
                    type(root, StatementShape.COUNT, true, left),
                    type(root, StatementShape.GROUPED, false, TreatUse.SELECT, TreatUse.GROUP_BY),
                    type(root, StatementShape.GROUPED, false, TreatUse.GROUP_BY))) {
                assertThat(renderer.refusal(admitted)).as(root.getSimpleName() + " " + admitted).isEmpty();
            }
            // A LEFT-joined node's type in the select fails on both lines; the rest was never measured.
            for (var refused : List.of(type(root, StatementShape.CONTENT, true, TreatUse.SELECT, TreatUse.LEFT_JOIN),
                    type(root, StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.INNER_JOIN),
                    type(root, StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.LEFT_JOIN),
                    type(root, StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.NEGATED),
                    type(root, StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.SUBQUERY),
                    type(root, StatementShape.CONTENT, true, TreatUse.SELECT, TreatUse.EXPRESSION),
                    type(root, StatementShape.COUNT, true, TreatUse.SELECT),
                    type(root, StatementShape.GROUPED, false, TreatUse.ORDER),
                    type(root, StatementShape.GROUPED, false, TreatUse.PREDICATE))) {
                assertRefused(renderer, refused, "unmeasured use set for a type read");
            }
            assertRefused(renderer, type(root, StatementShape.CONTENT, false, TreatUse.SELECT),
                    "doesn't read its root");
            for (var shape : List.of(StatementShape.SOURCE_BODY, StatementShape.CHILD, StatementShape.WRITE)) {
                assertRefused(renderer, type(root, shape, false, TreatUse.PREDICATE), "no " + shape + " cell");
            }
            assertRefused(renderer, type(String.class, StatementShape.CONTENT, true, TreatUse.SELECT),
                    "not a mapped entity");
        }
    }

    @Test
    void ac_col_49_a_type_order_is_refused_on_a_joined_root_without_a_discriminator() {
        assertThat(renderer(single).refusal(type(StPayment.class, StatementShape.CONTENT, true, TreatUse.ORDER)))
                .isEmpty();
        assertRefused(renderer(joined), type(JdPayment.class, StatementShape.CONTENT, true, TreatUse.ORDER),
                "JOINED root without a discriminator");
    }

    @Test
    void ac_exe_03_a_count_of_type_groups_is_refused_on_a_joined_root_without_a_discriminator() {
        var singleTable = renderer(single);
        for (var uses : List.of(new TreatUse[] {TreatUse.SELECT, TreatUse.GROUP_BY},
                new TreatUse[] {TreatUse.GROUP_BY})) {
            assertThat(singleTable.refusal(type(StPayment.class, StatementShape.GROUPED_COUNT, false, uses))).isEmpty();
            // createCountQuery drops the subtype joins there, so the engine counts the groups client-side (D-159).
            assertRefused(renderer(joined), type(JdPayment.class, StatementShape.GROUPED_COUNT, false, uses),
                    "counted client-side");
            assertThat(renderer(joined).refusal(type(JdPayment.class, StatementShape.GROUPED, false, uses))).isEmpty();
        }
        assertRefused(singleTable, type(StPayment.class, StatementShape.GROUPED_COUNT, false, TreatUse.PREDICATE),
                "unmeasured use set for a type read");
    }

    @Test
    void ac_col_51_a_single_table_column_a_sibling_maps_is_admitted_only_in_its_measured_cells() {
        var renderer = renderer(single);
        for (String attribute : List.of("number", "last4")) {
            for (var uses : List.of(EnumSet.of(TreatUse.SELECT), EnumSet.of(TreatUse.ORDER),
                    EnumSet.of(TreatUse.SELECT, TreatUse.EXPRESSION), EnumSet.of(TreatUse.NULL_TEST),
                    EnumSet.of(TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.NULL_TEST))) {
                assertThat(renderer.refusal(site(StPayment.class, StCard.class, attribute, StatementShape.CONTENT,
                        true, uses.toArray(TreatUse[]::new)))).as(attribute + uses).isEmpty();
            }
            assertThat(renderer.refusal(site(StPayment.class, StCard.class, attribute, StatementShape.COUNT, true,
                    TreatUse.PREDICATE, TreatUse.DISJUNCTION))).isEmpty();
            assertRefused(renderer, site(StPayment.class, StCard.class, attribute, StatementShape.CONTENT, true,
                    TreatUse.SELECT, TreatUse.LEFT_JOIN), "another entity of the hierarchy maps");
            assertRefused(renderer, site(StPayment.class, StCard.class, attribute, StatementShape.COUNT, true,
                    TreatUse.NULL_TEST), "another entity of the hierarchy maps");
            assertRefused(renderer, site(StPayment.class, StCard.class, attribute, StatementShape.COUNT, true,
                    TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.NULL_TEST), "another entity");
        }
        assertThat(renderer.refusal(site(StPayment.class, StCard.class, "cvv", StatementShape.COUNT, true,
                TreatUse.NULL_TEST))).isEmpty();
        assertThat(renderer.refusal(site(StPayment.class, StCard.class, "status", StatementShape.CONTENT, true,
                TreatUse.SELECT, TreatUse.LEFT_JOIN))).isEmpty();
    }

    @Test
    void ac_col_51_a_shared_column_count_admits_only_the_or_cells() {
        var renderer = renderer(single);
        for (var uses : List.of(EnumSet.of(TreatUse.PREDICATE, TreatUse.DISJUNCTION),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.NEGATED, TreatUse.DISJUNCTION),
                EnumSet.of(TreatUse.NULL_TEST, TreatUse.DISJUNCTION),
                EnumSet.of(TreatUse.NULL_TEST, TreatUse.NEGATED, TreatUse.DISJUNCTION))) {
            assertThat(renderer.refusal(site(StPayment.class, StCard.class, "number", StatementShape.COUNT, true,
                    uses.toArray(TreatUse[]::new)))).as(uses.toString()).isEmpty();
        }
        // A standalone predicate passed the count path too, but the RFC admits only the or and exists cells, and the
        // exists cells have no TCK case (D-156).
        for (var uses : List.of(EnumSet.of(TreatUse.PREDICATE), EnumSet.of(TreatUse.PREDICATE, TreatUse.NEGATED),
                EnumSet.of(TreatUse.NULL_TEST), EnumSet.of(TreatUse.NULL_TEST, TreatUse.NEGATED),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.NULL_TEST, TreatUse.DISJUNCTION),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.SUBQUERY),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.SUBQUERY),
                EnumSet.of(TreatUse.PREDICATE, TreatUse.NULL_TEST, TreatUse.DISJUNCTION, TreatUse.SUBQUERY))) {
            assertRefused(renderer, site(StPayment.class, StCard.class, "number", StatementShape.COUNT, true,
                    uses.toArray(TreatUse[]::new)), "another entity of the hierarchy maps");
        }
        // The same standalone predicate on a column no sibling maps is admitted.
        assertThat(renderer.refusal(site(StPayment.class, StCard.class, "cvv", StatementShape.COUNT, true,
                TreatUse.PREDICATE))).isEmpty();
    }

    @Test
    void ac_col_51_a_path_through_a_foreign_key_a_sibling_shares_is_refused() {
        var renderer = renderer(single);
        // StCard.owner and StBank.owner both map owner_id: a treated read through it could read the bank's owner.
        for (var shape : List.of(StatementShape.CONTENT, StatementShape.COUNT)) {
            for (String attribute : List.of("owner.name", "owner.id", "owner")) {
                assertRefused(renderer, site(StPayment.class, StCard.class, attribute, shape, true,
                        TreatUse.PREDICATE, TreatUse.DISJUNCTION), "a join or path through a column another entity");
            }
        }
        assertRefused(renderer, site(StPayment.class, StCard.class, "owner.name", StatementShape.CONTENT, true,
                TreatUse.SELECT), "a join or path through a column another entity");
        // The shared column read itself is the AC-COL-51 cell; a path no segment of which resolves is refused.
        assertThat(renderer.refusal(site(StPayment.class, StCard.class, "number", StatementShape.CONTENT, true,
                TreatUse.SELECT))).isEmpty();
        assertRefused(renderer, site(StPayment.class, StCard.class, "missing.name", StatementShape.CONTENT, true,
                TreatUse.SELECT), "StCard.missing maps no column");
        assertRefused(renderer, site(StPayment.class, StCard.class, "missing", StatementShape.CONTENT, true,
                TreatUse.SELECT), "StCard.missing maps no column");
        // Off SINGLE_TABLE no column is shared: a dotted path takes the core cells.
        assertThat(renderer(joined).refusal(site(JdPayment.class, JdCard.class, "owner.name",
                StatementShape.CONTENT, true, TreatUse.SELECT))).isEmpty();
    }

    @Test
    void ac_vnd_16_treats_builds_one_renderer_per_factory() {
        var support = new HibernateProviderSupport();
        TreatRenderer first = support.treats(joined).orElseThrow();
        assertThat(support.treats(joined)).containsSame(first);
        assertThat(new HibernateProviderSupport().treats(joined)).containsSame(first);
        assertThat(support.treats(single)).hasValueSatisfying(other -> assertThat(other).isNotSameAs(first));
        // The line check stays in front of the cache.
        assertThat(HibernateProviderSupport.treats(joined, "7.5.0.Final")).isEmpty();
    }

    @Test
    void ac_vnd_16_treats_serves_the_measured_lines_only() {
        assertThat(HibernateProviderSupport.treatsLine("6.6.58.Final")).isTrue();
        assertThat(HibernateProviderSupport.treatsLine("7.4.11.Final")).isTrue();
        assertThat(HibernateProviderSupport.treatsLine("6.6")).isFalse();
        assertThat(HibernateProviderSupport.treatsLine("6.5.3.Final")).isFalse();
        assertThat(HibernateProviderSupport.treatsLine("7.3.0.Final")).isFalse();
        assertThat(HibernateProviderSupport.treatsLine("7.5.0.Final")).isFalse();
        assertThat(HibernateProviderSupport.treatsLine("8.0.0.Final")).isFalse();
        assertThat(HibernateProviderSupport.treatsLine(null)).isFalse();
        assertThat(new HibernateProviderSupport().treats(joined)).isPresent();
        assertThat(HibernateProviderSupport.treats(joined, "7.5.0.Final")).isEmpty();
        assertThat(HibernateProviderSupport.treats(joined, "unknown")).isEmpty();
    }

    @Test
    void ac_vnd_16_compliance_mode_returns_no_renderer() {
        try (var strict = new Configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL,
                        "jdbc:h2:mem:treat-renderer-compliance")
                .setProperty(AvailableSettings.JPA_QUERY_COMPLIANCE, "true")
                .addAnnotatedClass(JdPayment.class).addAnnotatedClass(JdCard.class).addAnnotatedClass(JdBank.class)
                .addAnnotatedClass(JdHolder.class).setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .buildSessionFactory()) {
            assertThat(new HibernateProviderSupport().treats(strict)).isEmpty();
        }
    }

    @Test
    void ac_vnd_17_treat_reads_a_root_and_a_list_join_as_the_subtype() {
        var renderer = renderer(joined);
        joined.inSession(em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Object> query = cb.createQuery(Object.class);
            Root<JdPayment> root = query.from(JdPayment.class);
            From<?, JdCard> rootTreat = renderer.treat(cb, root, JdCard.class);
            assertThat(rootTreat.getJavaType()).isEqualTo(JdCard.class);
            Join<JdHolder, JdPayment> payments = query.from(JdHolder.class).join("payments");
            From<?, JdCard> listTreat = renderer.treat(cb, payments, JdCard.class);
            assertThat(listTreat.getJavaType()).isEqualTo(JdCard.class);
            assertThat(listTreat).isInstanceOf(ListJoin.class);
        });
    }
}
