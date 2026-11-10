package com.rey.modelquery.spike.rfc0023;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.spike.rfc0023.Emulator.Form;
import com.rey.modelquery.spike.rfc0023.Events.Event;
import com.rey.modelquery.spike.rfc0023.Events.Range;
import com.rey.modelquery.spike.rfc0023.Events.Row;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Spike plan items 1 to 3. A probe never throws: a failure is a FAIL cell, since a vendor limit is data. */
final class Probes {
    private final Harness h;
    private final int rows;
    private final SessionFactory sf;
    private final int round;

    Probes(Harness h, SessionFactory sf, int rows) {
        this.h = h;
        this.sf = sf;
        this.rows = rows;
        // the chunk size byKeys uses on every vendor here (observed: 8192 keys per statement), which R-FCH-05's rounds match
        this.round = 8192;
    }

    interface Body {
        String run(ModelQueryExecutor<Event> ex) throws Exception;
    }

    private void cell(String probe, String name, Body body) {
        h.clear();
        try (EntityManager em = sf.createEntityManager()) {
            String value = body.run(ModelQueryExecutor.create(em, Event.class, ModelQueryConfig.defaults()));
            h.cell(probe, name, "PASS", value);
        } catch (Throwable e) {
            h.cell(probe, name, "FAIL", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void expect(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }

    // ---------------------------------------------------------------- keys

    private static int[] months(int groups) {
        int[] out = new int[groups];
        for (int j = 0; j < groups; j++) {
            out[j] = j * Events.MONTHS / groups;
        }
        return out;
    }

    /** {@code count} distinct (month, n) rows spread round-robin over {@code groups} months of the 36. */
    private List<int[]> rowsOf(int count, int groups, long seed) {
        int[] months = months(groups);
        Random random = new Random(seed);
        Set<Long> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (out.size() < count) {
            int month = months[i++ % groups];
            int n = random.nextInt(rows);
            if (seen.add(month * 1_000_000L + n)) {
                out.add(new int[] {month, n});
            }
            expect(i < count * 200L, "cannot sample " + count + " rows over " + groups + " months of " + rows);
        }
        return out;
    }

    private List<Long> ids(List<int[]> rowRefs) {
        return rowRefs.stream().map(r -> Events.id(r[0], r[1], rows)).toList();
    }

    private List<Long> orders(List<int[]> rowRefs) {
        return rowRefs.stream().map(r -> Events.orderId(r[0], r[1], rows)).distinct().toList();
    }

    private static String texts(List<Harness.Stmt> stmts) {
        Map<Integer, Integer> perLength = new TreeMap<>();
        Set<String> distinct = new LinkedHashSet<>();
        for (Harness.Stmt s : stmts) {
            distinct.add(s.sql());
            perLength.merge(s.marks(), 1, Integer::sum);
        }
        return "statements=" + stmts.size() + " distinctTexts=" + distinct.size() + " bindsPerStatement="
                + stmts.stream().map(s -> String.valueOf(s.marks())).collect(Collectors.joining(","));
    }

    // ---------------------------------------------------------------- item 1: rendering and binds

    void rendering() {
        // three groups (months 5, 17, 30) of 3, 5 and 7 keys
        int[][] spec = {{5, 3}, {17, 5}, {30, 7}};
        List<int[]> refs = new ArrayList<>();
        for (int[] s : spec) {
            for (int i = 0; i < s[1]; i++) {
                refs.add(new int[] {s[0], 10 + i * 5});
            }
        }
        List<Long> ids = ids(refs);
        List<Long> orders = orders(refs);
        cell("1", "byKeys-two-sided/3 groups of 3,5,7", ex -> {
            var em = new Emulator(ex);
            var plain = em.byKeysUngrouped(ids);
            h.clear();
            var grouped = em.byKeysGrouped(Form.TWO_SIDED, ids, Range::monthOf, Events.ALL);
            expect(grouped.equals(plain) && grouped.size() == ids.size(), "grouped result differs from ungrouped");
            return texts(h.statements()) + " rows=" + grouped.size() + " sameAsUngrouped=true";
        });
        cell("1", "byKeys-two-sided/3 groups of 3,5,3 (same length shares a text)", ex -> {
            List<Long> same = ids(List.of(new int[] {5, 1}, new int[] {5, 2}, new int[] {5, 3}, new int[] {17, 1},
                    new int[] {17, 2}, new int[] {17, 3}, new int[] {17, 4}, new int[] {17, 5}, new int[] {30, 1},
                    new int[] {30, 2}, new int[] {30, 3}));
            new Emulator(ex).byKeysGrouped(Form.TWO_SIDED, same, Range::monthOf, Events.ALL);
            return texts(h.statements());
        });
        cell("1", "byKeys-lower/3 groups of 3,5,7", ex -> {
            var em = new Emulator(ex);
            var plain = em.byKeysUngrouped(ids);
            h.clear();
            var grouped = em.byKeysGrouped(Form.LOWER, ids, Range::monthOf, Events.ALL);
            expect(grouped.equals(plain), "grouped result differs from ungrouped");
            return texts(h.statements()) + " rows=" + grouped.size();
        });
        cell("1", "child-lower/3 groups", ex -> {
            var em = new Emulator(ex);
            var plain = em.childUngrouped(orders, round);
            h.clear();
            var grouped = em.childGrouped(Form.LOWER, orders, Range::monthOf, round);
            expect(new TreeSet<>(grouped.stream().map(Row::id).toList())
                    .equals(new TreeSet<>(plain.stream().map(Row::id).toList())), "grouped rows differ");
            return texts(h.statements()) + " orders=" + orders.size() + " rows=" + grouped.size();
        });
        // one group over the bind limit: 3 * round / 2 synthetic keys in month 17 (they need not exist), plus 5 in month 5
        cell("1", "byKeys-two-sided/one group over the limit", ex -> {
            List<Long> keys = new ArrayList<>();
            for (int i = 0; i < round + round / 4; i++) {
                keys.add(Events.idOf(Events.createdAt(17, 1, 100).plusMillis(i), i));
            }
            for (int i = 0; i < 5; i++) {
                keys.add(Events.id(5, i, rows));
            }
            h.clear();
            new Emulator(ex).byKeysGrouped(Form.TWO_SIDED, keys, Range::monthOf, null);
            return "keysInBigGroup=" + (round + round / 4) + " limitRound=" + round + " " + texts(h.statements());
        });
        cell("1", "child-lower/one group over the limit", ex -> {
            List<Long> keys = new ArrayList<>();
            for (int i = 0; i < round + round / 4; i++) {
                keys.add(Events.idOf(Events.createdAt(17, 1, 100).plusMillis(i), i));
            }
            for (int i = 0; i < 5; i++) {
                keys.add(Events.orderId(5, i, rows));
            }
            h.clear();
            new Emulator(ex).childGrouped(Form.LOWER, keys, Range::monthOf, round);
            return "keysInBigGroup=" + (round + round / 4) + " limitRound=" + round + " " + texts(h.statements());
        });
        // the same keys ungrouped, for the baseline
        cell("1", "byKeys-ungrouped/" + ids.size() + " keys", ex -> {
            new Emulator(ex).byKeysUngrouped(ids);
            return texts(h.statements());
        });
    }

    // ---------------------------------------------------------------- item 2: pruning

    private static final Pattern PARTITION = Pattern.compile("event_log_p(\\d{6})");

    private static OffsetDateTime utc(Object v) {
        if (v instanceof OffsetDateTime o) {
            return o.withOffsetSameInstant(ZoneOffset.UTC);
        }
        if (v instanceof Instant i) {
            return i.atOffset(ZoneOffset.UTC);
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (v instanceof java.time.LocalDateTime l) {
            return l.atOffset(ZoneOffset.UTC);
        }
        return null;
    }

    private static String literal(Object v, boolean mysql) {
        OffsetDateTime t = utc(v);
        if (t != null) {
            return "'" + (mysql ? t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")) : t.toString())
                    + "'";
        }
        if (v instanceof Number) {
            return v.toString();
        }
        return "'" + v + "'";
    }

    private static String bindTypes(Harness.Stmt s) {
        return s.binds().stream().map(b -> b == null ? "null" : b.getClass().getSimpleName()).distinct()
                .collect(Collectors.joining("/"));
    }

    /** A statement the engine emits for each form, for 3 keys of month 17, captured with its real binds. */
    private Harness.Stmt capture(String form, String keyKind) {
        h.clear();
        List<int[]> refs = List.of(new int[] {17, 100}, new int[] {17, 200}, new int[] {17, 300});
        try (EntityManager em = sf.createEntityManager()) {
            var ex = ModelQueryExecutor.create(em, Event.class, ModelQueryConfig.defaults());
            run(new Emulator(ex), form, keyKind, refs, false);
        }
        return h.statements().get(h.statements().size() - 1);
    }

    /** Runs one round: form is "two-sided", "lower" or "none"; keyKind is "byKeys" or "child". */
    private void run(Emulator em, String form, String keyKind, List<int[]> refs, boolean lock) {
        Form f = Form.valueOf(form.toUpperCase().replace('-', '_'));
        if (keyKind.equals("byKeys")) {
            List<Long> ids = ids(refs);
            Range r = Range.monthOf(ids.get(0));
            var q = switch (f) {
                case TWO_SIDED -> Events.TWO_SIDED.bind(Events.FROM, r.from()).bind(Events.TO, r.to()).build();
                case LOWER -> Events.LOWER.bind(Events.FROM, r.from()).build();
                case NONE -> Events.NONE.build();
            };
            ModelQueryExecutor<Event> ex = em.executor();
            ex.byKeys(lock ? q.withLock(RowLock.shared()) : q, ids);
        } else {
            List<Long> orders = orders(refs);
            Range r = Range.monthOf(orders.get(0));
            var q = switch (f) {
                case TWO_SIDED -> Events.childTwoSided(orders, r);
                case LOWER -> Events.childLower(orders, r);
                case NONE -> Events.childNone(orders);
            };
            em.executor().list(lock ? q.withLock(RowLock.shared()) : q, Limit.unlimited());
        }
    }

    private static final String[][] CASES = {
            {"byKeys", "two-sided"}, {"byKeys", "lower"}, {"byKeys", "none"},
            {"child", "two-sided"}, {"child", "lower"}, {"child", "none"}};

    void pruning() throws Exception {
        if (h.vendor().equals("H2")) {
            return;
        }
        boolean pg = h.vendor().equals("POSTGRESQL");
        try (Connection admin = h.raw("").getConnection()) {
            if (!pg) {
                admin.createStatement().execute("set session time_zone = '+00:00'");
            }
            for (String[] c : CASES) {
                String name = c[0] + "/" + c[1];
                Harness.Stmt stmt;
                try {
                    stmt = capture(c[1], c[0]);
                } catch (Throwable e) {
                    h.cell("2", name, "FAIL", "capture " + e);
                    continue;
                }
                if (pg) {
                    for (String mode : new String[] {"force_generic_plan", "force_custom_plan"}) {
                        pgExplain(admin, name, mode, stmt);
                    }
                } else {
                    mysqlExplain(admin, name, "event_log", stmt);
                    mysqlExplain(admin, name, "event_log_td", stmt);
                }
            }
        }
        Thread.sleep(1500);
        for (String[] c : CASES) {
            String name = c[0] + "/" + c[1];
            if (pg) {
                pgJdbcPath(name, c, "default", "");
                pgJdbcPath(name, c, "prepareThreshold=1", "prepareThreshold=1");
                pgJdbcPath(name, c, "prepareThreshold=1+force_generic_plan",
                        "prepareThreshold=1&options=-c%20plan_cache_mode=force_generic_plan");
            } else {
                mysqlJdbcPath(name, c, "client-side-prepare (default)", "");
                mysqlJdbcPath(name, c, "useServerPrepStmts=true", "useServerPrepStmts=true");
            }
        }
    }

    private void pgExplain(Connection c, String name, String mode, Harness.Stmt s) {
        try (Statement st = c.createStatement()) {
            StringBuilder sql = new StringBuilder();
            int n = 0;
            for (char ch : s.sql().toCharArray()) {
                if (ch == '?') {
                    sql.append('$').append(++n);
                } else {
                    sql.append(ch);
                }
            }
            st.execute("set plan_cache_mode = " + mode);
            st.execute("prepare s as " + sql);
            String args = s.binds().stream().map(b -> literal(b, false)).collect(Collectors.joining(", "));
            StringBuilder plan = new StringBuilder();
            // five executions: the generic plan is what plan_cache_mode forces; the custom one is re-planned per value
            for (int i = 0; i < 2; i++) {
                plan.setLength(0);
                try (ResultSet rs = st.executeQuery("explain (analyze, costs off, timing off, summary off) execute s("
                        + args + ")")) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
            }
            st.execute("deallocate s");
            String text = plan.toString();
            Harness.plan("pg", name.replace('/', '-') + "-" + mode, s.sql() + "\n-- binds " + s.binds() + "\n" + text);
            Matcher removed = Pattern.compile("Subplans Removed: (\\d+)").matcher(text);
            String removedN = removed.find() ? removed.group(1) : "none";
            Set<String> scanned = new TreeSet<>();
            Set<String> never = new TreeSet<>();
            for (String line : text.split("\n")) {
                Matcher m = PARTITION.matcher(line);
                if (m.find()) {
                    (line.contains("never executed") ? never : scanned).add(m.group(1));
                }
            }
            h.cell("2", name + "/explain/" + mode, "PASS", "subplansRemoved=" + removedN + " partitionsScanned="
                    + scanned.size() + " " + scanned + " neverExecuted=" + never.size() + " bindTypes=" + bindTypes(s));
        } catch (Throwable e) {
            h.cell("2", name + "/explain/" + mode, "FAIL", e.toString());
        }
    }

    private void mysqlExplain(Connection c, String name, String table, Harness.Stmt s) {
        try (Statement st = c.createStatement()) {
            StringBuilder sql = new StringBuilder();
            int n = 0;
            for (char ch : s.sql().toCharArray()) {
                sql.append(ch == '?' ? literal(s.binds().get(n++), true) : String.valueOf(ch));
            }
            String text = sql.toString().replaceAll("\\bevent_log\\b", table);
            String partitions;
            String tree;
            try (ResultSet rs = st.executeQuery("explain " + text)) {
                rs.next();
                partitions = rs.getString("partitions");
            }
            try (ResultSet rs = st.executeQuery("explain analyze " + text)) {
                rs.next();
                tree = rs.getString(1);
            }
            Harness.plan("mysql", name.replace('/', '-') + "-" + table, text + "\n-- partitions: " + partitions + "\n" + tree);
            int count = partitions == null ? 0 : partitions.split(",").length;
            h.cell("2", name + "/explain-literals/" + table, "PASS", "partitionsCount=" + count + " partitions="
                    + partitions + " bindTypes=" + bindTypes(s));
        } catch (Throwable e) {
            h.cell("2", name + "/explain-literals/" + table, "FAIL", e.toString());
        }
    }

    private void pgJdbcPath(String name, String[] c, String variant, String params) {
        try (Connection admin = h.raw("").getConnection()) {
            SessionFactory vf = h.factory(params);
            try {
                Map<String, Long> before = pgScans(admin);
                String prepared = "";
                try (EntityManager em = vf.createEntityManager()) {
                    em.getTransaction().begin();
                    var emu = new Emulator(ModelQueryExecutor.create(em, Event.class, ModelQueryConfig.defaults()));
                    List<int[]> refs = List.of(new int[] {17, 100}, new int[] {17, 200}, new int[] {17, 300});
                    for (int i = 0; i < 8; i++) {
                        run(emu, c[1], c[0], refs, false);
                    }
                    prepared = em.unwrap(Session.class).doReturningWork(conn -> {
                        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                                "select count(*), coalesce(sum(generic_plans), 0), coalesce(sum(custom_plans), 0) "
                                        + "from pg_prepared_statements")) {
                            rs.next();
                            return "serverPrepared=" + rs.getLong(1) + " genericPlans=" + rs.getLong(2)
                                    + " customPlans=" + rs.getLong(3);
                        }
                    });
                    em.getTransaction().commit();
                }
                Thread.sleep(1500);
                Map<String, Long> after = pgScans(admin);
                TreeMap<String, Long> delta = new TreeMap<>();
                after.forEach((k, v) -> {
                    long d = v - before.getOrDefault(k, 0L);
                    if (d > 0) {
                        delta.put(k.substring("event_log_p".length()), d);
                    }
                });
                h.cell("2", name + "/jdbc/" + variant, "PASS", "executions=8 partitionsScanned=" + delta.size()
                        + " scansPerPartition=" + delta + " " + prepared);
            } finally {
                vf.close();
            }
        } catch (Throwable e) {
            h.cell("2", name + "/jdbc/" + variant, "FAIL", e.toString());
        }
    }

    private static Map<String, Long> pgScans(Connection c) throws Exception {
        Map<String, Long> out = new TreeMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "select relname, coalesce(seq_scan, 0) + coalesce(idx_scan, 0) from pg_stat_user_tables "
                        + "where relname like 'event\\_log\\_p%'")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    private void mysqlJdbcPath(String name, String[] c, String variant, String params) {
        SessionFactory vf = null;
        try {
            vf = h.factory(params);
            try (EntityManager em = vf.createEntityManager()) {
                em.getTransaction().begin();
                var emu = new Emulator(ModelQueryExecutor.create(em, Event.class, ModelQueryConfig.defaults()));
                List<int[]> refs = List.of(new int[] {17, 100}, new int[] {17, 200}, new int[] {17, 300});
                Session session = em.unwrap(Session.class);
                run(emu, c[1], c[0], refs, false);
                long b = session.doReturningWork(conn -> status(conn, "Handler_read_key"));
                long e0 = session.doReturningWork(conn -> status(conn, "Com_stmt_execute"));
                run(emu, c[1], c[0], refs, false);
                long handler = session.doReturningWork(conn -> status(conn, "Handler_read_key")) - b;
                long serverExecs = session.doReturningWork(conn -> status(conn, "Com_stmt_execute")) - e0;
                run(emu, c[1], c[0], refs, true);
                String locks = session.doReturningWork(conn -> {
                    TreeMap<String, Long> parts = new TreeMap<>();
                    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                            "select partition_name, count(*) from performance_schema.data_locks "
                                    + "where object_name = 'event_log' and lock_type = 'RECORD' group by partition_name")) {
                        while (rs.next()) {
                            parts.put(String.valueOf(rs.getString(1)).replace("p", ""), rs.getLong(2));
                        }
                    }
                    return parts.size() + " " + parts;
                });
                em.getTransaction().commit();
                h.cell("2", name + "/jdbc/" + variant, "PASS", "Handler_read_key per execution=" + handler + " Com_stmt_execute=" + serverExecs
                        + " partitionsWithRecordLocks(for share)=" + locks);
            }
        } catch (Throwable e) {
            h.cell("2", name + "/jdbc/" + variant, "FAIL", e.toString());
        } finally {
            if (vf != null) {
                vf.close();
            }
        }
    }

    private static long status(Connection conn, String name) throws java.sql.SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "show session status like '" + name + "'")) {
            rs.next();
            return rs.getLong(2);
        }
    }

    // ---------------------------------------------------------------- item 3: cost

    record Timing(double median, double min, double max, int statements, int rowsRead, double jdbcMs) {
        String ms() {
            return String.format("%.1f", median);
        }
    }

    private Timing time(int warm, int runs, Supplier<Integer> body) {
        for (int i = 0; i < warm; i++) {
            body.get();
        }
        double[] t = new double[runs];
        int stmts = 0;
        int read = 0;
        for (int i = 0; i < runs; i++) {
            h.clear();
            long start = System.nanoTime();
            read = body.get();
            t[i] = (System.nanoTime() - start) / 1e6;
            stmts = h.statements().size();
        }
        java.util.Arrays.sort(t);
        return new Timing(t[runs / 2], t[0], t[runs - 1], stmts, read, replay(new ArrayList<>(h.statements()), runs));
    }

    /** The last run's statements replayed on a bare JDBC connection: round trips and the database, no Hibernate. */
    private double replay(List<Harness.Stmt> stmts, int runs) {
        try (Connection c = h.raw("").getConnection()) {
            double[] t = new double[runs];
            for (int i = -2; i < runs; i++) {
                long start = System.nanoTime();
                for (Harness.Stmt s : stmts) {
                    try (var ps = Harness.prepare(c, s); ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getLong(1);
                        }
                    }
                }
                if (i >= 0) {
                    t[i] = (System.nanoTime() - start) / 1e6;
                }
            }
            java.util.Arrays.sort(t);
            return t[runs / 2];
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String COST_HEADER = "vendor\tscenario\tform\tkeys\tgroups\tungrouped_stmts\tungrouped_ms\tungrouped_jdbc_ms\t"
            + "grouped_stmts\tgrouped_ms\tgrouped_jdbc_ms\tgrouped/ungrouped\tjdbc_grouped/ungrouped\trows\tnote";

    private void costRow(String scenario, String form, int keys, int groups, Timing u, Timing g, String note) {
        String line = String.join("\t", h.vendor(), scenario, form, "" + keys, "" + groups, "" + u.statements(),
                u.ms(), String.format("%.1f", u.jdbcMs()), "" + g.statements(), g.ms(), String.format("%.1f", g.jdbcMs()),
                String.format("%.2f", g.median() / u.median()), String.format("%.2f", g.jdbcMs() / u.jdbcMs()),
                "" + g.rowsRead(), note);
        Harness.tsv("rfc0023-cost-" + Harness.HIB + ".tsv", COST_HEADER, line);
        System.out.println("[" + h.vendor() + " " + Harness.HIB + "] " + line);
    }

    void cost(int runs) {
        try (EntityManager em = sf.createEntityManager()) {
            var ex = ModelQueryExecutor.create(em, Event.class, ModelQueryConfig.defaults());
            var emu = new Emulator(ex);
            for (int keys : new int[] {50, 200, 1000, 10_000}) {
                for (int groups : new int[] {1, 3, 12, 36}) {
                    if (groups > keys) {
                        continue;
                    }
                    List<int[]> refs = rowsOf(keys, groups, 7);
                    List<Long> ids = ids(refs);
                    List<Long> orders = orders(refs);
                    String scenario = keys == 10_000 ? "10k-keys" : keys == 50 && groups == 36 ? "page-50-over-36-months"
                            : "sweep";
                    int r = keys == 50 ? runs * 2 : runs;
                    // byKeys, two-sided (key = id; the group is the id's month)
                    try {
                        var plainMap = emu.byKeysUngrouped(ids);
                        var groupedMap = emu.byKeysGrouped(Form.TWO_SIDED, ids, Range::monthOf, null);
                        expect(plainMap.equals(groupedMap), "byKeys results differ");
                        Timing u = time(3, r, () -> emu.byKeysUngrouped(ids).size());
                        Timing g = time(3, r, () -> emu.byKeysGrouped(Form.TWO_SIDED, ids, Range::monthOf, null).size());
                        costRow(scenario, "byKeys two-sided", keys, groups, u, g, "assumeInRange");
                        Timing gv = time(3, r, () -> emu.byKeysGrouped(Form.TWO_SIDED, ids, Range::monthOf,
                                Events.ALL).size());
                        costRow(scenario, "byKeys two-sided+verify(no skew)", keys, groups, u, gv, "verifyMissing, none missing");
                    } catch (Throwable e) {
                        h.cell("3", scenario + "/byKeys/" + keys + "/" + groups, "FAIL", e.toString());
                    }
                    // child load, lower bound only (key = order id, to-many)
                    try {
                        var plainRows = emu.childUngrouped(orders, round);
                        var groupedRows = emu.childGrouped(Form.LOWER, orders, Range::monthOf, round);
                        expect(plainRows.size() == groupedRows.size(), "child rows differ " + plainRows.size() + " vs "
                                + groupedRows.size());
                        Timing u = time(3, r, () -> emu.childUngrouped(orders, round).size());
                        Timing g = time(3, r, () -> emu.childGrouped(Form.LOWER, orders, Range::monthOf, round).size());
                        costRow(scenario, "child lower-bound", keys, groups, u, g, "orders=" + orders.size());
                    } catch (Throwable e) {
                        h.cell("3", scenario + "/child/" + keys + "/" + groups, "FAIL", e.toString());
                    }
                }
            }
            verify(emu, runs);
        }
    }

    /** Keys whose id's month disagrees with created_at: the group says month m+1 (m-1 for the last), the row is in m. */
    private void verify(Emulator emu, int runs) {
        for (int keys : new int[] {50, 1000, 10_000}) {
            List<Long> ids = ids(rowsOf(keys, 36, 11));
            for (int pct : new int[] {1, 10}) {
                Set<Long> skewed = new LinkedHashSet<>(ids.subList(0, Math.max(1, ids.size() * pct / 100)));
                Function<Long, Range> group = id -> {
                    Range m = Range.monthOf(id);
                    if (!skewed.contains(id)) {
                        return m;
                    }
                    java.time.YearMonth ym = java.time.YearMonth.from(m.from().atZone(ZoneOffset.UTC));
                    return Range.month(ym.equals(Events.FIRST.plusMonths(Events.MONTHS - 1)) ? ym.minusMonths(1)
                            : ym.plusMonths(1));
                };
                try {
                    var plain = emu.byKeysUngrouped(ids);
                    var verified = emu.byKeysGrouped(Form.TWO_SIDED, ids, group, Events.ALL);
                    expect(plain.equals(verified), "verifyMissing result differs from ungrouped");
                    var lost = emu.byKeysGrouped(Form.TWO_SIDED, ids, group, null);
                    expect(lost.size() == ids.size() - skewed.size(), "assumeInRange should lose exactly the skewed keys");
                    Timing u = time(3, runs, () -> emu.byKeysUngrouped(ids).size());
                    Timing g = time(3, runs, () -> emu.byKeysGrouped(Form.TWO_SIDED, ids, group, Events.ALL).size());
                    costRow("verifyMissing-" + pct + "pct", "byKeys two-sided+verify", keys, 36, u, g,
                            "skewed=" + skewed.size() + "; assumeInRange would lose " + skewed.size());
                } catch (Throwable e) {
                    h.cell("3", "verify/" + keys + "/" + pct, "FAIL", e.toString());
                }
            }
        }
    }
}
