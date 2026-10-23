package com.rey.modelquery.spike.rfc0020;

import static com.rey.modelquery.spike.rfc0020.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.BusinessDay;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Promo;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Sale;
import com.rey.modelquery.spike.rfc0020.SpikeHarness.Stmt;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
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
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.criteria.JpaSubQuery;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * The timed and plan cells (RFC 0020 §Spike plan items 1, 3, 6, 7): A.1's three "unreconciled days" shapes, E.1's
 * null-safe forms against an index, B.2 against B.1, F.1's three count forms. Each statement is rendered by Hibernate
 * (captured through the proxy), then re-run on the unproxied data source: 2 warm-up rounds, 7 timed rounds with the
 * variants' order rotated each round, the median kept; then one plan ({@code EXPLAIN (ANALYZE, BUFFERS)} on
 * PostgreSQL, {@code EXPLAIN ANALYZE} on MySQL, {@code EXPLAIN} on H2). Timings go to
 * {@code rfc0020-timings-<hibernate>.tsv}, plan texts to {@code plans/<hibernate>/<vendor>.txt}.
 */
final class PlanProbes {

    static final int WARMUP = 2;
    static final int RUNS = 7;
    /** A variant whose first warm-up run exceeds this gets one warm-up and {@value #SLOW_RUNS} timed runs. */
    static final double SLOW_MS = 5_000;
    static final int SLOW_RUNS = 3;
    static final String PROMO_INDEX = "ix_sale_promo_code";
    static final String PROMO_CODE_INDEX = "ix_promo_code";
    static final Path TIMINGS = SpikeHarness.OUT_DIR.resolve("rfc0020-timings-" + SpikeHarness.HIB + ".tsv");
    private static boolean timingsInit = false;

    private static final Method DERIVED_JOIN =
            PartAProbes.method(JpaFrom.class, "join", Subquery.class, SqmJoinType.class);

    private record Variant(String name, Stmt stmt) {}

