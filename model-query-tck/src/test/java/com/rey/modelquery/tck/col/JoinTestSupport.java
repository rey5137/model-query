package com.rey.modelquery.tck.col;

import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.sql.Connection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.function.Consumer;
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

    /**
     * A DataSource over {@code db} that opens a connection per call. A factory built on it names its DataSource, so
     * the executor can read {@code DatabaseMetaData} without {@code model-query-hibernate} (R-VND-04).
     */
    public static DataSource dataSource(TckDatabase db) {
        return dataSource(db, () -> { });
    }

    /** As {@link #dataSource(TckDatabase)}, running {@code onOpen} after each connection it opens. */
    public static DataSource dataSource(TckDatabase db, Runnable onOpen) {
        return (DataSource) Proxy.newProxyInstance(JoinTestSupport.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("toString") ? "DataSource of " + db
                                : method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                : proxy == args[0];
                    }
                    if (!method.getName().equals("getConnection")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Connection connection = db.getConnection();
                    onOpen.run();
                    return connection;
                });
    }

    /**
     * Closes {@code factory} after running {@code work} on an executor of it, which resolves with or without the
     * Hibernate SPI; without it, the vendor is read from the factory's DataSource.
     */
    public static <E> void withExecutor(SessionFactory factory, boolean hibernate, Class<E> root,
            ModelQueryConfig config, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = factory) {
            Runnable run = () -> sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, config)));
            if (hibernate) {
                run.run();
            } else {
                withoutServices(run);
            }
        }
    }

    public static SessionFactory sessionFactory(DataSource dataSource) {
        return build(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource));
    }

    /**
     * Like {@link #sessionFactory(TckDatabase)}, with Hibernate's {@code hibernate.order_by.default_null_ordering} set
     * to {@code nullOrdering}, which Hibernate applies to every order rendered without a null precedence.
     */
    public static SessionFactory sessionFactory(TckDatabase db, String nullOrdering) {
        return build(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                .applySetting(AvailableSettings.DEFAULT_NULL_ORDERING, nullOrdering));
    }

    /**
     * Like {@link #sessionFactory(DataSource)}, but the connection goes back to the DataSource as soon as no statement
     * or result set of the session is open, so the DataSource's active count shows a result set left open.
     */
    public static SessionFactory sessionFactoryReleasingAfterStatement(DataSource dataSource) {
        return build(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                .applySetting(AvailableSettings.CONNECTION_HANDLING,
                        "DELAYED_ACQUISITION_AND_RELEASE_AFTER_STATEMENT"));
    }

    /** Runs {@code work} where no {@code ServiceLoader} service file is visible: the executor as without add-ons. */
    public static void withoutServices(Runnable work) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(new ClassLoader(original) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return name.startsWith("META-INF/services/") ? Collections.emptyEnumeration()
                        : super.getResources(name);
            }
        });
        try {
            work.run();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static SessionFactory build(StandardServiceRegistryBuilder builder) {
        var registry = builder
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .build();
        return new Configuration()
                .addAnnotatedClass(CustomerEntity.class)
                .addAnnotatedClass(OrderEntity.class)
                .addAnnotatedClass(OrderArchiveEntity.class)
                .addAnnotatedClass(OrderItemEntity.class)
                .addAnnotatedClass(PlainOrderItemEntity.class)
                .addAnnotatedClass(NullableSortEntity.class)
                .addAnnotatedClass(CompositeKeyItemEntity.class)
                .addAnnotatedClass(CompositeKeyCopyEntity.class)
                .addAnnotatedClass(StampedOrderEntity.class)
                .addAnnotatedClass(DatedRowEntity.class)
                .addAnnotatedClass(LabelEntity.class)
                .addAnnotatedClass(CustomerNoteEntity.class)
                .addAnnotatedClass(PatronEntity.class)
                .addAnnotatedClass(KeysetTypeEntity.class)
                .addAnnotatedClass(StringKeyProductEntity.class)
                .addAnnotatedClass(EmbeddedKeyEntity.class)
                .addAnnotatedClass(SkuProductEntity.class)
                .addAnnotatedClass(SkuOrderLineEntity.class)
                .addAnnotatedClass(FormulaProductEntity.class)
                .addAnnotatedClass(FormulaLineEntity.class)
                .addAnnotatedClass(DeliveryEntity.class)
                .buildSessionFactory(registry);
    }
}
