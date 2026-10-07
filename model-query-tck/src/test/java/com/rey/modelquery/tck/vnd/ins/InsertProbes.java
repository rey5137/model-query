package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.Generator;

/**
 * The D-116 vendor spike's harness: a factory over the {@code ins_*} probe entities that creates and drops its own
 * schema, and records every statement its sessions run so a probe can assert the plan Hibernate chose.
 */
public final class InsertProbes implements AutoCloseable {

    private static final List<Class<?>> ENTITIES = List.of(InsSourceEntity.class, InsIdentityEntity.class,
            InsAssignedEntity.class, InsPooledEntity.class, InsSequenceEntity.class, InsTableEntity.class,
            InsUuidEntity.class, InsMapsIdEntity.class, InsKeyedEntity.class);

    private final TckDatabase db;
    private final SessionFactory factory;
    private final List<String> recorded;

    private InsertProbes(TckDatabase db, SessionFactory factory, List<String> recorded) {
        this.db = db;
        this.factory = factory;
        this.recorded = recorded;
    }

    /** Opens a factory over the probe entities, its schema created, with {@code ins_source} seeded. */
    public static InsertProbes open(TckDatabase db) {
        return open(db, null, List.of());
    }

    /** As {@link #open(TckDatabase)}, also mapping the enhanced entity {@code loader} defines. */
    static InsertProbes open(TckDatabase db, EnhancingClassLoader loader) {
        return open(db, loader, List.of());
    }

    /**
     * As {@link #open(TckDatabase)}, also mapping the roots no bulk insert writes: a {@code JOINED} hierarchy, a
     * {@code @SecondaryTable} and a composite id with a generated part (R-WRT-26).
     */
    public static InsertProbes withUnsupportedRoots(TckDatabase db) {
        return open(db, null, List.of(InsJoinedEntity.class, InsJoinedChildEntity.class, InsSecondaryEntity.class,
                InsCompositeEntity.class));
    }

    /** As {@link #open(TckDatabase)}, also mapping the {@code persist} roots (R-WRT-39). */
    public static InsertProbes withPersistRoots(TckDatabase db) {
        return open(db, null, List.of(InsPersistEntity.class, InsRecordEmbeddedEntity.class,
                InsCtorEmbeddedEntity.class, InsPropertyChildEntity.class, InsGeneratedUuidEntity.class,
                InsCompositeEntity.class));
    }

    /**
     * As {@link #open(TckDatabase)}, also mapping the roots an update or delete {@code throughEntities()} writes: a
     * listened root, a parent with cascading children, and a root with an {@code @SQLDelete}.
     */
    public static InsertProbes withEntityWriteRoots(TckDatabase db) {
        return open(db, null, List.of(InsListenedEntity.class, InsParentEntity.class, InsChildEntity.class,
                InsSoftDeletedEntity.class));
    }

    /** As {@link #open(TckDatabase)}, also mapping the root write assignments write (R-WRT-49). */
    public static InsertProbes withWriteAssignmentRoots(TckDatabase db) {
        return open(db, null, List.of(InsAuditedEntity.class));
    }

    private static InsertProbes open(TckDatabase db, EnhancingClassLoader loader, List<Class<?>> extra) {
        List<String> recorded = Collections.synchronizedList(new ArrayList<>());
        DataSource dataSource = ProxyDataSourceBuilder.create(JoinTestSupport.dataSource(db))
                .afterQuery((info, queries) -> queries.forEach(q -> recorded.add(SqlSnapshots.normalize(q.getQuery()))))
                .build();
        var bootstrap = new BootstrapServiceRegistryBuilder();
        if (loader != null) {
            bootstrap.applyClassLoader(loader);
        }
        var bootstrapRegistry = bootstrap.build();
        var registry = new StandardServiceRegistryBuilder(bootstrapRegistry)
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .build();
        var configuration = new Configuration(bootstrapRegistry);
        ENTITIES.forEach(configuration::addAnnotatedClass);
        extra.forEach(configuration::addAnnotatedClass);
        if (loader != null) {
            configuration.addAnnotatedClass(loader.enhanced());
        }
        InsertProbes probes = new InsertProbes(db, configuration.buildSessionFactory(registry), recorded);
        try {
            for (int i = 1; i <= 3; i++) {
                probes.jdbc("insert into ins_source (id, code, name) values (" + i + ", 's" + i + "', 'S" + i + "')");
            }
        } catch (RuntimeException e) {
            probes.close();
            throw e;
        }
        return probes;
    }

    public SessionFactory factory() {
        return factory;
    }

    /** Runs {@code work} in a transaction of a new session, after forgetting the statements recorded so far. */
    <T> T inTransaction(Function<Session, T> work) {
        recorded.clear();
        return factory.fromTransaction(work);
    }

    /** Forgets the statements recorded so far, so {@link #statements()} shows only what runs next. */
    public void forget() {
        recorded.clear();
    }

    /** The statements recorded since the last {@link #inTransaction} or {@link #forget}. */
    public List<String> statements() {
        return List.copyOf(recorded);
    }

    /** The running Hibernate's major version. */
    static int hibernateMajor() {
        String version = Version.getVersionString();
        return Integer.parseInt(version.substring(0, version.indexOf('.')));
    }

    /** The generator Hibernate resolved for {@code root}'s id. */
    Generator generator(Class<?> root) {
        return factory.unwrap(SessionFactoryImplementor.class).getMappingMetamodel().getEntityDescriptor(root)
                .getGenerator();
    }

    /** {@code n} keys drawn from {@code root}'s generator in {@code session}: the RFC's pre-generation. */
    List<Object> pregenerate(Session session, Class<?> root, int n) {
        var generator = (BeforeExecutionGenerator) generator(root);
        var implementor = session.unwrap(SharedSessionContractImplementor.class);
        List<Object> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            keys.add(generator.generate(implementor, null, null, EventType.INSERT));
        }
        return keys;
    }

    /** Runs {@code sql} over plain JDBC, outside any recorded session. */
    public void jdbc(String sql) {
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** Each row of {@code sql}'s result, its columns joined by {@code |}, read over plain JDBC. */
    public List<String> rows(String sql) {
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> values = new ArrayList<>();
                for (int c = 1; c <= columns; c++) {
                    values.add(String.valueOf(rs.getObject(c)));
                }
                rows.add(String.join("|", values));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    @Override
    public void close() {
        factory.close();
    }
}
