package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.core.TreatUse;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.hibernate.AnnotationException;
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The mapping gates of AC-VND-18: composite ids, null discriminator values and subtype restrictions. */
class HibernateTreatMappingGateTest {

    @Embeddable
    static class Key implements Serializable {
        Long zeta;
        Long alpha;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(zeta, key.zeta) && Objects.equals(alpha, key.alpha);
        }

        @Override
        public int hashCode() {
            return Objects.hash(zeta, alpha);
        }
    }

    @Entity(name = "IdPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @IdClass(Key.class)
    static class IdPayment {
        @Id Long zeta;
        @Id Long alpha;
    }

    @Entity(name = "IdCard")
    static class IdCard extends IdPayment {
        String number;
    }

    @Entity(name = "EmbPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class EmbPayment {
        @EmbeddedId Key key;
    }

    @Entity(name = "EmbCard")
    static class EmbCard extends EmbPayment {
        String number;
    }

    @Entity(name = "NullPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class NullPayment {
        @Id Long id;
    }

    @Entity(name = "NullCard")
    @DiscriminatorValue("null")
    static class NullCard extends NullPayment {
        String number;
    }

    @Entity(name = "NotNullPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class NotNullPayment {
        @Id Long id;
    }

    @Entity(name = "NotNullCard")
    @DiscriminatorValue("not null")
    static class NotNullCard extends NotNullPayment {
        String number;
    }

    @Entity(name = "RestPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class RestPayment {
        @Id Long id;
    }

    @Entity(name = "RestPlainCard")
    static class RestPlainCard extends RestPayment {
        String number;
    }

    /** A subtype with {@code @SQLRestriction}, registered per test: 7.4 refuses it at bootstrap (AC-VND-18). */
    @Entity(name = "RestCard")
    @SQLRestriction("number is not null")
    static class RestCard extends RestPayment {
        String number;
    }

    @Entity(name = "RootRestPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @SQLRestriction("id > 0")
    static class RootRestPayment {
        @Id Long id;
    }

    @Entity(name = "RootRestCard")
    static class RootRestCard extends RootRestPayment {
        String number;
    }

    private static SessionFactory composite;
    private static SessionFactory discriminator;
    private static SessionFactory restricted;

    @BeforeAll
    static void start() {
        composite = factory("jdbc:h2:mem:treat-gate-composite", IdPayment.class, IdCard.class, EmbPayment.class,
                EmbCard.class);
        discriminator = factory("jdbc:h2:mem:treat-gate-discriminator", NullPayment.class, NullCard.class,
                NotNullPayment.class, NotNullCard.class, RestPayment.class, RestPlainCard.class);
        restricted = factory("jdbc:h2:mem:treat-gate-root-restriction", RootRestPayment.class, RootRestCard.class);
    }

    @AfterAll
    static void stop() {
        composite.close();
        discriminator.close();
        restricted.close();
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

    private static TreatSite select(Class<?> entity, Class<?> subtype) {
        return new TreatSite(entity, subtype, Optional.of("number"), StatementShape.CONTENT, true,
                Set.of(TreatUse.SELECT));
    }

    @Test
    void ac_vnd_18_an_id_class_or_embedded_id_hierarchy_is_refused() {
        var renderer = renderer(composite);
        assertThat(renderer.refusal(select(IdPayment.class, IdCard.class))).hasValueSatisfying(
                text -> assertThat(text).contains("composite id"));
        assertThat(renderer.refusal(select(EmbPayment.class, EmbCard.class))).hasValueSatisfying(
                text -> assertThat(text).contains("composite id"));
    }

    @Test
    void ac_vnd_18_a_null_or_not_null_discriminator_value_is_refused() {
        var renderer = renderer(discriminator);
        assertThat(renderer.refusal(select(NullPayment.class, NullCard.class))).hasValueSatisfying(
                text -> assertThat(text).contains("discriminator value"));
        assertThat(renderer.refusal(select(NotNullPayment.class, NotNullCard.class))).hasValueSatisfying(
                text -> assertThat(text).contains("discriminator value"));
    }

    @Test
    void ac_vnd_18_a_subtype_sql_restriction_is_refused_on_6_6_and_fails_bootstrap_on_7_4() {
        if (Version.getVersionString().startsWith("7.")) {
            assertThatThrownBy(() -> factory("jdbc:h2:mem:treat-gate-subtype-restriction", RestPayment.class,
                    RestCard.class)).isInstanceOf(AnnotationException.class);
            return;
        }
        try (var factory = factory("jdbc:h2:mem:treat-gate-subtype-restriction", RestPayment.class,
                RestPlainCard.class, RestCard.class)) {
            // The whole hierarchy is refused, a sibling of the restricted subtype included.
            assertThat(renderer(factory).refusal(select(RestPayment.class, RestPlainCard.class)))
                    .hasValueSatisfying(text -> assertThat(text).contains("subtype @SQLRestriction"));
            assertThat(renderer(factory).refusal(select(RestPayment.class, RestCard.class)))
                    .hasValueSatisfying(text -> assertThat(text).contains("subtype @SQLRestriction"));
        }
    }

    @Test
    void ac_vnd_18_a_root_sql_restriction_runs() {
        assertThat(renderer(restricted).refusal(select(RootRestPayment.class, RootRestCard.class))).isEmpty();
        assertThat(renderer(discriminator).refusal(select(RestPayment.class, RestPlainCard.class))).isEmpty();
    }
}
