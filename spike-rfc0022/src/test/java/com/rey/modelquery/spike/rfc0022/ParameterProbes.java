package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Employee;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Invoice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.sqm.tree.SqmJoinType;

/** Prototype request registry and statement-local parameter sink; no proposed production API is implemented. */
final class ParameterProbes {
    private ParameterProbes() {}

    static void run(SpikeHarness h, SessionFactory sessions) throws Exception {
        rendering(h, sessions, "bind");
        phases(h, sessions, "bind");
        try (var inline = h.factory(Map.of("hibernate.hbm2ddl.auto", "none",
                "hibernate.criteria.value_handling_mode", "inline"), Rfc0022Entities.ALL)) {
            rendering(h, inline, "inline");
            phases(h, inline, "inline");
        }
    }

    private static void rendering(SpikeHarness h, SessionFactory sessions, String mode) {
        for (String shape : List.of("bare", "arithmetic", "case-condition", "case-result", "coalesce",
                "function", "correlated", "source-body", "group-order")) {
            h.cell("C.1", mode + "/" + shape, () -> {
                String[] sql = new String[2];
                for (int i = 0; i < 2; i++) {
                    int value = i == 0 ? 7 : 9;
                    try (var em = sessions.createEntityManager()) {
                        var built = render(em, shape, value);
                        var query = em.createQuery(built.criteria());
                        bind(query, built.values());
                        query.setHint("jakarta.persistence.query.timeout", 60_000);
                        Q.equal(Q.rows(query.getResultList()), Q.nativeRows(h, built.nativeSql()));
                        sql[i] = h.statements().get(h.statements().size() - 1).sql();
                    }
                }
                SpikeHarness.expect(sql[0].equals(sql[1]), "SQL changed with request value: " + mode + "/" + shape);
                return "one typed node per request param; both values match native; stable SQL; mode=" + mode;
            });
        }
    }

    private record Built(JpaCriteriaQuery<Tuple> criteria, Map<ParameterExpression<?>, Object> values,
                         String nativeSql) {}

    private static Built render(EntityManager em, String shape, int value) throws Exception {
        var cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
        var q = cb.createTupleQuery();
        var root = q.from(Invoice.class);
        ParameterExpression<String> text = cb.parameter(String.class, "requestText");
        ParameterExpression<Long> number = cb.parameter(Long.class, "requestNumber");
        Map<ParameterExpression<?>, Object> values = new LinkedHashMap<>();
        String s = "req" + value;
        String nativeSql;
        Expression<?> selected;
        switch (shape) {
            case "bare" -> { selected = text; nativeSql = "'" + s + "'"; values.put(text, s); }
            case "arithmetic" -> {
                selected = cb.sum(root.<Long>get("id"), number);
                nativeSql = "i.id+" + value;
                values.put(number, (long) value);
            }
            case "case-condition" -> {
                selected = cb.<String>selectCase().when(cb.equal(text, cb.literal("req7")),
                        cb.literal("hit")).otherwise(cb.literal("miss"));
                nativeSql = "case when '" + s + "'='req7' then 'hit' else 'miss' end";
                values.put(text, s);
            }
            case "case-result" -> {
                selected = cb.<String>selectCase().when(cb.lt(root.get("id"), cb.literal(3L)), text)
                        .otherwise(root.get("number"));
                nativeSql = "case when i.id<3 then '" + s + "' else i.invoice_number end";
                values.put(text, s);
            }
            case "coalesce" -> {
                selected = cb.coalesce(text, root.<String>get("number"));
                nativeSql = "coalesce('" + s + "',i.invoice_number)";
                values.put(text, s);
            }
            case "function" -> {
                selected = cb.function("concat", String.class, root.get("number"), text);
                nativeSql = "concat(i.invoice_number,'" + s + "')";
                values.put(text, s);
            }
            case "correlated" -> {
                var sub = q.subquery(Long.class);
                var employee = sub.from(Employee.class);
                sub.select(cb.count(employee)).where(cb.equal(employee.get("code"), root.get("repCode")),
                        cb.gt(root.get("id"), number));
                selected = sub;
                nativeSql = "(select count(*) from employee e where e.code=i.rep_code and i.id>" + value + ")";
                values.put(number, (long) value);
            }
            case "source-body" -> {
                var body = cb.createTupleQuery();
                var invoice = body.from(Invoice.class);
                body.multiselect(invoice.get("id").alias("bodyId"), text.alias("bodyValue"))
                        .where(cb.le(invoice.get("id"), cb.literal(3L)));
                var source = source(q, root, body);
                onSource(source, cb.equal(source.get("bodyId"), root.get("id")));
                selected = source.get("bodyValue");
                nativeSql = "'" + s + "'";
                values.put(text, s);
            }
            case "group-order" -> {
                q.multiselect(number, cb.count(root)).groupBy(number).orderBy(cb.asc(number));
                values.put(number, (long) value);
                return new Built(q, values, "select " + value + ",count(*) from invoice");
            }
            default -> throw new IllegalArgumentException(shape);
        }
        long limit = shape.equals("correlated") ? 12L : 3L;
        q.multiselect(root.get("id"), selected).where(cb.le(root.get("id"), cb.literal(limit)))
                .orderBy(cb.asc(root.get("id")));
        return new Built(q, values, "select i.id," + nativeSql + " from invoice i where i.id<=" + limit
                + " order by i.id");
    }

    static From<?, ?> source(JpaCriteriaQuery<Tuple> q, JpaFrom<?, ?> root, JpaCriteriaQuery<Tuple> body)
            throws Exception {
        var method = JpaFrom.class.getMethod("join", JpaCteCriteria.class, SqmJoinType.class);
        return (From<?, ?>) method.invoke(root, q.with(body), SqmJoinType.INNER);
    }

