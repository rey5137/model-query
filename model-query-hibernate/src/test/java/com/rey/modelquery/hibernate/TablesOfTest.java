package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateProviderSupport#tablesOf} names every table reading an entity touches, unquoted and qualified with
 * the default schema, so two entities sharing one are told apart from two that share none (R-VND-13, D-109).
 */
class TablesOfTest {

    @Entity
    @Table(name = "parties")
    @Inheritance(strategy = InheritanceType.JOINED)
    static class Party {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "people")
    static class Person extends Party {
        String name;
    }

    /** On the joined supertable alone, as a second entity over the root's table. */
    @Entity
    @Table(name = "\"PARTIES\"")
    static class PartyView {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "accounts", schema = "ledger")
    @SecondaryTable(name = "`account_notes`")
    static class Account {
        @Id
        Long id;

        @Column(table = "`account_notes`")
        String note;
    }

    @Entity
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @Table(name = "shapes")
    abstract static class Shape {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "circles")
    static class Circle extends Shape {
        double radius;
    }

    @Entity
    @Table(name = "squares")
    static class Square extends Shape {
        double side;
    }

    private final HibernateProviderSupport support = new HibernateProviderSupport();

    @Test
    void ac_vnd_10_a_joined_subclass_reads_its_supertable_which_a_second_entity_on_it_shares() {
        try (SessionFactory sf = factory()) {
            assertThat(lower(support.tablesOf(sf, Person.class))).containsExactlyInAnyOrder("app.parties",
                    "app.people");
            assertThat(lower(support.tablesOf(sf, Party.class))).containsExactlyInAnyOrder("app.parties",
                    "app.people");
            assertThat(lower(support.tablesOf(sf, PartyView.class))).containsExactly("app.parties");
        }
    }

    @Test
    void ac_vnd_10_secondary_tables_are_read_unquoted_and_an_explicit_schema_is_kept() {
        try (SessionFactory sf = factory()) {
            assertThat(lower(support.tablesOf(sf, Account.class))).containsExactlyInAnyOrder("ledger.accounts",
                    "app.account_notes");
        }
    }

    @Test
    void ac_vnd_10_a_table_per_class_parent_reads_every_subclass_table_and_a_subclass_not_its_siblings() {
        try (SessionFactory sf = factory()) {
            assertThat(lower(support.tablesOf(sf, Shape.class))).containsExactlyInAnyOrder("app.shapes", "app.circles",
                    "app.squares");
            // Hibernate also names the parent's table among a subclass's query spaces: harmless, key-first is safe.
            assertThat(lower(support.tablesOf(sf, Circle.class))).contains("app.circles")
                    .doesNotContain("app.squares");
        }
    }

    @Test
    void ac_vnd_10_a_type_that_is_not_an_entity_has_no_tables() {
        try (SessionFactory sf = factory()) {
            assertThat(support.tablesOf(sf, String.class)).isEmpty();
        }
    }

    @Test
    void normalised_unquotes_each_part_and_qualifies_only_a_bare_name() {
        Identifier app = Identifier.toIdentifier("app");
        assertThat(HibernateProviderSupport.normalised("[dbo].\"my.table\"", null, app)).hasValue("dbo.my.table");
        assertThat(HibernateProviderSupport.normalised("`orders`", null, app)).hasValue("app.orders");
        assertThat(HibernateProviderSupport.normalised("orders", app, null)).hasValue("app.orders");
        assertThat(HibernateProviderSupport.normalised(" orders ", Identifier.toIdentifier("cat"), app))
                .hasValue("cat.app.orders");
        assertThat(HibernateProviderSupport.normalised("ledger.orders", Identifier.toIdentifier("cat"), app))
                .hasValue("ledger.orders");
        assertThat(HibernateProviderSupport.normalised("( select 1 )", null, app)).isEmpty();
    }

    private static Set<String> lower(Set<String> tables) {
        return tables.stream().map(table -> table.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static SessionFactory factory() {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:tables-of")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.DEFAULT_SCHEMA, "app");
        return new Configuration().addAnnotatedClass(Party.class).addAnnotatedClass(Person.class)
                .addAnnotatedClass(PartyView.class).addAnnotatedClass(Account.class).addAnnotatedClass(Shape.class)
                .addAnnotatedClass(Circle.class).addAnnotatedClass(Square.class)
                .buildSessionFactory(registry.build());
    }
}
