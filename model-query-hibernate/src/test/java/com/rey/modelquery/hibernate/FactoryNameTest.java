package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Proxy;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateProviderSupport#factoryName} answers the session factory name, which {@code MQ4012} quotes, and
 * nothing for an unnamed, blank-named or non-Hibernate factory (R-VND-19, D-133).
 */
class FactoryNameTest {

    @Entity
    static class Named {
        @Id
        Long id;
    }

    private final HibernateProviderSupport support = new HibernateProviderSupport();

    @Test
    void ac_vnd_15_a_named_session_factory_is_named_by_its_session_factory_name() {
        try (SessionFactory sf = factory("orders-unit")) {
            assertThat(support.factoryName(sf)).contains("orders-unit");
        }
    }

    @Test
    void ac_vnd_15_an_unnamed_or_blank_named_session_factory_has_no_name() {
        try (SessionFactory sf = factory(null)) {
            assertThat(support.factoryName(sf)).isEmpty();
        }
        try (SessionFactory sf = factory("  ")) {
            assertThat(support.factoryName(sf)).isEmpty();
        }
    }

    @Test
    void ac_vnd_15_a_factory_of_another_provider_has_no_name() {
        EntityManagerFactory other = (EntityManagerFactory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class}, (proxy, method, args) -> {
                    if (method.getName().equals("unwrap")) {
                        throw new PersistenceException("not Hibernate");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        assertThat(support.factoryName(other)).isEmpty();
    }

    private static SessionFactory factory(String name) {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:factory-name")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "");
        if (name != null) {
            registry.applySetting(AvailableSettings.SESSION_FACTORY_NAME, name)
                    .applySetting(AvailableSettings.SESSION_FACTORY_NAME_IS_JNDI, "false");
        }
        return new Configuration().addAnnotatedClass(Named.class).buildSessionFactory(registry.build());
    }
}
