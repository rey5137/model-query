package com.rey.modelquery.spike.rfc0021;

import static com.rey.modelquery.spike.rfc0021.PartAProbes.details;
import static com.rey.modelquery.spike.rfc0021.PartAProbes.eq;
import static com.rey.modelquery.spike.rfc0021.SpikeHarness.expect;
import static com.rey.modelquery.spike.rfc0021.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0021.Q.D;
import com.rey.modelquery.spike.rfc0021.Q.P;
import com.rey.modelquery.spike.rfc0021.Q.Sel;
import com.rey.modelquery.spike.rfc0021.Rfc0021Entities.HierarchyEdge;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

/**
 * B.1 join filters: a request-bound ON filter in the entity join's ON and in an association join's ON, alone and with
 * a static {@code on}, in one {@code on} call, the values bound once per statement, deleted profiles blanked and not
 * dropped; and an {@code exists} inside an ON per vendor (recorded only).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class PartBProbes {

    private PartBProbes() {}

    static void run(SpikeHarness h, SessionFactory sf) {
        P base = details().with(p -> p.hierarchy = 1L);
        h.cell("B.1/entity-on", "filter in the entity join's ON alone (node's profile unfiltered)", () -> {
            P p = base.with(q -> q.filterNode = false);
            List<String> got = Q.run(sf, p, Sel.DETAILS, 0, 0);
            List<String> want = Q.nativeRows(sf, Q.nativeDetails(p, false).replace(" AND c.is_deleted = false", ""));
            eq("entity ON alone", got, want);
            return "rows=" + got.size() + ", equal to native; sql: " + h.statements().get(0).sql().replaceAll("\\s+", " ");
        });
        h.cell("B.1/association-on", "filter in the association join's ON alone (manager profile unfiltered)", () -> {
            P p = base.with(q -> q.filterManager = false);
            List<String> got = Q.run(sf, p, Sel.DETAILS, 0, 0);
            List<String> want = Q.nativeRows(sf, Q.nativeDetails(p, false).replace(" AND p.is_deleted = false", ""));
            eq("association ON alone", got, want);
            return "rows=" + got.size() + ", equal to native";
        });
        h.cell("B.1/both", "filters in both ONs (the Details statement)", () -> {
            List<String> got = Q.run(sf, base, Sel.DETAILS, 0, 0);
            eq("both", got, Q.nativeRows(sf, Q.nativeDetails(base, false)));
            return "rows=" + got.size() + ", equal to native";
        });
        h.cell("B.1/static-on", "association join: static on (last_name like 'L00%') and the filter in one on call", () -> {
            P p = base.with(q -> q.staticNode = j -> q0(j, "L00%"));
            List<String> got = Q.run(sf, p, Sel.DETAILS, 0, 0);
            List<String> want = Q.nativeRows(sf, Q.nativeDetails(p, false).replace(" AND c.is_deleted = false",
                    " AND c.is_deleted = false AND c.last_name LIKE 'L00%'"));
            eq("static on + filter", got, want);
            String sql = h.statements().get(0).sql();
            return "rows=" + got.size() + ", equal to native; ON count for the node join: single predicate; sql: "
                    + sql.replaceAll("\\s+", " ");
        });
        h.cell("B.1/static-on", "entity join: static on (last_name like 'L00%') and the filter in one on call", () -> {
            P p = base.with(q -> q.staticManager = j -> q0(j, "L00%"));
            List<String> got = Q.run(sf, p, Sel.DETAILS, 0, 0);
            List<String> want = Q.nativeRows(sf, Q.nativeDetails(p, false).replace(" AND p.is_deleted = false",
                    " AND p.is_deleted = false AND p.last_name LIKE 'L00%'"));
            eq("static on + filter", got, want);
            return "rows=" + got.size() + ", equal to native";
        });
        h.cell("B.1/two-on-calls", "association join, two on(...) calls: the second replaces the first (why one call)", () -> {
            P p = base.with(q -> {
                q.staticNode = j -> q0(j, "L00%");
                q.splitOn = true;
            });
            List<String> got = Q.run(sf, p, Sel.DETAILS, 0, 0);
            List<String> oneCall = Q.nativeRows(sf, Q.nativeDetails(p, false).replace(" AND c.is_deleted = false",
                    " AND c.is_deleted = false AND c.last_name LIKE 'L00%'"));
            String sql = h.statements().get(0).sql();
            return (got.equals(oneCall) ? "the two calls were ANDed" : "the second on() replaced the first")
                    + " (rows " + got.size() + " vs one-call form " + oneCall.size() + "); last_name in the SQL: "
                    + sql.contains("last_name") + "; sql: " + sql.replaceAll("\\s+", " ");
        });
        h.cell("B.1/two-on-calls", "entity join, two on(...) calls through the reflective Method (replace or AND?)", () -> {
            long count = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, base);
                d.source();
                d.m = Q.entityJoin(d.hn, Rfc0021Entities.Profile.class, org.hibernate.query.sqm.tree.SqmJoinType.LEFT,
                        j -> cb.equal(j.get("id"), d.x.get("mq_startVertex")));
                Q.on(Q.ENTITY_JOIN, d.m, cb.notEqual(d.m.get("deleted"), true));
                d.q.multiselect(cb.count(d.hn)).where(cb.equal(d.hn.get("hierarchyId"), 1L));
                return (Long) em.createQuery(d.q).getSingleResult().get(0);
            });
            String sql = h.lastStatement().sql().replaceAll("\\s+", " ");
            boolean key = sql.contains("p1_0.id=");
            return "count=" + count + "; the second on() " + (key ? "was ANDed with the key predicate"
                    : "replaced the key predicate (a cross join with the live profiles)") + "; sql: " + sql;
        });
        h.cell("B.1/blanked", "deleted profiles blanked, not dropped; the node is kept", () -> {
            List<String> got = Q.run(sf, base, Sel.DETAILS, 0, 0);
            List<String> withoutDeleted = Q.run(sf, base.with(q -> q.incl = true), Sel.DETAILS, 0, 0);
            long nodesKept = Long.parseLong(Q.nativeRows(sf, "SELECT count(*) FROM hierarchy_node WHERE hierarchy_id = 1 "
                    + "AND is_deleted = false").get(0));
            expect(got.size() == nodesKept, "rows=" + got.size() + ", live nodes " + nodesKept);
            long blankedManager = got.stream().map(r -> r.split("\\|", -1))
                    .filter(a -> !a[5].equals("~") && a[6].equals("~")).count();
            long blankedNode = got.stream().map(r -> r.split("\\|", -1)).filter(a -> a[3].equals("~")).count();
            expect(blankedManager > 0 && blankedNode > 0, "nothing blanked: " + blankedManager + "/" + blankedNode);
            return "rows=" + got.size() + " = live nodes; manager name blanked on " + blankedManager
                    + " rows (manager id kept), node name blanked on " + blankedNode + " rows; include_deleted=1 rows="
                    + withoutDeleted.size();
        });
        h.cell("B.1/bind-once", "values bound once per statement: content page", () -> {
            Q.run(sf, base, Sel.DETAILS, 0, 20);
            return binds(h, 4);
        });
        h.cell("B.1/bind-once", "values bound once per statement: count (the entity join's ON only, the node join absent)",
                () -> {
                    Q.run(sf, base, Sel.COUNT, 0, 0);
                    return binds(h, 3);
                });
        h.cell("B.1/bind-once", "values bound once per statement: count over groups (createCountQuery)", () -> {
            inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, base);
                d.source();
                d.entity();
                d.q.multiselect(d.hn.get("hierarchyId"), d.m.get("id")).groupBy(d.hn.get("hierarchyId"), d.m.get("id"));
                return em.createQuery(d.q.createCountQuery()).getSingleResult();
            });
            return binds(h, 2);
        });
        h.cell("B.1/bind-once", "values bound once per statement: count over groups, entity join off a plain column "
                + "(no CTE, so createCountQuery runs)", () -> {
            inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, base);
                var m = Q.entityJoin(d.hn, Rfc0021Entities.Profile.class, org.hibernate.query.sqm.tree.SqmJoinType.LEFT,
                        j -> cb.and(cb.equal(j.get("id"), d.hn.get("nodeId")), cb.notEqual(j.get("deleted"), true)));
                d.q.multiselect(d.hn.get("hierarchyId"), m.get("id")).groupBy(d.hn.get("hierarchyId"), m.get("id"));
                return em.createQuery(d.q.createCountQuery()).getSingleResult();
            });
            return binds(h, 1);
        });
        h.cell("B.1/bind-once", "values bound once per statement: ids step (limit 20) and rows step", () -> {
            List<String> ids = Q.run(sf, base, Sel.ID, 0, 20);
            String first = binds(h, 3);
            List<Long> keys = ids.stream().map(Long::valueOf).toList();
            Q.run(sf, base.with(q -> q.extra = d -> d.hn.get("id").in(keys)), Sel.DETAILS, 0, 0);
            return "ids step: " + first + "; rows step: " + binds(h, 4);
        });
        existsInOn(h, sf);
    }

    private static Predicate q0(jakarta.persistence.criteria.From j, String pattern) {
        return Q.CB.like(j.get("lastName"), pattern);
    }

    /** The statement's {@code is_deleted} count (one per place it is written) against its binds. */
    private static String binds(SpikeHarness h, int expectedDeleted) {
        String sql = h.lastStatement().sql();
        int deleted = Q.occurrences(sql, "is_deleted");
        int marks = Q.occurrences(sql, "?");
        int binds = h.lastStatement().ops().size();
        expect(deleted == expectedDeleted, "is_deleted written " + deleted + " times, expected " + expectedDeleted
                + ": " + sql);
        expect(marks == binds, "placeholders " + marks + " vs binds " + binds);
        return "is_deleted written " + deleted + " times (edge CTE, each ON, root), placeholders=" + marks + ", binds="
                + h.lastStatement().binds();
    }

    // ------------------------------------------------------------------------------------------------ exists

    private static void existsInOn(SpikeHarness h, SessionFactory sf) {
        h.cell("B.1/exists-in-on", "exists in the entity join's ON, correlated to the entity join (record only)", () -> {
            List<String> got = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, details().with(p -> {
                    p.hierarchy = 1L;
                    p.attachEntity = false;
                }));
                d.source();
                jakarta.persistence.criteria.CriteriaQuery<?> q = d.q;
                Subquery<Integer> sub = q.subquery(Integer.class);
                var t = sub.from(Rfc0021Entities.ProfileType.class);
                d.m = Q.entityJoin(d.hn, Rfc0021Entities.Profile.class, org.hibernate.query.sqm.tree.SqmJoinType.LEFT,
                        j -> cb.and(cb.equal(j.get("id"), d.x.get("mq_startVertex")),
                                cb.exists(sub.select(cb.literal(1)).where(cb.equal(t.get("id"), j.get("type").get("id")),
                                        cb.notEqual(t.get("name"), "Type 3")))));
                d.q.multiselect(d.hn.get("id"), d.m.get("firstName")).where(cb.equal(d.hn.get("hierarchyId"), 1L))
                        .orderBy(cb.asc(d.hn.get("id")));
                return Q.rows(em.createQuery(d.q).getResultList());
            });
            List<String> want = Q.nativeRows(sf, "SELECT hn.id, p.first_name FROM hierarchy_node hn LEFT JOIN (SELECT "
                    + "e.hierarchy_id, e.end_vertex, max(e.start_vertex) sv FROM hierarchy_edge e WHERE e.hops = 2 "
                    + "AND e.hierarchy_id = 1 AND e.is_deleted = false GROUP BY e.hierarchy_id, e.end_vertex) x ON "
                    + "x.hierarchy_id = hn.hierarchy_id AND x.end_vertex = hn.node_id LEFT JOIN profile p ON "
                    + "p.id = x.sv AND EXISTS (SELECT 1 FROM profile_type t WHERE t.id = p.type_id AND t.name <> "
                    + "'Type 3') WHERE hn.hierarchy_id = 1 ORDER BY hn.id");
            eq("exists in ON", got, want);
            return "rows=" + got.size() + ", equal to native; sql: " + h.statements().get(0).sql().replaceAll("\\s+", " ");
        });
        h.cell("B.1/exists-in-on", "exists in an association join's ON, correlated to the root (record only)", () -> {
            List<String> got = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, details());
                Join c = d.hn.join("node", JoinType.LEFT);
                Subquery<Integer> sub = d.q.subquery(Integer.class);
                var e = sub.from(HierarchyEdge.class);
                c.on(cb.exists(sub.select(cb.literal(1)).where(cb.equal(e.get("endVertex"), d.hn.get("nodeId")),
                        cb.equal(e.get("hops"), 1))));
                d.q.multiselect(d.hn.get("id"), c.get("firstName")).where(cb.equal(d.hn.get("hierarchyId"), 1L))
                        .orderBy(cb.asc(d.hn.get("id")));
                return Q.rows(em.createQuery(d.q).getResultList());
            });
            long want = Long.parseLong(Q.nativeRows(sf, "SELECT count(*) FROM hierarchy_node WHERE hierarchy_id = 1")
                    .get(0));
            expect(got.size() == want, "rows=" + got.size() + " want " + want);
            return "rows=" + got.size() + "; named profiles=" + got.stream().filter(r -> !r.endsWith("|~")).count()
                    + "; sql: " + h.statements().get(0).sql().replaceAll("\\s+", " ");
        });
        h.cell("B.1/exists-correlated-join", "ON of a join made off a correlated root inside exists (R: Hibernate drops it?)",
                () -> {
                    List<String> out = inEm(sf, em -> {
                        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                        D d = new D(cb, details());
                        Subquery<Integer> sub = d.q.subquery(Integer.class);
                        var corr = sub.correlate(d.hn);
                        Join cj = corr.join("node", JoinType.INNER);
                        cj.on(cb.notEqual(cj.get("deleted"), true));
                        sub.select(cb.literal(1)).where(cb.equal(cj.get("firstName"), "P0014"));
                        d.q.multiselect(d.hn.get("id")).where(cb.exists(sub), cb.equal(d.hn.get("hierarchyId"), 1L));
                        return Q.rows(em.createQuery(d.q).getResultList());
                    });
                    String sql = h.lastStatement().sql();
                    return "rows=" + out.size() + "; is_deleted in the sub-query: " + sql.contains("is_deleted")
                            + "; sql: " + sql.replaceAll("\\s+", " ");
                });
    }
}
