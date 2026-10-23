package com.rey.modelquery.spike.rfc0021;

import static com.rey.modelquery.spike.rfc0021.SpikeHarness.expect;
import static com.rey.modelquery.spike.rfc0021.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0021.Q.D;
import com.rey.modelquery.spike.rfc0021.Q.P;
import com.rey.modelquery.spike.rfc0021.Q.Sel;
import com.rey.modelquery.spike.rfc0021.Rfc0021Entities.MemberEmb;
import com.rey.modelquery.spike.rfc0021.Rfc0021Entities.MemberIc;
import com.rey.modelquery.spike.rfc0021.Rfc0021Entities.Profile;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.metamodel.model.domain.EntityDomainType;
import org.hibernate.query.NullPrecedence;
import org.hibernate.query.SortDirection;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * A.1 (signatures), A.2 (rendering, statement kinds), A.3 (paths and keys) and the {@code count} cells of RFC 0021
 * Part A. The statement kinds are emulated: each is the Details statement with the extra predicate, order and limit the
 * library's statement of that kind has (the library cannot build an entity join yet), so what is checked is that the
 * join survives in the SQL of each shape and the rows equal the native SQL's.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class PartAProbes {

    private PartAProbes() {}

    private static final Map<String, List<String>> NATIVE = new LinkedHashMap<>();

    static List<String> nativeFull(SessionFactory sf, String key, P p) {
        return NATIVE.computeIfAbsent(key, k -> Q.nativeRows(sf, Q.nativeDetails(p, false)));
    }

    static void eq(String what, List<String> got, List<String> want) {
        if (got.equals(want)) {
            return;
        }
        int i = 0;
        while (i < got.size() && i < want.size() && got.get(i).equals(want.get(i))) {
            i++;
        }
        throw new SpikeHarness.Mismatch(what + ": got " + got.size() + " rows, want " + want.size()
                + "; first difference at " + i + ": got " + (i < got.size() ? got.get(i) : "-") + " want "
                + (i < want.size() ? want.get(i) : "-"));
    }

    static P details() {
        P p = new P();
        p.joinFilters = true;
        return p;
    }

    static void run(SpikeHarness h, SessionFactory sf) {
        signatures(h, sf);
        idClassAndEmbedded(h, sf);
        rendering(h, sf);
        statementKinds(h, sf);
        paths(h, sf);
        counts(h, sf);
    }

    // ------------------------------------------------------------------------------------------------ A.1

    private static void signatures(SpikeHarness h, SessionFactory sf) {
        Method join = Q.ENTITY_JOIN;
        h.cell("A.1/signatures", "JpaFrom#join(Class, SqmJoinType)", () -> {
            Class<?> ret = join.getReturnType();
            Method on = Q.method(ret, "on", Predicate[].class);
            boolean declaredOn = false;
            try {
                ret.getDeclaredMethod("on", Predicate[].class);
                declaredOn = true;
            } catch (NoSuchMethodException ignored) {
                // inherited
            }
            boolean jakartaJoin = Join.class.isAssignableFrom(ret);
            return "declaringClass=" + join.getDeclaringClass().getSimpleName() + "; abstract="
                    + Modifier.isAbstract(join.getModifiers()) + "; default=" + join.isDefault() + "; return="
                    + join.getGenericReturnType().getTypeName() + "; JpaEntityJoin interfaces="
                    + Arrays.stream(ret.getGenericInterfaces()).map(java.lang.reflect.Type::getTypeName)
                            .collect(Collectors.joining(", "))
                    + "; is a jakarta Join=" + jakartaJoin + "; on(Predicate...) declared on JpaEntityJoin="
                    + declaredOn + ", resolved on " + on.getDeclaringClass().getSimpleName() + " returning "
                    + on.getReturnType().getSimpleName();
        });
        h.cell("A.1/signatures", "runtime entity join: instanceof jakarta Join, Join#getParent(), JpaFrom#getAttribute", () -> {
            return inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                JpaRoot<Rfc0021Entities.HierarchyNode> hn = q.from(Rfc0021Entities.HierarchyNode.class);
                JpaFrom m = Q.entityJoin(hn, Profile.class, SqmJoinType.LEFT,
                        j -> cb.equal(j.get("id"), hn.get("nodeId")));
                String parent;
                if (m instanceof Join<?, ?> asJoin) {
                    parent = "getParent()==root: " + (asJoin.getParent() == hn);
                } else {
                    parent = "not a Join, so a Join#getParent() walk stops here";
                }
                JpaFrom to = (JpaFrom) m.join("type", JoinType.LEFT);
                return "runtime class=" + m.getClass().getSimpleName() + "; instanceof jakarta Join="
                        + (m instanceof Join<?, ?>) + "; " + parent + "; to-one join below it is a Join="
                        + (to instanceof Join<?, ?>) + " with getParent()==entity join: "
                        + (((Join<?, ?>) to).getParent() == m);
            });
        });
        h.cell("A.1/signatures", "other overloads", () -> {
            List<String> out = new ArrayList<>();
            for (Method m : JpaFrom.class.getMethods()) {
                if (m.getName().equals("join") && m.getParameterCount() >= 1
                        && (m.getParameterTypes()[0] == Class.class || m.getParameterTypes()[0] == EntityDomainType.class)
                        && !m.isBridge() && !m.isSynthetic()) {
                    out.add(Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
                            .collect(Collectors.joining(",", "join(", ")")) + (m.isDefault() ? " default" : "")
                            + " -> " + m.getReturnType().getSimpleName());
                }
            }
            boolean jakarta32 = Q.method(From.class, "join", Class.class, JoinType.class) != null;
            java.util.Collections.sort(out);
            return String.join("; ", out) + "; jakarta From#join(Class, JoinType) on the classpath=" + jakarta32;
        });
    }

    private static void idClassAndEmbedded(SpikeHarness h, SessionFactory sf) {
        h.cell("A.1/idclass", "join(Class, SqmJoinType) of an @IdClass entity: LEFT on both id attributes", () -> {
            List<String> got = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, details().with(p -> p.hierarchy = 1L));
                d.source();
                JpaFrom ic = Q.entityJoin(d.hn, MemberIc.class, SqmJoinType.LEFT, j -> cb.and(
                        cb.equal(j.get("hierarchyId"), d.x.get("mq_hierarchyId")),
                        cb.equal(j.get("profileId"), d.x.get("mq_startVertex"))));
                d.q.multiselect(d.hn.get("id"), ic.get("role"), cb.count(ic.get("hierarchyId")))
                        .where(cb.equal(d.hn.get("hierarchyId"), 1L)).groupBy(d.hn.get("id"), ic.get("role"))
                        .orderBy(cb.asc(d.hn.get("id")));
                return Q.rows(em.createQuery(d.q).getResultList());
            });
            List<String> want = Q.nativeRows(sf, "SELECT hn.id, ic.role, count(ic.hierarchy_id) FROM hierarchy_node hn "
                    + "LEFT JOIN (SELECT e.hierarchy_id, e.end_vertex, max(e.start_vertex) sv FROM hierarchy_edge e "
                    + "WHERE e.hops = 2 AND e.hierarchy_id = 1 AND e.is_deleted = false GROUP BY e.hierarchy_id, "
                    + "e.end_vertex) x ON x.hierarchy_id = hn.hierarchy_id AND x.end_vertex = hn.node_id "
                    + "LEFT JOIN member_ic ic ON ic.hierarchy_id = x.hierarchy_id AND ic.profile_id = x.sv "
                    + "WHERE hn.hierarchy_id = 1 GROUP BY hn.id, ic.role ORDER BY hn.id");
            eq("IdClass", got, want);
            return "rows=" + got.size() + ", matches native; count(ic.hierarchyId) per row read";
        });
        h.cell("A.1/idclass", "join(EntityDomainType, SqmJoinType) of the same entity (only if the Class form fails)",
                () -> {
                    Method m = Q.method(JpaFrom.class, "join", EntityDomainType.class, SqmJoinType.class);
                    expect(m != null, "no join(EntityDomainType, SqmJoinType)");
                    return inEm(sf, em -> {
                        EntityDomainType<MemberIc> type = (EntityDomainType<MemberIc>) em.getMetamodel()
                                .entity(MemberIc.class);
                        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                        JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                        JpaRoot<Rfc0021Entities.HierarchyNode> hn = q.from(Rfc0021Entities.HierarchyNode.class);
                        JpaFrom ic = (JpaFrom) Q.invoke(m, hn, type, SqmJoinType.LEFT);
                        Q.on(m, ic, cb.equal(ic.get("hierarchyId"), hn.get("hierarchyId")));
                        q.multiselect(cb.count(ic.get("profileId")));
                        return "works, count=" + em.createQuery(q).getSingleResult().get(0);
                    });
                });
        h.cell("A.1/embedded", "join(Class, SqmJoinType) of an @EmbeddedId entity: LEFT on key.hierarchyId, key.profileId",
                () -> {
                    List<String> got = inEm(sf, em -> {
                        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                        D d = new D(cb, details().with(p -> p.hierarchy = 1L));
                        d.source();
                        JpaFrom me = Q.entityJoin(d.hn, MemberEmb.class, SqmJoinType.LEFT, j -> cb.and(
                                cb.equal(j.get("key").get("hierarchyId"), d.x.get("mq_hierarchyId")),
                                cb.equal(j.get("key").get("profileId"), d.x.get("mq_startVertex"))));
                        d.q.multiselect(d.hn.get("id"), me.get("role"), cb.count(me.get("key").get("hierarchyId")))
                                .where(cb.equal(d.hn.get("hierarchyId"), 1L)).groupBy(d.hn.get("id"), me.get("role"))
                                .orderBy(cb.asc(d.hn.get("id")));
                        return Q.rows(em.createQuery(d.q).getResultList());
                    });
                    List<String> want = Q.nativeRows(sf, "SELECT hn.id, ic.role, count(ic.hierarchy_id) FROM "
                            + "hierarchy_node hn LEFT JOIN (SELECT e.hierarchy_id, e.end_vertex, max(e.start_vertex) sv "
                            + "FROM hierarchy_edge e WHERE e.hops = 2 AND e.hierarchy_id = 1 AND e.is_deleted = false "
                            + "GROUP BY e.hierarchy_id, e.end_vertex) x ON x.hierarchy_id = hn.hierarchy_id AND "
                            + "x.end_vertex = hn.node_id LEFT JOIN member_emb ic ON ic.hierarchy_id = x.hierarchy_id "
                            + "AND ic.profile_id = x.sv WHERE hn.hierarchy_id = 1 GROUP BY hn.id, ic.role ORDER BY hn.id");
                    eq("EmbeddedId", got, want);
                    return "rows=" + got.size() + ", matches native";
                });
    }

    // ------------------------------------------------------------------------------------------------ A.2

    private static void rendering(SpikeHarness h, SessionFactory sf) {
        P all = new P();
        all.incl = true;
        long matched = Long.parseLong(Q.nativeRows(sf, "SELECT count(*) FROM hierarchy_node hn JOIN hierarchy_edge e ON "
                + "e.hierarchy_id = hn.hierarchy_id AND e.end_vertex = hn.node_id AND e.hops = 2").get(0));
        long nodes = Rfc0021Dataset.HIERARCHIES * (long) Rfc0021Dataset.NODES;
        for (SqmJoinType source : List.of(SqmJoinType.LEFT, SqmJoinType.INNER)) {
            for (SqmJoinType entity : List.of(SqmJoinType.LEFT, SqmJoinType.INNER)) {
                P p = all.with(q -> {
                    q.source = source;
                    q.entity = entity;
                });
                h.cell("A.2/kinds", "root anchor: source " + source + ", entity join " + entity + ": count", () -> {
                    long count = Long.parseLong(Q.run(sf, p, Sel.COUNT, 0, 0).get(0));
                    long want = source == SqmJoinType.LEFT && entity == SqmJoinType.LEFT ? nodes : matched;
                    expect(count == want, "count=" + count + ", want " + want + " (all nodes " + nodes
                            + ", with an edge " + matched + ")");
                    return "count=" + count + (count == nodes ? " (unmatched source rows kept)"
                            : " (unmatched source rows dropped)");
                });
            }
        }
        for (SqmJoinType entity : List.of(SqmJoinType.LEFT, SqmJoinType.INNER)) {
            P p = all.with(q -> {
                q.entity = entity;
                q.anchorCte = true;
            });
            h.cell("A.2/anchor-cte", "comparison only: entity join " + entity + " under a LEFT source's CTE join: count",
                    () -> {
                        long count = Long.parseLong(Q.run(sf, p, Sel.COUNT, 0, 0).get(0));
                        return "count=" + count + " (root anchor " + (entity == SqmJoinType.LEFT ? nodes : matched)
                                + "); " + (count == nodes ? "unmatched source rows kept" : "unmatched source rows dropped");
                    });
        }
        h.cell("A.2/order", "creation order = rendering order: source, entity, node (library order)", () -> {
            Q.run(sf, details().with(p -> p.hierarchy = 1L), Sel.DETAILS, 0, 20);
            return "join order: " + Q.joinOrder(h.lastStatement().sql());
        });
        h.cell("A.2/order", "creation order = rendering order: node join created first", () -> {
            Q.run(sf, details().with(p -> {
                p.hierarchy = 1L;
                p.nodeFirst = true;
            }), Sel.DETAILS, 0, 20);
            return "join order: " + Q.joinOrder(h.lastStatement().sql());
        });
        h.cell("A.2/order", "creation order: entity join created before the source join is impossible (its ON reads "
                + "the source); an unrelated join created between them renders between them", () -> inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    D d = new D(cb, details());
                    d.p.skipSource = true;
                    d.source();
                    Join mid = d.hn.join("node", JoinType.LEFT);
                    d.entity();
                    d.q.multiselect(d.hn.get("id"), mid.get("firstName"), d.m.get("firstName"))
                            .orderBy(cb.asc(d.hn.get("id")));
                    em.createQuery(d.q).setMaxResults(5).getResultList();
                    return "join order: " + Q.joinOrder(h.lastStatement().sql());
                }));
    }

    // ------------------------------------------------------------------------------------------------ A.2 kinds

    private static void statementKinds(SpikeHarness h, SessionFactory sf) {
        P base = details();
        List<String> full = nativeFull(sf, "details-all", base);
        List<String> ids = full.stream().map(r -> r.substring(0, r.indexOf('|'))).toList();
        kind(h, "content page (offset 20, limit 20)", sf, base, Sel.DETAILS, 20, 20, full.subList(20, 40), 2);
        h.cell("A.2/kinds", "count", () -> {
            long count = Long.parseLong(Q.run(sf, base, Sel.COUNT, 0, 0).get(0));
            expect(count == full.size(), "count=" + count + ", native " + full.size());
            expect(Q.occurrences(h.lastStatement().sql(), "join profile ") == 1, "entity join missing: "
                    + h.lastStatement().sql());
            return "count=" + count;
        });
        h.cell("A.2/kinds", "count over groups through ProviderSupport#countQuery (JpaCriteriaQuery#createCountQuery)",
                () -> {
                    long count = inEm(sf, em -> {
                        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                        D d = new D(cb, base.with(p -> p.incl = true));
                        d.source();
                        d.entity();
                        d.q.multiselect(d.hn.get("hierarchyId"), d.m.get("id")).groupBy(d.hn.get("hierarchyId"),
                                d.m.get("id"));
                        JpaCriteriaQuery<Long> counted = d.q.createCountQuery();
                        return em.createQuery(counted).getSingleResult();
                    });
                    String sql = h.lastStatement().sql();
                    expect(Q.occurrences(sql, "join profile ") == 1, "entity join missing: " + sql);
                    long want = nativeFull(sf, "details-incl", base.with(p -> p.incl = true)).stream()
                            .map(r -> r.split("\\|")).map(a -> a[1] + "|" + a[5]).distinct().count();
                    expect(count == want, "count=" + count + ", native groups " + want);
                    return "groups=" + count + "; sql: " + sql.replaceAll("\\s+", " ");
                });
        h.cell("A.2/kinds", "control: createCountQuery over the grouped CTE query without the entity join", () -> {
            long count = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, base.with(p -> p.incl = true));
                d.source();
                d.q.multiselect(d.hn.get("hierarchyId"), d.x.get("mq_startVertex")).groupBy(d.hn.get("hierarchyId"),
                        d.x.get("mq_startVertex"));
                return em.createQuery(d.q.createCountQuery()).getSingleResult();
            });
            return "groups=" + count + "; sql: " + h.lastStatement().sql().replaceAll("\\s+", " ");
        });
        h.cell("A.2/kinds", "count over groups through createCountQuery, entity join off a plain root column (no CTE), "
                + "to see the join survive the copy", () -> {
            long count = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, base);
                JpaFrom m = Q.entityJoin(d.hn, Profile.class, SqmJoinType.LEFT,
                        j -> cb.equal(j.get("id"), d.hn.get("nodeId")));
                d.q.multiselect(d.hn.get("hierarchyId"), m.get("id")).groupBy(d.hn.get("hierarchyId"), m.get("id"));
                return em.createQuery(d.q.createCountQuery()).getSingleResult();
            });
            String sql = h.lastStatement().sql();
            expect(Q.occurrences(sql, "join profile ") == 1, "entity join missing after the copy: " + sql);
            long want = Long.parseLong(Q.nativeRows(sf, "SELECT count(*) FROM (SELECT hn.hierarchy_id, p.id FROM "
                    + "hierarchy_node hn LEFT JOIN profile p ON p.id = hn.node_id GROUP BY hn.hierarchy_id, p.id) t")
                    .get(0));
            expect(count == want, "groups=" + count + ", native " + want);
            return "groups=" + count + "; sql: " + sql.replaceAll("\\s+", " ");
        });
        h.cell("A.2/kinds", "root-id count of a treated path (count(distinct hn.id))", () -> {
            long count = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, base);
                d.source();
                d.entity();
                d.q.multiselect(cb.countDistinct(d.hn.get("id")));
                return (Long) em.createQuery(d.q).getSingleResult().get(0);
            });
            String sql = h.lastStatement().sql();
            long nodes = Rfc0021Dataset.HIERARCHIES * (long) Rfc0021Dataset.NODES;
            expect(count == nodes, "count=" + count + ", nodes " + nodes + " (no deleted check in this shape)");
            expect(Q.occurrences(sql, "join profile ") == 1, "entity join missing: " + sql);
            return "count=" + count + "; sql: " + sql.replaceAll("\\s+", " ");
        });
        List<String> pageIds = ids.subList(20, 40);
        h.cell("A.2/kinds", "primary-key-first step 1 (ids, offset 20, limit 20)", () -> {
            List<String> got = Q.run(sf, base, Sel.ID, 20, 20);
            eq("step 1", got, pageIds);
            expect(Q.occurrences(h.lastStatement().sql(), "join profile ") == 1, "entity join missing");
            return "ids=" + got.size();
        });
        List<Long> idList = pageIds.stream().map(Long::valueOf).toList();
        kind(h, "primary-key-first step 2 (rows where id in ids)", sf,
                base.with(p -> p.extra = d -> d.hn.get("id").in(idList)), Sel.DETAILS, 0, 0, full.subList(20, 40), 2);
        kind(h, "keyset page (id > 300, limit 100)", sf, base.with(p -> p.extra = d -> d.cb.gt(d.hn.get("id"), 300L)),
                Sel.DETAILS, 0, 100, full.stream().filter(r -> Long.parseLong(r.substring(0, r.indexOf('|'))) > 300)
                        .limit(100).toList(), 2);
        kind(h, "export page (id > 5000, limit 500)", sf, base.with(p -> p.extra = d -> d.cb.gt(d.hn.get("id"), 5000L)),
                Sel.DETAILS, 0, 500, full.stream().filter(r -> Long.parseLong(r.substring(0, r.indexOf('|'))) > 5000)
                        .limit(500).toList(), 2);
        h.cell("A.2/kinds", "export stream (getResultStream, fetch size 100, hierarchy 1)", () -> {
            P p = base.with(q -> q.hierarchy = 1L);
            List<String> got = Q.stream(sf, p, Sel.DETAILS);
            String sql = h.lastStatement().sql();
            expect(Q.occurrences(sql, "join profile ") == 2, "entity join missing: " + sql);
            eq("stream", got, nativeFull(sf, "details-h1", p));
            return "rows=" + got.size();
        });
        long k0 = Long.parseLong(ids.get(0));
        long k1 = Long.parseLong(ids.get(100));
        long k2 = Long.parseLong(ids.get(ids.size() - 1));
        List<Long> keys = List.of(k0, k1, k2, 99999L);
        kind(h, "byKeys chunk (three live ids and one absent)", sf, base.with(p -> p.extra = d -> d.hn.get("id").in(keys)),
                Sel.DETAILS, 0, 0, full.stream().filter(r -> keys.contains(Long.valueOf(r.substring(0, r.indexOf('|')))))
                        .toList(), 2);
        kind(h, "one(q, key) (a live id)", sf, base.with(p -> p.extra = d -> d.cb.equal(d.hn.get("id"), k1)),
                Sel.DETAILS, 0, 0, full.stream().filter(r -> r.startsWith(k1 + "|")).toList(), 2);
        List<Long> parents = List.of(14L, 15L, 16L, 17L, 5014L, 5015L);
        kind(h, "through child load (children keyed by parent node ids; emulated: node_id in (...))", sf,
                base.with(p -> p.extra = d -> d.hn.get("nodeId").in(parents)), Sel.DETAILS, 0, 0,
                full.stream().filter(r -> parents.contains(Long.valueOf(r.split("\\|")[2]))).toList(), 2);
        kind(h, "fetch-plan parent read (id in parent ids; emulated)", sf,
                base.with(p -> p.extra = d -> d.hn.get("id").in(List.of(7L, 8L, 5009L))), Sel.DETAILS, 0, 0,
                full.stream().filter(r -> List.of("7", "8", "5009").contains(r.substring(0, r.indexOf('|')))).toList(), 2);
    }

    private static void kind(SpikeHarness h, String name, SessionFactory sf, P p, Sel sel, int first, int max,
            List<String> want, int profileJoins) {
        h.cell("A.2/kinds", name, () -> {
            List<String> got = Q.run(sf, p, sel, first, max);
            eq(name, got, want);
            int joins = Q.occurrences(h.lastStatement().sql(), "join profile ");
            expect(joins == profileJoins, "profile joins=" + joins + ": " + h.lastStatement().sql());
            return "rows=" + got.size() + ", equal to native; join order: " + Q.joinOrder(h.lastStatement().sql());
        });
    }

    // ------------------------------------------------------------------------------------------------ A.3

    private static void paths(SpikeHarness h, SessionFactory sf) {
        h.cell("A.3/to-one", "type.name below the entity join, beside the node's own type (distinct joins)", () -> {
            List<String> got = Q.run(sf, details().with(p -> p.hierarchy = 2L), Sel.DETAILS, 0, 0);
            String sql = h.lastStatement().sql();
            expect(Q.occurrences(sql, "join profile_type ") == 2, "profile_type joins: " + sql);
            eq("details", got, nativeFull(sf, "details-h2", details().with(p -> p.hierarchy = 2L)));
            return "rows=" + got.size() + ", equal to native; " + Q.joinOrder(sql);
        });
        h.cell("A.3/two-joins", "two entity joins of Profile (manager off the source, node off the root)", () -> {
            List<String> got = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, details().with(p -> {
                    p.hierarchy = 1L;
                    p.joinFilters = false;
                }));
                d.source();
                d.entity();
                JpaFrom node = Q.entityJoin(d.hn, Profile.class, SqmJoinType.LEFT,
                        j -> cb.equal(j.get("id"), d.hn.get("nodeId")));
                d.q.multiselect(d.hn.get("id"), node.get("firstName"), d.m.get("firstName"))
                        .where(cb.equal(d.hn.get("hierarchyId"), 1L)).orderBy(cb.asc(d.hn.get("id")));
                return Q.rows(em.createQuery(d.q).getResultList());
            });
            List<String> want = Q.nativeRows(sf, "SELECT hn.id, n.first_name, p.first_name FROM hierarchy_node hn "
                    + "LEFT JOIN (SELECT e.hierarchy_id, e.end_vertex, max(e.start_vertex) sv FROM hierarchy_edge e "
                    + "WHERE e.hops = 2 AND e.hierarchy_id = 1 AND e.is_deleted = false GROUP BY e.hierarchy_id, "
                    + "e.end_vertex) x ON x.hierarchy_id = hn.hierarchy_id AND x.end_vertex = hn.node_id "
                    + "LEFT JOIN profile p ON p.id = x.sv LEFT JOIN profile n ON n.id = hn.node_id "
                    + "WHERE hn.hierarchy_id = 1 ORDER BY hn.id");
            eq("two joins", got, want);
            return "rows=" + got.size() + ", equal to native; " + Q.joinOrder(h.statements().get(0).sql());
        });
        h.cell("A.3/filter", "filter on a joined-entity column (where m.first_name = 'P0002')", () -> {
            P p = details().with(q -> {
                q.manager = null;
                q.extra = d -> d.cb.equal(d.m.get("firstName"), "P0002");
            });
            List<String> got = Q.run(sf, p, Sel.DETAILS, 0, 0);
            List<String> want = nativeFull(sf, "details-all", details()).stream()
                    .filter(r -> r.split("\\|", -1)[6].equals("P0002")).toList();
            eq("filter", got, want);
            return "rows=" + got.size();
        });
        h.cell("A.3/order", "order by the nullable m.first_name, nulls last, then id", () -> {
            P p = details().with(q -> q.hierarchy = 1L);
            List<String> got = ordered(sf, p, null);
            List<String> want = nativeOrdered(sf);
            eq("order", got, want.subList(0, Math.min(1000, want.size())));
            long nulls = want.stream().filter(r -> r.split("\\|", -1)[6].equals("~")).count();
            return "first 1000 rows equal to native order; " + nulls + " of " + want.size()
                    + " rows have a NULL manager name and sort last";
        });
        h.cell("A.3/keyset", "keyset walk (pages of 1000) over (m.first_name nulls last, hn.id), whole hierarchy 1", () -> {
            P p = details().with(q -> q.hierarchy = 1L);
            List<String> walked = new ArrayList<>();
            List<String> last = null;
            int pages = 0;
            while (true) {
                List<String> page = ordered(sf, p, last);
                if (page.isEmpty()) {
                    break;
                }
                walked.addAll(page);
                last = Arrays.asList(page.get(page.size() - 1).split("\\|", -1));
                pages++;
                expect(pages < 20, "walk does not end");
            }
            List<String> want = nativeOrdered(sf);
            eq("keyset walk", walked, want);
            return "pages=" + pages + ", rows=" + walked.size() + ", no row lost or repeated";
        });
        h.cell("A.3/group", "grouped outer model grouped by the joined column m.first_name (count of nodes)", () -> {
            List<String> got = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, details().with(p -> p.incl = true));
                d.source();
                d.entity();
                d.q.multiselect(d.m.get("firstName"), cb.count(d.hn)).groupBy(d.m.get("firstName"))
                        .orderBy(cb.asc(d.m.get("firstName")));
                return Q.rows(em.createQuery(d.q).getResultList());
            });
            List<String> want = Q.nativeRows(sf, "SELECT p.first_name, count(*) FROM hierarchy_node hn LEFT JOIN "
                    + "(SELECT e.hierarchy_id, e.end_vertex, max(e.start_vertex) sv FROM hierarchy_edge e WHERE "
                    + "e.hops = 2 GROUP BY e.hierarchy_id, e.end_vertex) x ON x.hierarchy_id = hn.hierarchy_id AND "
                    + "x.end_vertex = hn.node_id LEFT JOIN profile p ON p.id = x.sv GROUP BY p.first_name "
                    + "ORDER BY p.first_name");
            // The groups' order puts the NULL group where the vendor puts it; compare as sets.
            expect(new java.util.HashSet<>(got).equals(new java.util.HashSet<>(want)) && got.size() == want.size(),
                    "groups differ: " + got.size() + " vs " + want.size());
            return "groups=" + got.size() + ", equal to native (as a set)";
        });
    }

    private static List<String> ordered(SessionFactory sf, P p, List<String> after) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            D d = new D(cb, p);
            d.build(Sel.DETAILS);
            List<Predicate> w = new ArrayList<>(d.where);
            if (after != null) {
                String name = after.get(6);
                long id = Long.parseLong(after.get(0));
                Expression<String> fn = d.m.get("firstName");
                if (name.equals("~")) {
                    w.add(cb.and(cb.isNull(fn), cb.gt(d.hn.get("id"), id)));
                } else {
                    w.add(cb.or(cb.greaterThan(fn, name), cb.isNull(fn),
                            cb.and(cb.equal(fn, name), cb.gt(d.hn.get("id"), id))));
                }
            }
            d.q.where(w.toArray(new Predicate[0]));
            d.q.orderBy(cb.sort((JpaExpression<?>) d.m.get("firstName"), SortDirection.ASCENDING, NullPrecedence.LAST),
                    cb.asc(d.hn.get("id")));
            return Q.rows(em.createQuery(d.q).setMaxResults(1000).getResultList());
        });
    }

    private static List<String> nativeOrdered(SessionFactory sf) {
        return Q.nativeRows(sf, Q.nativeDetails(details().with(p -> p.hierarchy = 1L), false)
                .replace("ORDER BY r.id", "ORDER BY CASE WHEN p.first_name IS NULL THEN 1 ELSE 0 END, p.first_name, r.id"));
    }

    // ------------------------------------------------------------------------------------------------ count

    private static void counts(SpikeHarness h, SessionFactory sf) {
        long nodes = Rfc0021Dataset.HIERARCHIES * (long) Rfc0021Dataset.NODES;
        h.cell("A.4/count", "count(m) over the entity join: SQL form, unmatched LEFT counts 0", () -> counted(sf, "m"));
        h.cell("A.4/count", "count(m.id): the first id attribute", () -> counted(sf, "m.id"));
        h.cell("A.4/count", "count(mt) over a to-one join below the entity join", () -> counted(sf, "mt"));
        h.cell("A.4/count", "count(mt.id)", () -> counted(sf, "mt.id"));
        h.cell("A.4/count", "count(ic.hierarchyId): @IdClass first id attribute", () -> inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            D d = new D(cb, details().with(p -> p.incl = true));
            d.source();
            JpaFrom ic = Q.entityJoin(d.hn, MemberIc.class, SqmJoinType.LEFT, j -> cb.and(
                    cb.equal(j.get("hierarchyId"), d.x.get("mq_hierarchyId")),
                    cb.equal(j.get("profileId"), d.x.get("mq_startVertex"))));
            d.q.multiselect(cb.count(d.hn), cb.count(ic.get("hierarchyId")), cb.count(ic));
            Tuple t = em.createQuery(d.q).getSingleResult();
            return "count(hn)=" + t.get(0) + ", count(ic.hierarchyId)=" + t.get(1) + ", count(ic)=" + t.get(2)
                    + "; sql=" + h.lastStatement().sql().replaceAll("\\s+", " ");
        }));
        h.cell("A.4/count", "per-row count(m.id) grouped by hn.id, hierarchy 1: unmatched rows are 0", () -> {
            List<String> got = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                D d = new D(cb, details().with(p -> {
                    p.incl = true;
                    p.hierarchy = 1L;
                }));
                d.source();
                d.entity();
                d.managerType();
                d.q.multiselect(d.hn.get("id"), cb.count(d.m.get("id")), cb.count(d.mt.get("id")))
                        .where(cb.equal(d.hn.get("hierarchyId"), 1L)).groupBy(d.hn.get("id"))
                        .orderBy(cb.asc(d.hn.get("id")));
                return Q.rows(em.createQuery(d.q).getResultList());
            });
            long zero = got.stream().filter(r -> r.endsWith("|0|0")).count();
            long one = got.stream().filter(r -> r.endsWith("|1|1")).count();
            expect(zero + one == got.size() && zero > 0, "zero=" + zero + ", one=" + one + ", rows=" + got.size());
            return "rows=" + got.size() + ", count 0 for " + zero + " unmatched, 1 for " + one;
        });
    }

    private static String counted(SessionFactory sf, String what) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            D d = new D(cb, details().with(p -> p.incl = true));
            d.source();
            d.entity();
            d.managerType();
            From target = switch (what) {
                case "m", "m.id" -> d.m;
                default -> d.mt;
            };
            Expression<Long> count = what.endsWith(".id") ? cb.count(target.get("id")) : cb.count(target);
            d.q.multiselect(cb.count(d.hn), count);
            Tuple t = em.createQuery(d.q).getSingleResult();
            long nodes = Rfc0021Dataset.HIERARCHIES * (long) Rfc0021Dataset.NODES;
            long matched = Long.parseLong(Q.nativeRows(sf, "SELECT count(*) FROM hierarchy_node hn JOIN hierarchy_edge e "
                    + "ON e.hierarchy_id = hn.hierarchy_id AND e.end_vertex = hn.node_id AND e.hops = 2").get(0));
            expect(((Long) t.get(0)) == nodes, "count(hn)=" + t.get(0));
            expect(((Long) t.get(1)) == matched, "count(" + what + ")=" + t.get(1) + ", matched " + matched);
            return "count(hn)=" + t.get(0) + ", count(" + what + ")=" + t.get(1) + " (unmatched counted 0)";
        });
    }
}
