package com.rey.modelquery.spike.rfc0023;

import com.mysql.cj.jdbc.MysqlDataSource;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import org.hibernate.cfg.Configuration;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** RFC 0023 spike harness: one vendor database, proxied data sources that capture every statement with its binds. */
final class Harness implements AutoCloseable {

    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    static final String HIB = Version.getVersionString();
    static final Path DIR = Path.of("..", "rfc", "0023-spike-data");

    /** A captured statement: SQL, its bind values in call order, and the calls to replay it over JDBC. */
    record Stmt(String sql, List<Object> binds, List<ParameterSetOperation> ops) {
        int marks() {
            return (int) sql.chars().filter(c -> c == '?').count();
        }
    }

    private final String vendor;
    private final List<Stmt> statements = new CopyOnWriteArrayList<>();
    private final JdbcDatabaseContainer<?> container;
    private final DataSource h2;
    private final java.sql.Connection keeper;

    Harness(String vendor) throws SQLException {
        this.vendor = vendor;
        switch (vendor) {
            case "H2" -> {
                container = null;
                JdbcDataSource ds = new JdbcDataSource();
                ds.setURL("jdbc:h2:mem:rfc0023_" + java.util.UUID.randomUUID());
                ds.setUser("sa");
                h2 = ds;
                keeper = ds.getConnection();
            }
            case "POSTGRESQL" -> {
                PostgreSQLContainer c = new PostgreSQLContainer("postgres:16-alpine");
                c.withCommand("postgres", "-c", "shared_buffers=512MB");
                c.start();
                container = c;
                h2 = null;
                keeper = null;
            }
            case "MYSQL" -> {
                MySQLContainer c = new MySQLContainer("mysql:8.0.31");
                c.withUrlParam("connectionTimeZone", "UTC");
                c.withUrlParam("forceConnectionTimeZoneToSession", "true");
                c.withCommand("--innodb-buffer-pool-size=1073741824");
                c.start();
                container = c;
                h2 = null;
                keeper = null;
            }
            default -> throw new IllegalArgumentException(vendor);
        }
    }

    String vendor() {
        return vendor;
    }

    /** MySQL: lets the test user read {@code performance_schema.data_locks}. */
    void grantMonitoring() throws SQLException {
        if (!vendor.equals("MYSQL")) {
            return;
        }
        MysqlDataSource root = new MysqlDataSource();
        root.setURL(container.getJdbcUrl());
        root.setUser("root");
        root.setPassword(container.getPassword());
        try (Connection c = root.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("grant select on performance_schema.* to '" + container.getUsername() + "'@'%'");
            st.execute("flush privileges");
        }
    }

    String version() throws SQLException {
        try (Connection c = raw("").getConnection()) {
            return c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion()
                    + "; driver " + c.getMetaData().getDriverName() + " " + c.getMetaData().getDriverVersion();
        }
    }

    /** An unproxied data source; {@code urlParams} ("a=b&amp;c=d") is appended to the JDBC URL of a container. */
    DataSource raw(String urlParams) throws SQLException {
        switch (vendor) {
            case "H2":
                return h2;
            case "POSTGRESQL": {
                PGSimpleDataSource pg = new PGSimpleDataSource();
                pg.setURL(url(urlParams));
                pg.setUser(container.getUsername());
                pg.setPassword(container.getPassword());
                return pg;
            }
            default: {
                MysqlDataSource my = new MysqlDataSource();
                my.setURL(url(urlParams));
                my.setUser(container.getUsername());
                my.setPassword(container.getPassword());
                return my;
            }
        }
    }

    private String url(String params) {
        String url = container.getJdbcUrl();
        if (params.isEmpty()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + params;
    }

    DataSource proxied(String urlParams) throws SQLException {
        return ProxyDataSourceBuilder.create(raw(urlParams)).listener(new Capture()).build();
    }

    SessionFactory factory(String urlParams) throws SQLException {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.hbm2ddl.auto", "none");
        cfg.addAnnotatedClass(Events.Event.class);
        cfg.getProperties().put("hibernate.connection.datasource", proxied(urlParams));
        return cfg.buildSessionFactory();
    }

    /** Prepares {@code stmt.sql()} on {@code conn} and replays its bind calls. */
    static java.sql.PreparedStatement prepare(Connection conn, Stmt stmt) throws SQLException {
        java.sql.PreparedStatement ps = conn.prepareStatement(stmt.sql());
        try {
            for (ParameterSetOperation op : stmt.ops()) {
                op.getMethod().invoke(ps, op.getArgs());
            }
        } catch (ReflectiveOperationException e) {
            ps.close();
            throw new SQLException("bind replay failed", e);
        }
        return ps;
    }

    List<Stmt> statements() {
        return statements;
    }

    void clear() {
        statements.clear();
    }

    static void tsv(String file, String header, String line) {
        try {
            Path p = DIR.resolve(file);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) {
                Files.writeString(p, header + "\n", StandardOpenOption.CREATE);
            }
            Files.writeString(p, line.replace("\r", "").replace("\n", " ") + "\n", StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One result row of {@code rfc0023-<line>.tsv}. */
    void cell(String probe, String name, String status, String value) {
        System.out.println("[" + vendor + " " + HIB + "] " + probe + " " + name + " " + status + " " + value);
        tsv("rfc0023-" + HIB + ".tsv", "vendor\tprobe\tcase\tstatus\tvalue",
                String.join("\t", vendor, probe, name, status, value.replace('\t', ' ')));
    }

    static void plan(String vendor, String name, String text) {
        try {
            Path p = DIR.resolve("plans").resolve(HIB).resolve(vendor + "-" + name + ".txt");
            Files.createDirectories(p.getParent());
            Files.writeString(p, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> T inEm(SessionFactory sf, java.util.function.Function<EntityManager, T> body) {
        try (EntityManager em = sf.createEntityManager()) {
            return body.apply(em);
        }
    }

    @Override
    public void close() {
        try {
            if (keeper != null) {
                keeper.close();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        if (container != null) {
            container.stop();
        }
    }

    private final class Capture implements QueryExecutionListener {
        @Override
        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {}

        @Override
        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
            for (QueryInfo q : queryInfoList) {
                List<Object> binds = new ArrayList<>();
                List<ParameterSetOperation> ops = new ArrayList<>();
                for (List<ParameterSetOperation> paramList : q.getParametersList()) {
                    for (ParameterSetOperation op : paramList) {
                        ops.add(op);
                        Object[] args = op.getArgs();
                        binds.add(args.length >= 2 ? args[1] : null);
                    }
                }
                if (q.getQuery().regionMatches(true, 0, "select", 0, 6)) {
                    statements.add(new Stmt(q.getQuery(), binds, ops));
                }
            }
        }
    }
}
