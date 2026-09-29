package com.rey.modelquery.tck.harness;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One database per target for the whole JVM: started lazily on first use, seeded once, and reaped by Testcontainers'
 * Ryuk when the JVM exits.
 */
public final class TckDatabases {

    private static final Map<TckTarget, TckDatabase> STARTED = new ConcurrentHashMap<>();

    private TckDatabases() {}

    public static TckDatabase get(TckTarget target) {
        return STARTED.computeIfAbsent(target, TckDatabases::start);
    }

    private static TckDatabase start(TckTarget target) {
        TckDatabase db =
                switch (target.vendor()) {
                    case H2 -> new TckDatabase(target, "jdbc:h2:mem:tck;DB_CLOSE_DELAY=-1", "sa", "");
                    case POSTGRESQL -> {
                        PostgreSQLContainer container = new PostgreSQLContainer("postgres:" + target.version() + "-alpine");
                        container.withUrlParam("reWriteBatchedInserts", "true");
                        yield fromContainer(target, container);
                    }
                    case MYSQL -> {
                        MySQLContainer container = new MySQLContainer("mysql:" + target.version());
                        container.withUrlParam("rewriteBatchedStatements", "true");
                        yield fromContainer(target, container);
                    }
                };
        try (Connection connection = db.getConnection()) {
            TckFixture.create(connection, target.vendor());
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create the TCK fixture on " + target, e);
        }
        return db;
    }

    private static TckDatabase fromContainer(TckTarget target, JdbcDatabaseContainer<?> container) {
        container.start();
        return new TckDatabase(
                target, container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
