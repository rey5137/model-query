package com.rey.modelquery.spike.rfc0024;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
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

/** RFC 0024 spike harness: one vendor database and a proxied data source that captures every statement. */
final class Harness implements AutoCloseable {

    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    static final String HIB = Version.getVersionString();
    static final Path DIR = Path.of("..", "rfc", "0024-spike-data");

    /** A failed expectation inside a cell. */
    static final class Mismatch extends RuntimeException {
        Mismatch(String message) {
            super(message);
        }
    }

    record Stmt(String sql, List<Object> binds) {}

    /** A cell body: returns the value recorded on PASS, or throws. */
    interface Body {
        String run() throws Exception;
    }

    private final String vendor;
    private final List<Stmt> statements = new CopyOnWriteArrayList<>();
    private final JdbcDatabaseContainer<?> container;
    private final DataSource h2;
    private final Connection keeper;
    private int failures;

    Harness(String vendor) throws SQLException {
        this.vendor = vendor;
        switch (vendor) {
            case "H2" -> {
                container = null;
                JdbcDataSource ds = new JdbcDataSource();
                ds.setURL("jdbc:h2:mem:rfc0024_" + java.util.UUID.randomUUID());
                ds.setUser("sa");
                h2 = ds;
                keeper = ds.getConnection();
            }
            case "POSTGRESQL" -> {
                PostgreSQLContainer c = new PostgreSQLContainer("postgres:16-alpine");
                c.start();
                container = c;
                h2 = null;
                keeper = null;
            }
            case "MYSQL" -> {
                MySQLContainer c = new MySQLContainer("mysql:8.0.31");
                c.withUrlParam("connectionTimeZone", "UTC");
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

    int failures() {
        return failures;
    }

    String version() throws SQLException {
        try (Connection c = raw().getConnection()) {
            return c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion();
        }
    }

    DataSource raw() {
        switch (vendor) {
            case "H2":
                return h2;
            case "POSTGRESQL": {
                PGSimpleDataSource pg = new PGSimpleDataSource();
                pg.setURL(container.getJdbcUrl());
                pg.setUser(container.getUsername());
                pg.setPassword(container.getPassword());
                return pg;
            }
            default: {
                MysqlDataSource my = new MysqlDataSource();
                my.setURL(container.getJdbcUrl());
                my.setUser(container.getUsername());
                my.setPassword(container.getPassword());
                return my;
            }
        }
    }

    SessionFactory factory() {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        for (Class<?> entity : Entities.ALL) {
            cfg.addAnnotatedClass(entity);
        }
        cfg.getProperties().put("hibernate.connection.datasource",
                ProxyDataSourceBuilder.create(raw()).listener(new Capture()).build());
        return cfg.buildSessionFactory();
    }

    /** Runs {@code body} against a fresh executor rooted at {@code root}, capturing its statements. */
    <E, T> T run(SessionFactory sessions, Class<E> root, Function<ModelQueryExecutor<E>, T> body) {
        statements.clear();
        return sessions.fromSession(em -> body.apply(ModelQueryExecutor.create(em, root,
                ModelQueryConfig.defaults())));
    }

    <E, M> List<M> list(SessionFactory sessions, Class<E> root, ModelQuery<E, ?, M> query) {
        return run(sessions, root, executor -> executor.list(query, com.rey.modelquery.core.Limit.unlimited()));
    }

    List<Stmt> statements() {
        return List.copyOf(statements);
    }

    /** The SQL of the last captured select. */
    String lastSql() {
        if (statements.isEmpty()) {
            throw new Mismatch("no statement captured");
        }
        return statements.get(statements.size() - 1).sql();
    }

    /** Rows of a native query as {@code a|b|c} strings, NULL as {@code null}, decimals stripped of trailing zeros. */
    List<String> nativeRows(String sql) throws SQLException {
        var rows = new ArrayList<String>();
        try (Connection c = raw().getConnection(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                var cells = new ArrayList<String>();
                for (int i = 1; i <= columns; i++) {
                    cells.add(text(rs.getObject(i)));
                }
                rows.add(String.join("|", cells));
            }
        }
        return rows;
    }

    long nativeCount(String sql) throws SQLException {
        try (Connection c = raw().getConnection(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String text(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof java.math.BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number n && !(value instanceof Double || value instanceof Float)) {
            return Long.toString(n.longValue());
        }
        return value.toString();
    }

    static String row(Object... cells) {
        var parts = new ArrayList<String>();
        for (Object cell : cells) {
            parts.add(text(cell));
        }
        return String.join("|", parts);
    }

    static void expect(boolean condition, String message) {
        if (!condition) {
            throw new Mismatch(message);
        }
    }

    static void equal(List<String> actual, List<String> expected) {
        if (!actual.equals(expected)) {
            throw new Mismatch("rows differ:\n  engine " + actual + "\n  native " + expected);
        }
    }

    /** Indices of {@code tables} in {@code sql}'s FROM, in order; a missing table fails. */
    static void joinOrder(String sql, String... tables) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String table : tables) {
            int next = lower.indexOf(" " + table + " ", at + 1);
            expect(next > at, table + " is not after the previous join in: " + sql);
            at = next;
        }
    }

    /** Runs one cell and records it; a failing cell is recorded as FAIL and counted, never thrown. */
    void cell(String item, String name, Body body) {
        String status;
        String value;
        try {
            value = body.run();
            status = "PASS";
        } catch (Throwable failure) {
            failures++;
            status = "FAIL";
            value = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        record(item, name, status, value);
    }

    void record(String item, String name, String status, String value) {
        System.out.println("[" + vendor + " " + HIB + "] " + item + " " + name + " " + status + " " + value);
        tsv("rfc0024-" + HIB + ".tsv", "vendor\titem\tcase\tstatus\tvalue",
                String.join("\t", vendor, item, name, status, value.replace('\t', ' ')));
    }

    /** Writes the statements captured by the last run as {@code sql/<line>/<vendor>-<name>.sql}. */
    void sql(String name) {
        var text = new StringBuilder();
        for (Stmt stmt : statements) {
            text.append(stmt.sql()).append(";\n-- binds ").append(stmt.binds()).append("\n\n");
        }
        try {
            Path p = DIR.resolve("sql").resolve(HIB).resolve(vendor.toLowerCase(Locale.ROOT) + "-" + name + ".sql");
            Files.createDirectories(p.getParent());
            Files.writeString(p, text.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void tsv(String file, String header, String line) {
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
                if (!q.getQuery().stripLeading().regionMatches(true, 0, "select", 0, 6)
                        && !q.getQuery().stripLeading().regionMatches(true, 0, "with", 0, 4)) {
                    continue;
                }
                List<Object> binds = new ArrayList<>();
                for (List<ParameterSetOperation> params : q.getParametersList()) {
                    for (ParameterSetOperation op : params) {
                        Object[] args = op.getArgs();
                        binds.add(args.length >= 2 ? args[1] : null);
                    }
                }
                statements.add(new Stmt(q.getQuery(), binds));
            }
        }
    }
}