    static void onSource(From<?, ?> joined, Predicate predicate) throws Exception {
        var method = JpaFrom.class.getMethod("join", JpaCteCriteria.class, SqmJoinType.class);
        method.getReturnType().getMethod("on", Predicate[].class)
                .invoke(joined, (Object) new Predicate[] {predicate});
    }

    private static void bind(jakarta.persistence.Query query, Map<ParameterExpression<?>, Object> values) {
        Map<String, Object> named = new LinkedHashMap<>();
        values.forEach((parameter, value) -> named.put(parameter.getName(), value));
        int calls = 0;
        for (var parameter : query.getParameters()) {
            SpikeHarness.expect(named.containsKey(parameter.getName()),
                    "unbound statement parameter " + parameter.getName());
            query.setParameter(parameter.getName(), named.get(parameter.getName()));
            calls++;
        }
        SpikeHarness.expect(calls == query.getParameters().size(), "not bound once per statement parameter");
    }

    private static void phases(SpikeHarness h, SessionFactory sessions, String mode) {
        List<String> phases = new ArrayList<>(java.util.Arrays.stream(Q.Kind.values()).map(Enum::name).toList());
        phases.add("INLINE_COUNT");
        phases.add("SOURCE_BODY");
        for (String phase : phases) {
            h.cell("C.2", mode + "/" + phase, () -> {
                List<String> sql = new ArrayList<>();
                for (long value : List.of(7L, 9L)) {
                    try (var em = sessions.createEntityManager()) {
                        var cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                        var q = cb.createTupleQuery();
                        var root = q.from(Invoice.class);
                        var where = cb.parameter(Long.class, "whereParam");
                        var select = cb.parameter(String.class, "selectOnly");
                        var order = cb.parameter(Long.class, "orderOnly");
                        var bodyParam = cb.parameter(Long.class, "bodyOnly");
                        Map<ParameterExpression<?>, Object> values = Map.of(where, value, select, "req" + value,
                                order, value, bodyParam, value + 1);
                        boolean count = phase.equals("COUNT") || phase.equals("ROOT_ID_COUNT");
                        boolean keys = phase.equals("PK_FIRST");
                        if (count) {
                            q.multiselect(phase.equals("ROOT_ID_COUNT") ? cb.countDistinct(root.get("id"))
                                    : cb.count(root));
                        } else if (keys) {
                            q.multiselect(root.get("id"));
                        } else if (phase.equals("INLINE_COUNT")) {
                            q.multiselect(root.get("id"), select, cb.count(cb.literal(1), cb.createWindow()));
                        } else {
                            q.multiselect(root.get("id"), select);
                        }
                        List<Predicate> filters = new ArrayList<>();
                        filters.add(cb.ge(root.get("id"), where));
                        filters.add(cb.le(root.get("id"), cb.literal(30L)));
                        if (phase.equals("SOURCE_BODY")) {
                            var body = cb.createTupleQuery();
                            var b = body.from(Invoice.class);
                            body.multiselect(b.get("id").alias("bodyId"))
                                    .where(cb.ge(b.get("id"), bodyParam));
                            var source = source(q, root, body);
                            onSource(source, cb.equal(source.get("bodyId"), root.get("id")));
                        }
                        String suffix = phase.equals("SOURCE_BODY") ? " and i.id>=" + (value + 1) : "";
                        if (List.of("KEYSET", "EXPORT_NEXT").contains(phase)) {
                            filters.add(cb.gt(root.get("id"), cb.literal(20L))); suffix = " and i.id>20";
                        } else if (List.of("PK_SECOND", "BY_KEYS").contains(phase)) {
                            filters.add(root.get("id").in(cb.literal(8L), cb.literal(10L), cb.literal(25L)));
                            suffix = " and i.id in (8,10,25)";
                        } else if (phase.equals("ONE")) {
                            filters.add(cb.equal(root.get("id"), cb.literal(25L))); suffix = " and i.id=25";
                        }
                        q.where(filters.toArray(Predicate[]::new));
                        if (!count) { q.orderBy(cb.asc(cb.sum(root.<Long>get("id"), order))); }
                        var query = em.createQuery(q);
                        int expected = count ? 1 : keys ? 2 : phase.equals("SOURCE_BODY") ? 4 : 3;
                        SpikeHarness.expect(query.getParameters().size() == expected,
                                phase + " rendered params " + query.getParameters().size() + " expected " + expected);
                        bind(query, values);
                        query.setHint("jakarta.persistence.query.timeout", 60_000);
                        boolean limited = !count && !phase.equals("STREAM");
                        if (limited) { query.setMaxResults(20); }
                        List<Tuple> rows;
                        if (phase.equals("STREAM")) {
                            try (var stream = query.getResultStream()) { rows = stream.toList(); }
                        } else { rows = query.getResultList(); }
                        sql.add(h.lastStatement().sql());
                        String columns = count ? "count(*)" : keys ? "i.id" : "i.id,'req" + value + "'";
                        if (phase.equals("INLINE_COUNT")) { columns += ",count(*) over()"; }
                        String nativeSql = "select " + columns + " from invoice i where i.id>=" + value
                                + " and i.id<=30" + suffix + (count ? "" : " order by i.id")
                                + (limited ? " limit 20" : "");
                        Q.equal(Q.rows(rows), Q.nativeRows(h, nativeSql));
                    }
                }
                SpikeHarness.expect(sql.get(0).equals(sql.get(1)), "SQL changed across calls for " + phase);
                return "stable SQL; bound each rendered param once; select/order params absent from counts; mode="
                        + mode;
            });
        }
    }
}