    private interface Build {
        void run(HibernateCriteriaBuilder cb, jakarta.persistence.EntityManager em);
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
            for (boolean index : List.of(false, true)) {
                if (index) {
                    p.createIndex(Rfc0020Dataset.SALE_DATE_INDEX, "business_date");
                }
                p.a1(index);
                if (index) {
                    p.dropIndex(Rfc0020Dataset.SALE_DATE_INDEX);
                }
            }
            p.createIndex(PROMO_INDEX, "promo_code");
            p.createIndex(PROMO_CODE_INDEX, "promo", "code");
            p.e1();
            p.dropIndex(PROMO_CODE_INDEX, "promo");
            p.dropIndex(PROMO_INDEX);
            p.b();
            p.f1();
        }
    }

    // ---------------------------------------------------------------------------------------------------- A.1

    /**
     * "Unreconciled days" ({@code counted_cash} distinct from the day's cash sum, in the RFC's two-valued portable
     * {@code distinctFromColumn} form): (a) two correlated sums, (b) LEFT join to the grouped CTE, (c) to the derived
     * table. {@code count unfiltered} drops the filter, so the join is unreferenced (PostgreSQL's join removal).
     */
    private void a1(boolean index) {
        String idx = index ? "with " + Rfc0020Dataset.SALE_DATE_INDEX : "no index";
        for (String kind : List.of("page of 20", "full export", "count", "count unfiltered")) {
            List<Variant> variants = new ArrayList<>();
            for (String shape : List.of("a correlated", "b cte", "c derived")) {
                Stmt stmt = capture("A.1/" + kind, shape, idx, (cb, em) -> a1Statement(cb, em, shape, kind));
                if (stmt != null) {
                    variants.add(new Variant(shape, stmt));
                }
            }
            time("A.1/" + kind, idx, variants, index ? Rfc0020Dataset.SALE_DATE_INDEX : null);
        }
    }

    private static void a1Statement(HibernateCriteriaBuilder cb, jakarta.persistence.EntityManager em, String shape,
            String kind) {
        boolean count = kind.startsWith("count");
        JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
        JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
        Expression<?> cash;
        Expression<?> total;
        if (shape.startsWith("a")) {
            cash = correlatedSum(cb, q, d, "cashTotal");
            total = correlatedSum(cb, q, d, "total");
        } else {
            JpaFrom<?, Tuple> x = shape.startsWith("b") ? cteJoin(cb, q, d) : derivedJoin(cb, q, d);
            cash = x.get("mq_cash");
            total = x.get("mq_total");
        }
        if (!kind.equals("count unfiltered")) {
            // RFC 0020 Part E: distinctFrom of two operands is the NOT of the two-valued OR form.
            q.where(cb.not(PartEProbes.orForm(cb, d.get("countedCash"), cash)));
        }
        if (count) {
            q.multiselect(cb.count(d));
            em.createQuery(q).getResultList();
            return;
        }
        q.multiselect(d.get("id"), d.get("businessDate"), d.get("countedCash"), cash, total);
        var query = em.createQuery(q.orderBy(kind.equals("page of 20")
                ? cb.desc(d.get("businessDate")) : cb.asc(d.get("id"))));
        if (kind.equals("page of 20")) {
            query.setMaxResults(20);
        }
        query.getResultList();
    }

    private static Subquery<BigDecimal> correlatedSum(HibernateCriteriaBuilder cb, JpaCriteriaQuery<Tuple> q,
            JpaRoot<BusinessDay> d, String attribute) {
        Subquery<BigDecimal> sub = q.subquery(BigDecimal.class);
        Root<Sale> s = sub.from(Sale.class);
        return sub.select(cb.sum(s.<BigDecimal>get(attribute)))
                .where(cb.equal(s.get("businessDate"), d.get("businessDate")));
    }

    private static JpaFrom<?, Tuple> cteJoin(HibernateCriteriaBuilder cb, JpaCriteriaQuery<Tuple> q,
            JpaRoot<BusinessDay> d) {
        JpaCriteriaQuery<Tuple> body = cb.createTupleQuery();
        JpaRoot<Sale> s = body.from(Sale.class);
        body.multiselect(s.get("businessDate").alias("mq_businessDate"),
                cb.sum(s.<BigDecimal>get("cashTotal")).alias("mq_cash"),
                cb.sum(s.<BigDecimal>get("total")).alias("mq_total")).groupBy(s.get("businessDate"));
        JpaCteCriteria<Tuple> cte = q.with(body);
        return PartAProbes.join(d, cte, SqmJoinType.LEFT, x -> cb.equal(x.get("mq_businessDate"),
                d.get("businessDate")));
    }

    @SuppressWarnings("unchecked")
    private static JpaFrom<?, Tuple> derivedJoin(HibernateCriteriaBuilder cb, JpaCriteriaQuery<Tuple> q,
            JpaRoot<BusinessDay> d) {
        JpaSubQuery<Tuple> sub = q.subquery(Tuple.class);
        JpaRoot<Sale> s = sub.from(Sale.class);
        sub.multiselect(s.get("businessDate").alias("mq_businessDate"),
                cb.sum(s.<BigDecimal>get("cashTotal")).alias("mq_cash"),
                cb.sum(s.<BigDecimal>get("total")).alias("mq_total")).groupBy(s.get("businessDate"));
        JpaFrom<?, Tuple> x = (JpaFrom<?, Tuple>) PartAProbes.invoke(DERIVED_JOIN, d, sub, SqmJoinType.LEFT);
        Predicate on = cb.equal(x.get("mq_businessDate"), d.get("businessDate"));
        PartAProbes.invoke(PartAProbes.method(DERIVED_JOIN.getReturnType(), "on", Predicate[].class), x,
                (Object) new Predicate[] {on});
        return x;
    }

    // ---------------------------------------------------------------------------------------------------- E.1

    /**
     * Index use of the null-safe forms, with {@value #PROMO_INDEX} on {@code sale.promo_code} and
     * {@value #PROMO_CODE_INDEX} on {@code promo.code}. On PostgreSQL every two-operand cell is also re-planned and
     * re-timed under {@code set enable_seqscan = off}, to show whether an index plan exists at all and what it costs.
     */
    private void e1() throws SQLException {
        String idx = "with " + PROMO_INDEX;
        List<Variant> filter = new ArrayList<>();
        addE1(filter, "E.1/filter", "eq baseline", (cb, s) -> cb.equal(s.get("promoCode"), "P7"));
        addE1(filter, "E.1/filter", "value form (= ? and is not null)",
                (cb, s) -> PartEProbes.valueForm(cb, s.get("promoCode"), "P7"));
        addE1(filter, "E.1/filter", "native notDistinctFrom(col, ?)",
                (cb, s) -> cb.notDistinctFrom(s.get("promoCode"), "P7"));
        time("E.1/filter", idx, filter, PROMO_INDEX);

        String both = idx + ", " + PROMO_CODE_INDEX;
        List<Variant> twoOperands = twoOperandFilter(both);
        List<Variant> exists = existsVariants(both);
        String names = PROMO_INDEX + "," + PROMO_CODE_INDEX;
        time("E.1/filter-2op", both, twoOperands, names);
        time("E.1/exists", both, exists, names);
        if (h.vendor().equals("POSTGRESQL")) {
            try (Statement st = conn.createStatement()) {
                st.execute("set enable_seqscan = off");
                try {
                    time("E.1/filter-2op", both + ", enable_seqscan=off", twoOperands, names);
                    time("E.1/exists", both + ", enable_seqscan=off", exists, names);
                } finally {
                    st.execute("reset enable_seqscan");
                }
            }
        }
    }

    /** Promo ids the two-operand filter reads: one matched code, one NULL code, three codes no sale has. */
    static final List<Long> FILTER_PROMOS = List.of(7L, 51L, 61L, 62L, 63L);

    private static Predicate twoOperand(HibernateCriteriaBuilder cb, String form, Expression<?> a, Expression<?> b) {
        return switch (form) {
            case "eq baseline" -> cb.equal(a, b);
            case "or-form" -> PartEProbes.orForm(cb, a, b);
            case "bare-or" -> PartEProbes.bareForm(cb, a, b);
            default -> cb.notDistinctFrom(a, b);
        };
    }

    /** A filter over two nullable indexed columns: {@code sale, promo where <form>(s.promo_code, p.code)}. */
    private List<Variant> twoOperandFilter(String idx) {
        List<Variant> out = new ArrayList<>();
        for (String form : List.of("eq baseline", "or-form", "bare-or", "native")) {
            Stmt stmt = capture("E.1/filter-2op", form, idx, (cb, em) -> {
                JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                Root<Sale> s = q.from(Sale.class);
                Root<Promo> p = q.from(Promo.class);
                q.multiselect(s.get("id"), p.get("id"))
                        .where(twoOperand(cb, form, s.get("promoCode"), p.get("code")), p.get("id").in(FILTER_PROMOS));
                em.createQuery(q).getResultList();
            });
            if (stmt != null) {
                out.add(new Variant(form, stmt));
            }
        }
        return out;
    }

    /**
     * Promos with a sale of the same code, null-safely. {@code eq baseline, non-null codes} drops the NULL-code promos
     * from the outer query: an {@code =} lookup of a NULL key reads every NULL index entry on some vendors.
     */
    private List<Variant> existsVariants(String idx) {
        List<Variant> out = new ArrayList<>();
        for (String form : List.of("eq baseline", "eq baseline, non-null codes", "or-form", "bare-or", "native")) {
            Stmt stmt = capture("E.1/exists", form, idx, (cb, em) -> {
                JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
                Root<Promo> p = q.from(Promo.class);
                Subquery<Long> sub = q.subquery(Long.class);
                Root<Sale> s = sub.from(Sale.class);
                sub.select(s.get("id")).where(twoOperand(cb, form.startsWith("eq") ? "eq baseline" : form,
                        s.get("promoCode"), p.get("code")));
                Predicate where = form.endsWith("non-null codes")
                        ? cb.and(cb.exists(sub), cb.isNotNull(p.get("code"))) : cb.exists(sub);
                em.createQuery(q.select(p.get("id")).where(where).orderBy(cb.asc(p.get("id")))).getResultList();
            });
            if (stmt != null) {
                out.add(new Variant(form, stmt));
            }
        }
        return out;
    }

    private interface SaleWhere {
        Predicate on(HibernateCriteriaBuilder cb, Root<Sale> s);
    }

    private void addE1(List<Variant> out, String cell, String name, SaleWhere where) {
        Stmt stmt = capture(cell, name, "with " + PROMO_INDEX, (cb, em) -> {
            JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
            Root<Sale> s = q.from(Sale.class);
            em.createQuery(q.select(s.get("id")).where(where.on(cb, s))).getResultList();
        });
        if (stmt != null) {
            out.add(new Variant(name, stmt));
        }
    }

    // ------------------------------------------------------------------------------------------------ B and F.1

    /** B.2 (ranked source) against B.1 (array_agg first-by), on the statements the functional cells kept. */
    private void b() {
        for (String by : List.of("day", "category")) {
            time("B/litepos " + by, "no index", kept(List.of("B.1/litepos/" + by + "/arrayGet",
                    "B.1/litepos/" + by + "/pattern", "B.2/litepos/" + by), "B/litepos " + by), null);
        }
    }

    private void f1() {
        for (String cell : List.of("F.1/grouped", "F.1/page-total")) {
            List<String> keys = PartFProbes.FORMS.stream().map(f -> cell + "/" + f).toList();
            time(cell, "no index", kept(keys, cell), null);
        }
    }

    private List<Variant> kept(List<String> keys, String cell) {
        List<Variant> out = new ArrayList<>();
        for (String key : keys) {
            Stmt stmt = h.kept(key);
            if (stmt == null) {
                timing(cell, key, "no index", "NOT_RUN", "", "", "",
                        "its functional cell failed on this vendor; no statement kept", "");
            } else {
                out.add(new Variant(key, stmt));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ machinery

    /** Runs {@code build} through Hibernate and returns the statement it executed, or records a FAIL row. */
    private Stmt capture(String cell, String name, String idx, Build build) {
        try {
            inEm(sf, em -> {
                build.run((HibernateCriteriaBuilder) em.getCriteriaBuilder(), em);
                return null;
            });
            return h.lastStatement();
        } catch (RuntimeException e) {
            timing(cell, name, idx, "FAIL", "", "", "", SpikeHarness.error(e), "");
            return null;
        }
    }

    private void time(String cell, String idx, List<Variant> variants, String indexName) {
        if (variants.isEmpty()) {
            return;
        }
        int n = variants.size();
        List<List<Double>> samples = new ArrayList<>();
        long[] rows = new long[n];
        String[] errors = new String[n];
        boolean[] slow = new boolean[n];
        for (int i = 0; i < n; i++) {
            samples.add(new ArrayList<>());
        }
        for (int round = 0; round < WARMUP + RUNS; round++) {
            for (int k = 0; k < n; k++) {
                int i = (k + round) % n;
                boolean slowSkip = slow[i] && (round == 1 || samples.get(i).size() >= SLOW_RUNS);
                if (errors[i] != null || slowSkip) {
                    continue;
                }
                try {
                    long start = System.nanoTime();
                    rows[i] = execute(variants.get(i).stmt());
                    double ms = (System.nanoTime() - start) / 1e6;
                    if (round >= WARMUP) {
                        samples.get(i).add(ms);
                    } else if (ms > SLOW_MS) {
                        slow[i] = true;
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
                summary = summarize(plan, indexName);
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

    /** A one-line plan digest: node kinds in order, top buffers, CTE handling and whether the index shows up. */
    private String summarize(String plan, String indexName) {
        StringBuilder out = new StringBuilder();
        String lower = plan.toLowerCase(Locale.ROOT);
        if (indexName != null) {
            for (String name : indexName.split(",")) {
                out.append(indexName.contains(",") ? "index " + name + "=" : "index=")
                        .append(lower.contains(name.toLowerCase(Locale.ROOT)) ? "used" : "not used").append("; ");
            }
        }
        switch (h.vendor()) {
            case "POSTGRESQL" -> {
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
        return s.length() > 700 ? s.substring(0, 700) + "..." : s;
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

    // ------------------------------------------------------------------------------------------------ setup

    private void analyze() throws SQLException {
        try (Statement st = conn.createStatement()) {
            switch (h.vendor()) {
                case "POSTGRESQL" -> st.execute("analyze");
                case "MYSQL" -> {
                    for (String t : List.of("business_day", "sale", "sale_line", "item", "customer", "promo",
                            "inventory_movement")) {
                        st.execute("analyze table " + t);
                    }
                }
                default -> st.execute("analyze");
            }
        }
    }

    private void createIndex(String name, String column) throws SQLException {
        createIndex(name, "sale", column);
    }

    private void createIndex(String name, String table, String column) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("create index " + name + " on " + table + " (" + column + ")");
        }
        analyze();
    }

    private void dropIndex(String name) throws SQLException {
        dropIndex(name, "sale");
    }

    private void dropIndex(String name, String table) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(h.vendor().equals("MYSQL") ? "drop index " + name + " on " + table : "drop index " + name);
        }
        analyze();
    }
}
