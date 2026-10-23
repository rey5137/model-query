package com.rey.modelquery.spike.rfc0019.benchmarks;

import com.rey.modelquery.spike.rfc0019.LitePosDataset;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Cashier;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Sale;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.SaleLine;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Store;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Terminal;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Manages database containers, HikariCP connection pools, and Hibernate SessionFactories
 * for JMH benchmarks.
 */
public final class BenchmarkDatabase {

    private static PostgreSQLContainer<?> pgContainer;
    private static MySQLContainer<?> myContainer;

    public static final class StatementCapture implements StatementInspector {
        private final List<String> captured = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            captured.add(sql);
            return sql;
        }

        public List<String> captured() {
            return captured;
        }

        public void clear() {
            captured.clear();
        }

        public String last() {
            if (captured.isEmpty()) {
                throw new IllegalStateException("No SQL statements captured");
            }
            return captured.get(captured.size() - 1);
        }
    }

    private BenchmarkDatabase() {}

    public static synchronized void startContainers() throws Exception {
        if (pgContainer == null) {
            PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
            pg.withUrlParam("reWriteBatchedInserts", "true");
            pg.start();
            pgContainer = pg;
            try (Connection conn = pgContainer.createConnection("")) {
                createTables(conn, "POSTGRESQL");
                LitePosDataset.seed(conn, true);
                try (Statement st = conn.createStatement()) {
                    st.execute("ANALYZE");
                }
            }
        }
        if (myContainer == null) {
            MySQLContainer<?> my = new MySQLContainer<>("mysql:8.0.31");
            my.withUrlParam("rewriteBatchedStatements", "true");
            my.withUrlParam("connectionTimeZone", "UTC");
            my.withUrlParam("forceConnectionTimeZoneToSession", "true");
            my.start();
            myContainer = my;
            try (Connection conn = myContainer.createConnection("")) {
                createTables(conn, "MYSQL");
                LitePosDataset.seed(conn, true);
                try (Statement st = conn.createStatement()) {
                    st.execute("ANALYZE TABLE store, terminal, cashier, sale, sale_line");
                }
            }
        }
    }

    public static synchronized void stopContainers() {
        if (pgContainer != null) {
            try {
                pgContainer.stop();
            } finally {
                pgContainer = null;
            }
        }
        if (myContainer != null) {
            try {
                myContainer.stop();
            } finally {
                myContainer = null;
            }
        }
    }

    public static String pgJdbcUrl() {
        return pgContainer != null ? pgContainer.getJdbcUrl() : System.getProperty("spike.pg.url");
    }

    public static String pgUsername() {
        return pgContainer != null ? pgContainer.getUsername() : System.getProperty("spike.pg.user", "test");
    }

    public static String pgPassword() {
        return pgContainer != null ? pgContainer.getPassword() : System.getProperty("spike.pg.password", "test");
    }

    public static String mysqlJdbcUrl() {
        return myContainer != null ? myContainer.getJdbcUrl() : System.getProperty("spike.mysql.url");
    }

    public static String mysqlUsername() {
        return myContainer != null ? myContainer.getUsername() : System.getProperty("spike.mysql.user", "test");
    }

    public static String mysqlPassword() {
        return myContainer != null ? myContainer.getPassword() : System.getProperty("spike.mysql.password", "test");
    }

    public static HikariDataSource createDataSource(String url, String user, String pass, int poolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(poolSize);
        cfg.setConnectionTimeout(30000);
        return new HikariDataSource(cfg);
    }

    public static SessionFactory createSessionFactory(DataSource ds, Map<String, ?> extraSettings) {
        return createSessionFactory(ds, extraSettings, null);
    }

    public static SessionFactory createSessionFactory(
            DataSource ds, Map<String, ?> extraSettings, StatementCapture capture) {
        StandardServiceRegistryBuilder registryBuilder = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource", ds)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.show_sql", "false")
                .applySetting("hibernate.format_sql", "false")
                .applySetting("hibernate.jdbc.batch_size", "50")
                .applySetting("hibernate.order_inserts", "true")
                .applySetting("hibernate.order_updates", "true");
        if (capture != null) {
            registryBuilder.applySetting("hibernate.session_factory.statement_inspector", capture);
        }
        if (extraSettings != null) {
            extraSettings.forEach(registryBuilder::applySetting);
        }
        StandardServiceRegistry registry = registryBuilder.build();
        return new MetadataSources(registry)
                .addAnnotatedClass(Store.class)
                .addAnnotatedClass(Terminal.class)
                .addAnnotatedClass(Cashier.class)
                .addAnnotatedClass(Sale.class)
                .addAnnotatedClass(SaleLine.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    public static void warmPool(DataSource ds, int count) throws SQLException {
        for (int i = 0; i < count; i++) {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("SELECT 1");
            }
        }
    }

    private static void createTables(Connection conn, String vendor) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if ("POSTGRESQL".equals(vendor)) {
                st.execute("CREATE TABLE IF NOT EXISTS store ("
                        + "id BIGINT PRIMARY KEY, code VARCHAR(50) NOT NULL, name VARCHAR(255) NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS terminal ("
                        + "id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, serial_number VARCHAR(100) NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS cashier ("
                        + "id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, code VARCHAR(50) NOT NULL, "
                        + "name VARCHAR(255) NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS sale ("
                        + "id BIGINT PRIMARY KEY, receipt_number VARCHAR(100) NOT NULL, store_id BIGINT NOT NULL, "
                        + "terminal_id BIGINT NOT NULL, cashier_id BIGINT NOT NULL, sale_time TIMESTAMP NOT NULL, "
                        + "status VARCHAR(30) NOT NULL, total_amount DECIMAL(12,2) NOT NULL, "
                        + "tax_amount DECIMAL(12,2) NOT NULL, discount_amount DECIMAL(12,2) NOT NULL, "
                        + "note VARCHAR(500), item_count BIGINT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS sale_line ("
                        + "id BIGINT PRIMARY KEY, sale_id BIGINT NOT NULL, line_number INT NOT NULL, "
                        + "product_sku VARCHAR(100) NOT NULL, quantity DECIMAL(10,3) NOT NULL, "
                        + "unit_price DECIMAL(10,2) NOT NULL, line_total DECIMAL(12,2) NOT NULL)");

                // Indexes for LitePOS schema
                st.execute("CREATE INDEX IF NOT EXISTS idx_sale_store_time ON sale(store_id, sale_time)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_sale_terminal ON sale(terminal_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_sale_cashier ON sale(cashier_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_sale_line_sale ON sale_line(sale_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_terminal_store ON terminal(store_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_cashier_store ON cashier(store_id)");
            } else {
                st.execute("CREATE TABLE IF NOT EXISTS store ("
                        + "id BIGINT PRIMARY KEY, code VARCHAR(50) NOT NULL, name VARCHAR(255) NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS terminal ("
                        + "id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, serial_number VARCHAR(100) NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS cashier ("
                        + "id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, code VARCHAR(50) NOT NULL, "
                        + "name VARCHAR(255) NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS sale ("
                        + "id BIGINT PRIMARY KEY, receipt_number VARCHAR(100) NOT NULL, store_id BIGINT NOT NULL, "
                        + "terminal_id BIGINT NOT NULL, cashier_id BIGINT NOT NULL, sale_time DATETIME NOT NULL, "
                        + "status VARCHAR(30) NOT NULL, total_amount DECIMAL(12,2) NOT NULL, "
                        + "tax_amount DECIMAL(12,2) NOT NULL, discount_amount DECIMAL(12,2) NOT NULL, "
                        + "note VARCHAR(500), item_count BIGINT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS sale_line ("
                        + "id BIGINT PRIMARY KEY, sale_id BIGINT NOT NULL, line_number INT NOT NULL, "
                        + "product_sku VARCHAR(100) NOT NULL, quantity DECIMAL(10,3) NOT NULL, "
                        + "unit_price DECIMAL(10,2) NOT NULL, line_total DECIMAL(12,2) NOT NULL)");

                // Indexes for LitePOS schema (MySQL)
                st.execute("CREATE INDEX idx_sale_store_time ON sale(store_id, sale_time)");
                st.execute("CREATE INDEX idx_sale_terminal ON sale(terminal_id)");
                st.execute("CREATE INDEX idx_sale_cashier ON sale(cashier_id)");
                st.execute("CREATE INDEX idx_sale_line_sale ON sale_line(sale_id)");
                st.execute("CREATE INDEX idx_terminal_store ON terminal(store_id)");
                st.execute("CREATE INDEX idx_cashier_store ON cashier(store_id)");
            }
        }
    }
}
