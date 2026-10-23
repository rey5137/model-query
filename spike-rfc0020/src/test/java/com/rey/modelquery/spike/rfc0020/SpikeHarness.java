package com.rey.modelquery.spike.rfc0020;

import com.mysql.cj.jdbc.MysqlDataSource;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
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

/**
 * RFC 0020 spike harness: one vendor database, a proxied {@code DataSource} capturing every statement with its binds,
 * and the TSV writer ({@code vendor, probe, case, status, value, sql}). A cell never throws: a failure is a
 * {@code FAIL} row, since a refusal or a vendor limit is data (§Spike plan).
 */
public final class SpikeHarness implements AutoCloseable {

    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    public static final String HIB = Version.getVersionString();
    public static final Path OUT_DIR = Path.of("..", "rfc", "0020-spike-data");
    public static final Path OUT = OUT_DIR.resolve("rfc0020-" + HIB + ".tsv");
    private static boolean outInit = false;

    /** A captured statement: its SQL, its binds as text, and the bind calls to replay it over JDBC. */
    public record Stmt(String sql, String binds, List<ParameterSetOperation> ops) {
        @Override
        public String toString() {
            return binds.isEmpty() ? sql : sql + " || " + binds;
        }
    }

    /** A cell's expectation that did not hold; recorded as {@code FAIL} with its message. */
    public static final class Mismatch extends RuntimeException {
        public Mismatch(String message) {
            super(message);
        }
    }

    private final String vendor;
    private final List<Stmt> statements = new CopyOnWriteArrayList<>();
    private final java.util.Map<String, Stmt> kept = new java.util.concurrent.ConcurrentHashMap<>();
    private final JdbcDatabaseContainer<?> container;
    private final DataSource raw;
    private final DataSource dataSource;
    private int pass;
    private int fail;
    private int notRun;

    public SpikeHarness(String vendor) {
        this.vendor = vendor;
        switch (vendor) {
            case "H2" -> {
                container = null;
                JdbcDataSource h2 = new JdbcDataSource();
                h2.setURL("jdbc:h2:mem:rfc0020;DB_CLOSE_DELAY=-1");
                h2.setUser("sa");
                raw = h2;
            }
            case "POSTGRESQL" -> {
                PostgreSQLContainer pgContainer = new PostgreSQLContainer("postgres:16-alpine");
                pgContainer.withUrlParam("reWriteBatchedInserts", "true");
                pgContainer.start();
                container = pgContainer;
                PGSimpleDataSource pg = new PGSimpleDataSource();
                pg.setURL(container.getJdbcUrl());
                pg.setUser(container.getUsername());
                pg.setPassword(container.getPassword());
                raw = pg;
            }
            case "MYSQL" -> {
                MySQLContainer myContainer = new MySQLContainer("mysql:8.0.31");
                myContainer.withUrlParam("rewriteBatchedStatements", "true");
                myContainer.withUrlParam("connectionTimeZone", "UTC");
                myContainer.withUrlParam("forceConnectionTimeZoneToSession", "true");
                myContainer.start();
                container = myContainer;
                MysqlDataSource my = new MysqlDataSource();
                my.setURL(container.getJdbcUrl());
                my.setUser(container.getUsername());
                my.setPassword(container.getPassword());
                raw = my;
            }
            default -> throw new IllegalArgumentException("Unknown vendor: " + vendor);
        }
        dataSource = ProxyDataSourceBuilder.create(raw).listener(new Capture()).build();
    }

    public String vendor() {
        return vendor;
    }

    /** The unproxied data source: seeding, replays and plans, none of which is captured. */
    public DataSource raw() {
        return raw;
    }

    public SessionFactory factory(Class<?>... entities) {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.criteria.copy_tree", "true");
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        cfg.setProperty("jakarta.persistence.query.timeout", "60000");
        for (Class<?> entity : entities) {
            cfg.addAnnotatedClass(entity);
        }
        cfg.getProperties().put("hibernate.connection.datasource", dataSource);
        return cfg.buildSessionFactory();
    }

    public static <T> T inEm(SessionFactory sf, Function<EntityManager, T> body) {
        try (EntityManager em = sf.createEntityManager()) {
            return body.apply(em);
        }
    }

