package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Employee;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.EmployeeEmb;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.EmployeeIc;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Invoice;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

/** Measures the proposed count forms directly; production RowCounts remains unchanged. */
final class JoinedCountProbes {
    private static boolean initialized;
    private JoinedCountProbes() {}

    static void run(SpikeHarness h, SessionFactory sessions) throws Exception {
        if (!initialized) {
            Files.deleteIfExists(SpikeHarness.OUT_DIR.resolve("joined-count-timings-" + SpikeHarness.HIB + ".tsv"));
            initialized = true;
        }
        for (String anchor : List.of("column", "source")) {
            for (Q.Target target : Q.Target.values()) {
                for (JoinKind kind : JoinKind.values()) {
                    for (String path : kind == JoinKind.LEFT ? List.of("plain", "to-one", "filter")
                            : List.of("plain", "to-one")) {
                        for (boolean grouped : List.of(false, true)) {
                            String cell = anchor + "/" + target + "/" + kind + "/" + path + "/"
                                    + (grouped ? "grouped" : "ungrouped");
                            h.cell("D.1", cell, () -> execute(h, sessions, anchor, target, kind, path, grouped));
                        }
                    }
                }
            }
        }
    }

    private static String execute(SpikeHarness h, SessionFactory sessions, String anchor, Q.Target target,
            JoinKind kind, String path, boolean grouped) throws Exception {
        try (var em = sessions.createEntityManager()) {
            var cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            var q = cb.createTupleQuery();
            var root = q.from(Invoice.class);
            From<?, ?> source = root;
            if (anchor.equals("source")) {
                var body = cb.createTupleQuery();
                var invoice = body.from(Invoice.class);
                body.multiselect(invoice.get("id").alias("bodyId"), invoice.get("repCode").alias("bodyRep"),
                        invoice.get("regionCode").alias("bodyRegion"));
                source = ParameterProbes.source(q, root, body);
                ParameterProbes.onSource(source, cb.equal(source.get("bodyId"), root.get("id")));
            }
            From<?, ?> columns = source;
            Class<?> entity = switch (target) {
                case SIMPLE -> Employee.class;
                case ID_CLASS -> EmployeeIc.class;
                case EMBEDDED -> EmployeeEmb.class;
            };
            From<?, ?> employee = Q.provider(sessions).entityJoins().orElseThrow().join(root, entity, kind, join -> {
                Path<?> id = target == Q.Target.EMBEDDED ? join.get("id") : join;
                var code = columns.get(anchor.equals("source") ? "bodyRep" : "repCode");
                var region = columns.get(anchor.equals("source") ? "bodyRegion" : "regionCode");
                var key = cb.equal(id.get("code"), code);
                if (target != Q.Target.SIMPLE) { key = cb.and(key, cb.equal(id.get("regionCode"), region)); }
                return path.equals("filter") ? cb.and(key, cb.isTrue(join.get("active"))) : key;
            });
            Path<?> identity = target == Q.Target.EMBEDDED ? employee.get("id").get("code") : employee.get("code");
            From<?, ?> counted = employee;
            if (path.equals("to-one")) {
                counted = employee.join("team", jakarta.persistence.criteria.JoinType.INNER);
                identity = counted.get("id");
            }
            Expression<Long> filtered = cb.count(cb.<Integer>selectCase()
                    .when(cb.and(cb.isNotNull(identity), cb.lt(root.get("id"), 25L)), cb.literal(1))
                    .otherwise(cb.nullLiteral(Integer.class)));
            var selections = new ArrayList<jakarta.persistence.criteria.Selection<?>>();
            if (grouped) { selections.add(employee.get("name")); }
            selections.add(cb.count());
            selections.add(cb.count(identity));
            selections.add(filtered);
            q.multiselect(selections);
            if (grouped) { q.groupBy(employee.get("name")); }
            var query = em.createQuery(q).setHint("jakarta.persistence.query.timeout", 60_000);
            var rows = query.getResultList();
            String table = switch (target) {
                case SIMPLE -> "employee";
                case ID_CLASS -> "employee_ic";
                case EMBEDDED -> "employee_emb";
            };
            String from = " from invoice i " + (kind == JoinKind.LEFT ? "left join " : "join ") + table
                    + " e on e.code=i.rep_code" + (target == Q.Target.SIMPLE ? "" : " and e.region_code=i.region_code")
                    + (path.equals("filter") ? " and e.active=true" : "")
                    + (path.equals("to-one") ? " join team t on t.id=e.team_id" : "");
            String id = path.equals("to-one") ? "t.id" : "e.code";
            String sql = "select " + (grouped ? "e.name," : "") + "count(*),count(" + id + "),"
                    + "count(case when " + id + " is not null and i.id<25 then 1 else null end)" + from
                    + (grouped ? " group by e.name" : "");
            var actual = new ArrayList<>(Q.rows(rows));
            var wanted = new ArrayList<>(Q.nativeRows(h, sql));
            Collections.sort(actual); Collections.sort(wanted); Q.equal(actual, wanted);
            boolean eligible = kind == JoinKind.INNER;
            boolean unmatched = false;
            for (var row : rows) {
                int offset = grouped ? 1 : 0;
                long star = ((Number) row.get(offset)).longValue();
                long idCount = ((Number) row.get(offset + 1)).longValue();
                if (eligible) { SpikeHarness.expect(star == idCount, "eligible count(*) differs from count(id)"); }
                else if (star != idCount) {
                    unmatched = true;
                    SpikeHarness.expect(star > idCount, "LEFT count ordering");
                    if (grouped) { SpikeHarness.expect(idCount == 0, "unmatched LEFT group must count zero ids"); }
                }
            }
            if (!eligible && !path.equals("to-one")) {
                SpikeHarness.expect(unmatched, "fixture did not exercise unmatched/blanked LEFT rows");
            }
            if (h.vendor().equals("POSTGRESQL") && grouped && target == Q.Target.SIMPLE
                    && path.equals("plain") && kind == JoinKind.INNER) {
                timing(h, anchor, "star", "select e.name,count(*)" + from + " group by e.name");
                timing(h, anchor, "id", "select e.name,count(e.code)" + from + " group by e.name");
            }
            return "native rows equal; eligible=" + eligible + "; LEFT difference=" + unmatched
                    + "; filtered 1-under-condition checked";
        }
    }

    private static void timing(SpikeHarness h, String anchor, String form, String sql) throws Exception {
        var samples = new ArrayList<Double>();
        try (var connection = h.raw().getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            for (int run = 0; run < 9; run++) {
                long start = System.nanoTime();
                try (var rows = statement.executeQuery(sql)) {
                    while (rows.next()) { rows.getString(1); rows.getLong(2); }
                }
                if (run >= 2) { samples.add((System.nanoTime() - start) / 1_000_000.0); }
            }
        }
        Collections.sort(samples);
        var file = SpikeHarness.OUT_DIR.resolve("joined-count-timings-" + SpikeHarness.HIB + ".tsv");
        if (!Files.exists(file)) {
            Files.writeString(file, "vendor\tanchor\tform\tmedian_ms\tvalue\n");
        }
        Files.writeString(file, String.join("\t", h.vendor(), anchor, form, samples.get(3).toString(),
                "info only: grouped read execution; no latency threshold") + "\n", StandardOpenOption.APPEND);
    }
}
