package com.rey.modelquery.tck.col;

import com.rey.modelquery.tck.harness.TckDatabase;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** Builds a Hibernate {@link SessionFactory} over a (proxied) DataSource for the fixture entities. */
final class JoinTestSupport {

    private JoinTestSupport() {}

    static SessionFactory sessionFactory(TckDatabase db) {
        return build(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password()));
    }

    static SessionFactory sessionFactory(DataSource dataSource) {
        return build(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource));
    }

    private static SessionFactory build(StandardServiceRegistryBuilder builder) {
        var registry = builder
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .build();
        return new Configuration()
                .addAnnotatedClass(CustomerEntity.class)
                .addAnnotatedClass(OrderEntity.class)
                .addAnnotatedClass(OrderItemEntity.class)
                .buildSessionFactory(registry);
    }
}
