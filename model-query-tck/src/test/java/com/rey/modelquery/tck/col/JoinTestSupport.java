package com.rey.modelquery.tck.col;

import com.rey.modelquery.tck.harness.TckDatabase;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** Builds a Hibernate {@link SessionFactory} over a (proxied) DataSource for the fixture entities. */
public final class JoinTestSupport {

    private JoinTestSupport() {}

    public static SessionFactory sessionFactory(TckDatabase db) {
        return build(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password()));
    }

    public static SessionFactory sessionFactory(DataSource dataSource) {
        return build(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource));
    }

    /**
     * Like {@link #sessionFactory(DataSource)}, but the connection goes back to the DataSource as soon as no statement
     * or result set of the session is open, so the DataSource's active count shows a result set left open.
     */
    public static SessionFactory sessionFactoryReleasingAfterStatement(DataSource dataSource) {
        return build(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                .applySetting(AvailableSettings.CONNECTION_HANDLING, "DELAYED_ACQUISITION_AND_RELEASE_AFTER_STATEMENT"));
    }

    private static SessionFactory build(StandardServiceRegistryBuilder builder) {
        var registry = builder
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .build();
        return new Configuration()
                .addAnnotatedClass(CustomerEntity.class)
                .addAnnotatedClass(OrderEntity.class)
                .addAnnotatedClass(OrderItemEntity.class)
                .addAnnotatedClass(NullableSortEntity.class)
                .addAnnotatedClass(CompositeKeyItemEntity.class)
                .buildSessionFactory(registry);
    }
}