    /** Runs {@code body}, recording {@code PASS} with its value or {@code FAIL} with the error; never throws. */
    public boolean cell(String probe, String name, Callable<String> body) {
        statements.clear();
        try {
            String value = body.call();
            record(probe, name, "PASS", value, sql());
            pass++;
            return true;
        } catch (Throwable e) {
            record(probe, name, "FAIL", error(e), sql());
            fail++;
            return false;
        }
    }

    public void notRun(String probe, String name, String why) {
        record(probe, name, "NOT_RUN", why, "");
        notRun++;
    }

    public static void expect(boolean condition, String message) {
        if (!condition) {
            throw new Mismatch(message);
        }
    }

    public String counts() {
        return vendor + " " + HIB + ": PASS " + pass + ", FAIL " + fail + ", NOT_RUN " + notRun;
    }

    public String sql() {
        return statements.stream().map(Stmt::toString).collect(Collectors.joining(" ;; "));
    }

    public List<Stmt> statements() {
        return statements;
    }

    public Stmt lastStatement() {
        if (statements.isEmpty()) {
            throw new IllegalStateException("no statement captured");
        }
        return statements.get(statements.size() - 1);
    }

    /** Keeps the last captured statement under {@code key}, for the plan and timing cells (A.1, B.2, E.1, F.1). */
    public void keep(String key) {
        kept.put(key, lastStatement());
    }

    public Stmt kept(String key) {
        return kept.get(key);
    }

    /**
     * Re-runs {@code stmt} on the unproxied data source with its own bind calls, and returns, per selected column,
     * its JDBC type name, precision and scale, and the first row's value with its Java class (D.1: what the driver
     * reads, independently of the type Hibernate declares).
     */
    public List<String> replayColumns(Stmt stmt) throws SQLException {
        try (Connection conn = raw.getConnection(); PreparedStatement ps = prepare(conn, "", stmt)) {
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                boolean row = rs.next();
                List<String> out = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object value = row ? rs.getObject(i) : null;
                    out.add(md.getColumnTypeName(i) + "(" + md.getPrecision(i) + "," + md.getScale(i) + ")="
                            + describe(value));
                }
                return out;
            }
        }
    }

    /** Prepares {@code prefix + stmt.sql()} on {@code conn} and replays the statement's bind calls on it. */
    public static PreparedStatement prepare(Connection conn, String prefix, Stmt stmt) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(prefix + stmt.sql());
        try {
            for (ParameterSetOperation op : stmt.ops()) {
                Method method = op.getMethod();
                method.invoke(ps, op.getArgs());
            }
        } catch (ReflectiveOperationException e) {
            ps.close();
            throw new SQLException("bind replay failed", e);
        }
        return ps;
    }

    /** A value with its Java class and, for a {@code BigDecimal}, its scale. */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof java.math.BigDecimal bd) {
            return bd.toPlainString() + ":BigDecimal(scale " + bd.scale() + ")";
        }
        return value + ":" + value.getClass().getSimpleName();
    }

    public static String error(Throwable e) {
        StringBuilder out = new StringBuilder();
        Throwable t = e;
        for (int depth = 0; t != null && depth < 4; depth++, t = t.getCause()) {
            if (depth > 0) {
                out.append(" <- ");
            }
            String message = Objects.toString(t.getMessage(), "");
            out.append(t.getClass().getSimpleName()).append(": ")
                    .append(message.length() > 400 ? message.substring(0, 400) + "..." : message);
        }
        return out.toString();
    }

    public synchronized void record(String probe, String name, String status, String value, String sql) {
        try {
            if (!outInit) {
                Files.createDirectories(OUT.getParent());
                Files.writeString(OUT, "vendor\tprobe\tcase\tstatus\tvalue\tsql\n",
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                outInit = true;
            }
            String line = String.join("\t", vendor, escape(probe), escape(name), status, escape(value),
                    escape(sql)) + "\n";
            Files.writeString(OUT, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\t", " ").replace("\n", " ").replace("\r", "");
    }

    @Override
    public void close() {
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
                List<String> binds = new ArrayList<>();
                List<ParameterSetOperation> ops = new ArrayList<>();
                for (List<ParameterSetOperation> paramList : q.getParametersList()) {
                    for (ParameterSetOperation op : paramList) {
                        ops.add(op);
                        Object[] args = op.getArgs();
                        binds.add(args.length >= 2 ? Objects.toString(args[1]) : "?");
                    }
                }
                statements.add(new Stmt(q.getQuery(), String.join(", ", binds), ops));
            }
        }
    }
}
