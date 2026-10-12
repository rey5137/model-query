package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ConstantRenderer;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateConstantRenderer}: the stored value of a basic attribute's mapped type, an {@code @Enumerated} one
 * by name or by ordinal and an {@code AttributeConverter} one as converted (spec vendor/40 R-VND-16, api/10 R-COL-27).
 */
class HibernateConstantRendererTest {

    @Entity(name = "ConstantRow")
    static class ConstantRow {
        @Id
        Long id;

        @Enumerated(EnumType.STRING)
        @Column(name = "status")
        Status status;

        @Enumerated(EnumType.ORDINAL)
        @Column(name = "rank")
        Rank rank;

        @Convert(converter = YesNoConverter.class)
        @Column(name = "active")
        Boolean active;

        @Column(name = "label")
        String label;

        @Column(name = "total")
        Integer total;
    }

    enum Status { PENDING, FAILED, CLOSED }

    enum Rank { FIRST, SECOND, THIRD }

    /** {@code Boolean} attribute over a {@code String} column. */
    @Converter
    static class YesNoConverter implements AttributeConverter<Boolean, String> {
        @Override
        public String convertToDatabaseColumn(Boolean attribute) {
            return Boolean.TRUE.equals(attribute) ? "Y" : "N";
        }

        @Override
        public Boolean convertToEntityAttribute(String dbData) {
            return "Y".equals(dbData);
        }
    }

    @Test
    void ac_col_32_a_constant_maps_as_its_attribute_is_stored() {
        try (SessionFactory sessions = factory()) {
            ConstantRenderer renderer = new HibernateProviderSupport().constants(sessions).orElseThrow();
            assertThat(renderer.storedValue(ConstantRow.class, "status", Status.PENDING)).contains("PENDING");
            assertThat(renderer.storedValue(ConstantRow.class, "rank", Rank.SECOND)).contains(1);
            assertThat(renderer.storedValue(ConstantRow.class, "active", true)).contains("Y");
            assertThat(renderer.storedValue(ConstantRow.class, "active", false)).contains("N");
            assertThat(renderer.storedValue(ConstantRow.class, "label", "x")).contains("x");
            assertThat(renderer.storedValue(ConstantRow.class, "total", 7)).contains(7);
            // An attribute the entity does not map names no literal (MQ1508).
            assertThat(renderer.storedValue(ConstantRow.class, "nope", "x")).isEmpty();
        }
    }

    private static SessionFactory factory() {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:constant-renderer")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        return new Configuration().addAnnotatedClass(ConstantRow.class).buildSessionFactory(registry.build());
    }
}
