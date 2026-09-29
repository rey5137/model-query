package com.rey.modelquery.tck.sql;

import static org.assertj.core.api.Assertions.fail;

import com.rey.modelquery.tck.harness.TckDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;

/**
 * SQL-snapshot harness (spec delivery/60 R-QA-03, R-QA-04). Runs some work against a {@code datasource-proxy}
 * wrapped {@link DataSource}, captures every statement it executed, and asserts they equal the committed file
 * {@code src/test/resources/sql/<vendor>/<name>.sql}.
 *
 * <p>Normalization: each statement has every run of whitespace (including line breaks) collapsed to one space and
 * is trimmed; the file holds one statement per line, in execution order, each line ended by {@code \n}. Nothing
 * else is rewritten. Prepared statements are captured with their {@code ?} placeholders, never bound values.
 *
 * <p>A mismatch or a missing snapshot fails the test with a diff. Run with {@code -Dsql.snapshots.update=true} to
 * rewrite the files under {@code src/test/resources}; the change then appears in the PR diff for review.
 */
public final class SqlSnapshots {

    /** System property that switches on rewriting of snapshot files. */
    public static final String UPDATE_PROPERTY = "sql.snapshots.update";

    private static final String RESOURCE_DIR = "src/test/resources/sql";

    private SqlSnapshots() {}

    /** The work whose SQL is captured; it receives a DataSource over the database under test. */
    @FunctionalInterface
    public interface SqlWork {
        void run(DataSource dataSource) throws Exception;
    }

    /**
     * Runs {@code work} and asserts its SQL equals the snapshot {@code name} for the database's vendor. Returns the
     * captured statements, normalized as the snapshot holds them, for a test that also inspects them.
     */
    public static List<String> assertMatches(TckDatabase db, String name, SqlWork work) {
        List<String> actual = capture(db, work);
        Path file = snapshotFile(db, name);
        boolean update = Boolean.getBoolean(UPDATE_PROPERTY);
        String rendered = String.join("\n", actual) + "\n";
        try {
            if (update) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, rendered, StandardCharsets.UTF_8);
                return actual;
            }
            if (!Files.exists(file)) {
                fail("Missing SQL snapshot %s. Captured:%n%s%nRe-run with -D%s=true to create it, then review the diff.",
                        file, rendered, UPDATE_PROPERTY);
            }
            List<String> expected = readLines(file);
            if (!expected.equals(actual)) {
                fail("SQL differs from snapshot %s (- snapshot, + actual). Re-run with -D%s=true only if the change "
                        + "is intended:%n%s", file, UPDATE_PROPERTY, diff(expected, actual));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot access snapshot " + file, e);
        }
        return actual;
    }

    /**
     * Runs {@code work} and returns every statement it executed, normalized as a snapshot holds them, without
     * comparing them to a file: for a test that counts or inspects statements too long to commit.
     */
    public static List<String> capture(TckDatabase db, SqlWork work) {
        List<String> captured = new ArrayList<>();
        DataSource proxy = ProxyDataSourceBuilder.create(new DriverManagerDataSource(db))
                .afterQuery((execInfo, queries) -> queries.forEach(q -> captured.add(normalize(q.getQuery()))))
                .build();
        try {
            work.run(proxy);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SQL snapshot work failed", e);
        }
        return captured;
    }

    static String normalize(String sql) {
        return sql.trim().replaceAll("\\s+", " ");
    }

    private static Path snapshotFile(TckDatabase db, String name) {
        String vendor = db.vendor().name().toLowerCase(Locale.ROOT);
        return Path.of(System.getProperty("basedir", ".")).resolve(RESOURCE_DIR).resolve(vendor).resolve(name + ".sql");
    }

    private static List<String> readLines(Path file) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** Line diff by longest common subsequence: '-' only in the snapshot, '+' only in the actual SQL. */
    static String diff(List<String> expected, List<String> actual) {
        int n = expected.size();
        int m = actual.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = expected.get(i).equals(actual.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && expected.get(i).equals(actual.get(j))) {
                out.append("  ").append(expected.get(i)).append('\n');
                i++;
                j++;
            } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                out.append("- ").append(expected.get(i++)).append('\n');
            } else {
                out.append("+ ").append(actual.get(j++)).append('\n');
            }
        }
        return out.toString();
    }

    /** Minimal DataSource over {@link TckDatabase}; no pooling, each call opens a new connection. */
    private static final class DriverManagerDataSource implements DataSource {
        private final TckDatabase db;

        DriverManagerDataSource(TckDatabase db) {
            this.db = db;
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            return db.getConnection();
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            return db.getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
            throw new java.sql.SQLException("Not a wrapper for " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
