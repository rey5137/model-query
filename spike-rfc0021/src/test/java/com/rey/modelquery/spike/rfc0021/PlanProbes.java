package com.rey.modelquery.spike.rfc0021;

import static com.rey.modelquery.spike.rfc0021.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0021.Q.D;
import com.rey.modelquery.spike.rfc0021.Q.P;
import com.rey.modelquery.spike.rfc0021.Q.Sel;
import com.rey.modelquery.spike.rfc0021.SpikeHarness.Stmt;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

/**
 * A.4 cost: the Details page of 20, its count and a full export, with and without the entity join (referenced and
 * unreferenced), against the native SQL. Each statement is built through Hibernate, executed once through the session
 * (which also checks it runs and captures it with its binds), then re-run on one unproxied JDBC connection: 2
 * warm-up rounds and 7 timed rounds, each round running every variant of the cell with the order rotated by one, so
 * the variants alternate; the median of the 7 is kept. After the timed runs one plan is taken (PostgreSQL
 * {@code EXPLAIN (ANALYZE, BUFFERS)}, MySQL {@code EXPLAIN ANALYZE}, H2 {@code EXPLAIN}).
 */
final class PlanProbes {

    static final int WARMUP = 2;
    static final int RUNS = 7;
    static final Path TIMINGS = SpikeHarness.OUT_DIR.resolve("rfc0021-timings-" + SpikeHarness.HIB + ".tsv");
    private static boolean timingsInit = false;

    private record Variant(String name, Stmt stmt) {}

    private interface Build {
        Stmt run(jakarta.persistence.EntityManager em, HibernateCriteriaBuilder cb);
    }

    private final SpikeHarness h;
    private final SessionFactory sf;
    private final Connection conn;
    private final Path planFile;

    private PlanProbes(SpikeHarness h, SessionFactory sf, Connection conn) {
        this.h = h;
        this.sf = sf;
        this.conn = conn;
        this.planFile = SpikeHarness.OUT_DIR.resolve("plans").resolve(SpikeHarness.HIB)
                .resolve(h.vendor().toLowerCase(Locale.ROOT) + ".txt");
    }

