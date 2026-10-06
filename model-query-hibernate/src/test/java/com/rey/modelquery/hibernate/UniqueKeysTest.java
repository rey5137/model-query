package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.NaturalId;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateInsertSupport#uniqueKeys}: the unique keys a conflict clause may name, read from the mapping under a
 * physical naming strategy that renames columns, as Spring Boot's default does (spec api/14 R-WRT-34).
 */
class UniqueKeysTest {

    /** A {@code @Table} on a superclass that is no entity, which Hibernate ignores: its constraint is no key. */
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "name"))
    abstract static class Unmapped {}

    @Embeddable
    static class Code {
        String prefix;
        Integer number;
    }

    @Entity
    static class Owner {
        @Id
        Long id;
    }

    /** Its constraint names {@code refNo} by its logical name; the physical column is {@code ref_no}. */
    @Entity
    @Table(name = "uk_item", uniqueConstraints = @UniqueConstraint(columnNames = {"region", "refNo"}))
    static class Item extends Unmapped {
        @Id
        Long id;

        @NaturalId
        @Embedded
        Code code;

        String region;

        String refNo;

        @Column(unique = true)
        String sku;

        @ManyToOne
        @JoinColumn(name = "ownerRef", unique = true)
        Owner owner;

        String name;
    }

    @Entity
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @Table(name = "uk_base", uniqueConstraints = @UniqueConstraint(columnNames = "tag"))
    static class Base {
        @Id
        Long id;

        String tag;
    }

    /** Hibernate copies its parent's unique constraint to its own table. */
    @Entity
    @Table(name = "uk_derived")
    static class Derived extends Base {
        String extra;
    }

    @Test
    void ac_wrt_32_unique_keys_are_the_id_natural_id_unique_columns_and_constraints_by_logical_or_physical_name() {
        try (SessionFactory sf = factory()) {
            assertThat(new HibernateInsertSupport().uniqueKeys(sf, Item.class)).containsExactlyInAnyOrder(
                    Set.of("id"), Set.of("code"), Set.of("code.prefix", "code.number"), Set.of("sku"),
                    Set.of("owner"), Set.of("region", "refNo"));
        }
    }

    @Test
    void ac_wrt_32_a_table_per_class_subclass_keeps_its_parents_constraint() {
        try (SessionFactory sf = factory()) {
            assertThat(new HibernateInsertSupport().uniqueKeys(sf, Derived.class))
                    .containsExactlyInAnyOrder(Set.of("id"), Set.of("tag"));
        }
    }

    private static SessionFactory factory() {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:unique-keys")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "");
        return new Configuration().setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .addAnnotatedClass(Item.class).addAnnotatedClass(Owner.class).addAnnotatedClass(Base.class)
                .addAnnotatedClass(Derived.class).buildSessionFactory(registry.build());
    }
}
