package com.rey.modelquery.spike.rfc0019;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Harness for RFC 0019 spike: manages vendor databases, DataSource proxying,
 * statement capture, and TSV recording.
 */
public final class SpikeHarness implements AutoCloseable {

    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    public static final String HIB = Version.getVersionString();
    public static final Path OUT_DIR = Path.of("..", "rfc", "0019-spike-data");
    public static final Path OUT = OUT_DIR.resolve("rfc0019-" + HIB + ".tsv");
    private static boolean outInit = false;

    public record Stmt(String sql, String binds, List<ParameterSetOperation> ops) {
        @Override
        public String toString() {
            return binds.isEmpty() ? sql : sql + " || " + binds;
        }
    }

    private final String vendor;
    private final List<Stmt> statements = new CopyOnWriteArrayList<>();
    private final JdbcDatabaseContainer<?> container;
    private final DataSource dataSource;

    public SpikeHarness(String vendor) {
        this.vendor = vendor;
        DataSource raw;
        switch (vendor) {
            case "H2" -> {
                container = null;
                JdbcDataSource h2 = new JdbcDataSource();
                h2.setURL("jdbc:h2:mem:rfc0019;DB_CLOSE_DELAY=-1");
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

    public DataSource dataSource() {
        return dataSource;
    }

    public String vendor() {
        return vendor;
    }

    public SessionFactory factory(String what, Class<?>... entities) {
        return factory(what, Map.of(), entities);
    }

    public SessionFactory factory(String what, Map<String, String> settings, Class<?>... entities) {
        try {
            Configuration cfg = new Configuration();
            cfg.setProperty("hibernate.criteria.copy_tree", "true");
            cfg.setProperty("hibernate.hbm2ddl.auto", "update");
            cfg.setProperty("jakarta.persistence.query.timeout", "10000");
            settings.forEach(cfg::setProperty);
            for (Class<?> entity : entities) {
                cfg.addAnnotatedClass(entity);
            }
            cfg.getProperties().put("hibernate.connection.datasource", dataSource);
            return cfg.buildSessionFactory();
        } catch (RuntimeException e) {
            record("0", "bootstrap " + what, "FAIL", error(e), "");
            throw e;
        }
    }

    public void cell(String probe, String name, Callable<String> body) {
        statements.clear();
        String status;
        String value;
        try {
            value = body.call();
            status = "PASS";
        } catch (Throwable e) {
            value = error(e);
            status = "FAIL";
            record(probe, name, status, value, sql());
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new RuntimeException(e);
        }
        record(probe, name, status, value, sql());
    }

    public void notRun(String probe, String name, String why) {
        record(probe, name, "NOT_RUN", why, "");
    }

    public void observed(String probe, String name, String value) {
        record(probe, name, "OBSERVED", value, sql());
    }

    public void fail(String probe, String name, String value) {
        record(probe, name, "FAIL", value, sql());
    }

    public String sql() {
        return statements.stream().map(Stmt::toString).collect(Collectors.joining(" ;; "));
    }

    public String lastSql() {
        return statements.isEmpty() ? "" : statements.get(statements.size() - 1).sql();
    }

    public List<Stmt> statements() {
        return statements;
    }

    public void clearStatements() {
        statements.clear();
    }

    public static String error(Throwable e) {
        StringBuilder out = new StringBuilder();
        Throwable t = e;
        for (int depth = 0; t != null && depth < 4; depth++, t = t.getCause()) {
            if (depth > 0) {
                out.append(" <- ");
            }
            out.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return out.toString();
    }

    private synchronized void record(String probe, String name, String status, String value, String sql) {
        try {
            if (!outInit) {
                Files.createDirectories(OUT.getParent());
                Files.writeString(OUT, "vendor\tprobe\tcase\tstatus\tvalue\tsql\n",
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                outInit = true;
            }
            String line = String.format("%s\t%s\t%s\t%s\t%s\t%s\n",
                    vendor,
                    escape(probe),
                    escape(name),
                    status,
                    escape(value),
                    escape(sql));
            Files.writeString(OUT, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\t", " ").replace("\n", " ").replace("\r", "");
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