    static void run(SpikeHarness h, SessionFactory sf) throws Exception {
        try (Connection conn = h.raw().getConnection()) {
            conn.setAutoCommit(true);
            if (h.vendor().equals("H2")) {
                // H2 otherwise hands a re-run of a cached command its previous result without executing it.
                try (Statement st = conn.createStatement()) {
                    st.execute("set optimize_reuse_results 0");
                }
            }
            PlanProbes p = new PlanProbes(h, sf, conn);
            Files.createDirectories(p.planFile.getParent());
            Files.writeString(p.planFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            p.analyze();
            for (Long hierarchy : new Long[] {1L, null}) {
                p.cells(hierarchy);
            }
        }
    }

    private void cells(Long hierarchy) {
        String idx = hierarchy == null ? "all hierarchies" : "hierarchy 1";
        P details = PartAProbes.details().with(p -> p.hierarchy = hierarchy);

        List<Variant> page = new ArrayList<>();
        add(page, "native", idx, nat(Q.nativeDetails(details, false), 0, 20));
        add(page, "lib: entity join, referenced (Details)", idx, lib(details, Sel.DETAILS, 0, 20));
        add(page, "lib: no manager columns, entity join attached (3 columns read)", idx,
                lib(details, Sel.UNREFERENCED, 0, 20));
        add(page, "lib: unreferenced, no entity join", idx,
                lib(details.with(p -> p.attachEntity = false), Sel.UNREFERENCED, 0, 20));
        add(page, "lib: Details without the manager (no entity join, null columns)", idx,
                lib(details.with(p -> p.attachEntity = false), Sel.DETAILS, 0, 20));
        time("A.4/page of 20", idx, page);

        List<Variant> count = new ArrayList<>();
        add(count, "native", idx, nat(Q.nativeDetails(details, true), 0, 0));
        add(count, "lib: count, entity join attached unreferenced", idx, lib(details, Sel.COUNT, 0, 0));
        add(count, "lib: count, no entity join", idx, lib(details.with(p -> p.attachEntity = false), Sel.COUNT, 0, 0));
        time("A.4/count", idx, count);

        List<Variant> export = new ArrayList<>();
        add(export, "native", idx, nat(Q.nativeDetails(details, false), 0, 0));
        add(export, "lib: entity join, referenced (Details)", idx, lib(details, Sel.DETAILS, 0, 0));
        add(export, "lib: unreferenced, entity join attached", idx, lib(details, Sel.UNREFERENCED, 0, 0));
        add(export, "lib: unreferenced, no entity join", idx,
                lib(details.with(p -> p.attachEntity = false), Sel.UNREFERENCED, 0, 0));
        time("A.4/full export", idx, export);
    }

    private void add(List<Variant> out, String name, String idx, Build build) {
        try {
            Stmt stmt = inEm(sf, em -> build.run(em, (HibernateCriteriaBuilder) em.getCriteriaBuilder()));
            out.add(new Variant(name, stmt));
        } catch (RuntimeException e) {
            timing("A.4", name, idx, "FAIL", "", "", "", SpikeHarness.error(e), "");
        }
    }

    private Build nat(String sql, int first, int max) {
        return (em, cb) -> {
            var q = em.createNativeQuery(sql);
            if (first > 0) {
                q.setFirstResult(first);
            }
            if (max > 0) {
                q.setMaxResults(max);
            }
            q.getResultList();
            return h.lastStatement();
        };
    }

    private Build lib(P p, Sel sel, int first, int max) {
        return (em, cb) -> {
            D d = new D(cb, p);
            d.build(sel);
            var q = em.createQuery(d.q);
            if (first > 0) {
                q.setFirstResult(first);
            }
            if (max > 0) {
                q.setMaxResults(max);
            }
            q.getResultList();
            return h.lastStatement();
        };
    }

    // ------------------------------------------------------------------------------------------------ machinery

    private void time(String cell, String idx, List<Variant> variants) {
        if (variants.isEmpty()) {
            return;
        }
        int n = variants.size();
        List<List<Double>> samples = new ArrayList<>();
        long[] rows = new long[n];
        String[] errors = new String[n];
        for (int i = 0; i < n; i++) {
            samples.add(new ArrayList<>());
        }
        for (int round = 0; round < WARMUP + RUNS; round++) {
            for (int k = 0; k < n; k++) {
                int i = (k + round) % n;
                if (errors[i] != null) {
                    continue;
                }
                try {
                    long start = System.nanoTime();
                    rows[i] = execute(variants.get(i).stmt());
                    double ms = (System.nanoTime() - start) / 1e6;
                    if (round >= WARMUP) {
                        samples.get(i).add(ms);
                    }
                } catch (SQLException e) {
                    errors[i] = SpikeHarness.error(e);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Variant v = variants.get(i);
            if (errors[i] != null) {
                timing(cell, v.name(), idx, "FAIL", "", "", "", errors[i], v.stmt().toString());
                continue;
            }
            String summary;
            try {
                String plan = explain(v.stmt());
                writePlan(cell, v.name(), idx, v.stmt(), plan);
                summary = summarize(plan);
            } catch (SQLException e) {
                summary = "plan failed: " + SpikeHarness.error(e);
            }
            List<Double> s = new ArrayList<>(samples.get(i));
            Collections.sort(s);
            timing(cell, v.name(), idx, "PASS", String.format(Locale.ROOT, "%.3f", s.get(s.size() / 2)),
                    samples.get(i).stream().map(d -> String.format(Locale.ROOT, "%.3f", d)).toList().toString(),
                    Long.toString(rows[i]), summary, v.stmt().toString());
        }
    }

    /** Executes {@code stmt} and reads every column of every row; returns the row count. */
    private long execute(Stmt stmt) throws SQLException {
        try (PreparedStatement ps = SpikeHarness.prepare(conn, "", stmt); ResultSet rs = ps.executeQuery()) {
            int columns = rs.getMetaData().getColumnCount();
            long count = 0;
            while (rs.next()) {
                for (int c = 1; c <= columns; c++) {
                    rs.getObject(c);
                }
                count++;
            }
            return count;
        }
    }

    private String explain(Stmt stmt) throws SQLException {
        String prefix = switch (h.vendor()) {
            case "POSTGRESQL" -> "explain (analyze, buffers) ";
            case "MYSQL" -> "explain analyze ";
            default -> "explain ";
        };
        try (PreparedStatement ps = SpikeHarness.prepare(conn, prefix, stmt); ResultSet rs = ps.executeQuery()) {
            List<String> lines = new ArrayList<>();
            while (rs.next()) {
                lines.add(rs.getString(1));
            }
            return String.join("\n", lines);
        }
    }

    private static final Pattern PG_NODE = Pattern.compile("^\\s*(?:->\\s+)?([A-Z][A-Za-z ]+?)(?: on | using |"
            + "  \\(|\\s+\\(cost)");
    private static final Pattern MY_NODE = Pattern.compile("^\\s*->\\s+([^(:]+?)(?:\\s+\\(cost|\\s+\\(actual|:|$)");
    private static final Pattern BUFFERS = Pattern.compile("Buffers: ([^\\n]+)");
    private static final Pattern EXEC = Pattern.compile("Execution Time: ([0-9.]+ ms)");
    private static final Pattern PG_PROFILE = Pattern.compile("(?m)^.*(?:Scan|Join).* on profile (?!_type)\\w*.*$");
    private static final Pattern PG_PROFILE_TYPE = Pattern.compile("(?m)^.* on profile_type .*$");

    /** A one-line plan digest: scans of profile and profile_type, top buffers, CTE handling, node kinds in order. */
    private String summarize(String plan) {
        StringBuilder out = new StringBuilder();
        String lower = plan.toLowerCase(Locale.ROOT);
        switch (h.vendor()) {
            case "POSTGRESQL" -> {
                out.append("profile scans=").append(count(PG_PROFILE, plan)).append("; profile_type scans=")
                        .append(count(PG_PROFILE_TYPE, plan)).append("; ");
                out.append("cte=").append(plan.contains("CTE Scan") ? "materialized" : "none/inlined").append("; ");
                Matcher b = BUFFERS.matcher(plan);
                out.append("top buffers=").append(b.find() ? b.group(1).trim() : "none").append("; ");
                Matcher e = EXEC.matcher(plan);
                out.append("exec=").append(e.find() ? e.group(1) : "?").append("; ");
                out.append("nodes=").append(nodes(plan, PG_NODE));
            }
            case "MYSQL" -> {
                out.append("cte=").append(lower.contains("materialize cte") ? "materialized"
                        : lower.contains("materialize") ? "derived materialized" : "merged/none").append("; ");
                out.append("nodes=").append(nodes(plan, MY_NODE));
            }
            default -> out.append("plan=").append(plan.replaceAll("\\s+", " ").trim());
        }
        String s = out.toString();
        return s.length() > 900 ? s.substring(0, 900) + "..." : s;
    }

    private static int count(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String nodes(String plan, Pattern node) {
        List<String> out = new ArrayList<>();
        for (String line : plan.split("\n")) {
            Matcher m = node.matcher(line);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty() && !name.startsWith("Buffers") && !name.startsWith("Planning")
                        && !name.startsWith("Execution")) {
                    out.add(name.length() > 60 ? name.substring(0, 60) : name);
                }
            }
        }
        return String.join(" > ", out);
    }

    private void writePlan(String cell, String name, String idx, Stmt stmt, String plan) {
        String text = "### " + h.vendor() + " | " + cell + " | " + name + " | " + idx + "\n-- " + stmt + "\n"
                + plan + "\n\n";
        try {
            Files.writeString(planFile, text, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized void timing(String cell, String name, String idx, String status, String median,
            String runs, String rows, String plan, String sql) {
        try {
            if (!timingsInit) {
                Files.writeString(TIMINGS, "vendor\tcell\tcase\tindex\tstatus\tmedian_ms\truns_ms\trows\tplan\tsql\n",
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                timingsInit = true;
            }
            String line = String.join("\t", h.vendor(), cell, name, idx, status, median, runs, rows,
                    clean(plan), clean(sql)) + "\n";
            Files.writeString(TIMINGS, line, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String clean(String s) {
        return s.replace("\t", " ").replace("\n", " ").replace("\r", "");
    }

    private void analyze() throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (h.vendor().equals("MYSQL")) {
                for (String t : List.of("hierarchy", "hierarchy_node", "hierarchy_edge", "profile", "profile_type")) {
                    st.execute("analyze table " + t);
                }
            } else {
                st.execute("analyze");
            }
        }
    }
}
