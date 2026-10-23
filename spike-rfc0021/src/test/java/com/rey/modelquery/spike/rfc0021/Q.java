package com.rey.modelquery.spike.rfc0021;

import static com.rey.modelquery.spike.rfc0021.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0021.Rfc0021Entities.HierarchyEdge;
import com.rey.modelquery.spike.rfc0021.Rfc0021Entities.HierarchyNode;
import com.rey.modelquery.spike.rfc0021.Rfc0021Entities.Profile;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * The motivating Details statement, built straight on Hibernate Criteria (the library has no entity-join API yet): the
 * root {@code hierarchy_node} {@code hn}, a grouped edge CTE joined as RFC 0020's {@code SourceRenderer#join} does,
 * the proposed entity join of {@code profile} off the CTE's {@code mq_startVertex}, and the node's profile as an
 * ordinary to-one path. The entity join goes through the proposed {@code EntityJoinRenderer#join}: reflective
 * {@code JpaFrom#join(Class, SqmJoinType)} and a reflective {@code on}, called once with the whole predicate.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Q {

    private Q() {}

    /** The builder of the statement being built, for static ON functions that only see the join. */
    static HibernateCriteriaBuilder CB;

    static final Method ENTITY_JOIN = method(JpaFrom.class, "join", Class.class, SqmJoinType.class);
    static final Method CTE_JOIN = method(JpaFrom.class, "join", JpaCteCriteria.class, SqmJoinType.class);

    /** One request: filters, kinds, and which pieces of the statement are built. */
    static final class P {
        boolean incl;
        Long hierarchy;
        Long manager;
        Long node;
        String firstName;
        List<Long> nodeIds;
        int hops = 2;
        SqmJoinType source = SqmJoinType.LEFT;
        SqmJoinType entity = SqmJoinType.LEFT;
        boolean anchorCte;        // the entity join under the CTE join instead of the root (comparison only)
        boolean attachEntity = true;
        boolean joinFilters;      // Part B: the deleted checks of the node and manager profiles go in their ON
        boolean nodeFirst;        // creation order: the node's association join before the source and entity joins
        boolean whereDeleted;     // without Part B: deleted checks of c and p as where terms
        boolean noDeletedCheck;   // without Part B, no deleted check on c and p at all
        boolean skipSource;       // no source and no entity join (the Simple model)
        boolean filterNode = true;     // with joinFilters: the node's profile ON takes the deleted check
        boolean filterManager = true;  // with joinFilters: the entity join's ON takes the deleted check
        Function<From, Predicate> staticNode;     // a static TableField.on(...) of the node's profile join
        Function<From, Predicate> staticManager;  // the same for the entity join
        boolean splitOn;               // the node's ON as two on(...) calls, the second replacing the first
        Function<D, Predicate> extra; // one more where term (statement kinds: ids, keyset, one key)

        P copy() {
            P p = new P();
            p.incl = incl; p.hierarchy = hierarchy; p.manager = manager; p.node = node; p.firstName = firstName;
            p.nodeIds = nodeIds; p.hops = hops; p.source = source; p.entity = entity; p.anchorCte = anchorCte;
            p.attachEntity = attachEntity; p.joinFilters = joinFilters; p.nodeFirst = nodeFirst;
            p.whereDeleted = whereDeleted; p.noDeletedCheck = noDeletedCheck; p.skipSource = skipSource;
            p.extra = extra; p.filterNode = filterNode; p.filterManager = filterManager;
            p.staticNode = staticNode; p.staticManager = staticManager; p.splitOn = splitOn;
            return p;
        }

        P with(java.util.function.Consumer<P> c) {
            P p = copy();
            c.accept(p);
            return p;
        }
    }

    /** What the statement selects. */
    enum Sel { DETAILS, UNREFERENCED, ID, COUNT, SIMPLE }

    /** A statement under construction. */
    static final class D {
        final HibernateCriteriaBuilder cb;
        final JpaCriteriaQuery<Tuple> q;
        final P p;
        JpaRoot<HierarchyNode> hn;
        JpaFrom x;        // the joined source
        JpaFrom m;        // the entity join (manager)
        Join c;           // node's profile (association join)
        Join ct;          // its type
        From mt;          // the manager's type: a to-one path below the entity join
        final List<Predicate> where = new ArrayList<>();

        D(HibernateCriteriaBuilder cb, P p) {
            this.cb = cb;
            CB = cb;
            this.q = cb.createTupleQuery();
            this.p = p;
            this.hn = q.from(HierarchyNode.class);
        }

        Predicate notDeleted(From from) {
            return cb.notEqual(from.get("deleted"), true);
        }

        void source() {
            JpaCriteriaQuery<Tuple> body = cb.createTupleQuery();
            JpaRoot<HierarchyEdge> e = body.from(HierarchyEdge.class);
            body.multiselect(e.get("hierarchyId").alias("mq_hierarchyId"), e.get("endVertex").alias("mq_endVertex"),
                    cb.max(e.<Long>get("startVertex")).alias("mq_startVertex"),
                    cb.greatest(e.<java.time.LocalDateTime>get("createdAt")).alias("mq_createdAt"));
            List<Predicate> w = new ArrayList<>();
            w.add(cb.equal(e.get("hops"), p.hops));
            if (p.hierarchy != null) {
                w.add(cb.equal(e.get("hierarchyId"), p.hierarchy));
            }
            if (!p.incl) {
                w.add(cb.notEqual(e.get("deleted"), true));
            }
            body.where(w.toArray(new Predicate[0])).groupBy(e.get("hierarchyId"), e.get("endVertex"));
            JpaCteCriteria<Tuple> cte = q.with(body);
            x = (JpaFrom) invoke(CTE_JOIN, hn, cte, p.source);
            on(CTE_JOIN, x, cb.and(cb.equal(x.get("mq_hierarchyId"), hn.get("hierarchyId")),
                    cb.equal(x.get("mq_endVertex"), hn.get("nodeId"))));
        }

        /** The proposed hook: the entity join off {@code anchor}, with the predicate built once from the join. */
        void entity() {
            JpaFrom anchor = p.anchorCte ? x : hn;
            m = entityJoin(anchor, Profile.class, p.entity, j -> {
                List<Predicate> parts = new ArrayList<>();
                parts.add(cb.equal(j.get("id"), x.get("mq_startVertex")));
                if (p.staticManager != null) {
                    parts.add(p.staticManager.apply(j));
                }
                if (p.joinFilters && p.filterManager && !p.incl) {
                    parts.add(notDeleted(j));
                }
                return parts.size() == 1 ? parts.get(0) : cb.and(parts.toArray(new Predicate[0]));
            });
        }

        void node() {
            c = hn.join("node", JoinType.LEFT);
            List<Predicate> parts = new ArrayList<>();
            if (p.staticNode != null) {
                parts.add(p.staticNode.apply(c));
            }
            if (p.joinFilters && p.filterNode && !p.incl) {
                parts.add(notDeleted(c));
            }
            if (p.splitOn) {
                for (Predicate part : parts) {
                    c.on(part);
                }
            } else if (!parts.isEmpty()) {
                c.on(parts.size() == 1 ? parts.get(0) : cb.and(parts.toArray(new Predicate[0])));
            }
            ct = c.join("type", JoinType.LEFT);
        }

        void managerType() {
            mt = (From) m.join("type", JoinType.LEFT);
        }

        void build(Sel sel) {
            boolean needNode = sel == Sel.DETAILS || sel == Sel.SIMPLE || p.firstName != null;
            boolean needMgr = sel == Sel.DETAILS || p.manager != null;
            if (p.nodeFirst && needNode) {
                node();
            }
            if (!p.skipSource) {
                source();
                if (p.attachEntity) {
                    entity();
                }
            }
            if (!p.nodeFirst && needNode) {
                node();
            }
            if (sel == Sel.DETAILS && p.attachEntity && !p.skipSource) {
                managerType();
            }
            if (p.hierarchy != null) {
                where.add(cb.equal(hn.get("hierarchyId"), p.hierarchy));
            }
            if (p.manager != null) {
                where.add(cb.equal(x.get("mq_startVertex"), p.manager));
            }
            if (p.node != null) {
                where.add(cb.equal(hn.get("nodeId"), p.node));
            }
            if (p.nodeIds != null) {
                where.add(hn.get("nodeId").in(p.nodeIds));
            }
            if (p.firstName != null) {
                where.add(cb.equal(c.get("firstName"), p.firstName));
            }
            if (!p.incl) {
                where.add(notDeleted(hn));
            }
            if (p.extra != null) {
                where.add(p.extra.apply(this));
            }
            if (p.whereDeleted && !p.incl) {
                // a where term drops the row where the native SQL blanks the profile (A.7 item 2)
                where.add(notDeleted(c));
                if (m != null) {
                    where.add(cb.or(cb.isNull(m.get("id")), notDeleted(m)));
                }
            }
            switch (sel) {
                case DETAILS -> q.multiselect(hn.get("id"), hn.get("hierarchyId"), hn.get("nodeId"), c.get("firstName"),
                        ct.get("name"), x.get("mq_startVertex"),
                        p.attachEntity ? m.get("firstName") : cb.nullLiteral(String.class),
                        p.attachEntity ? mt.get("name") : cb.nullLiteral(String.class), x.get("mq_createdAt"));
                case UNREFERENCED -> q.multiselect(hn.get("id"), hn.get("hierarchyId"), hn.get("nodeId"),
                        x.get("mq_startVertex"), x.get("mq_createdAt"));
                case ID -> q.multiselect(hn.get("id"));
                case COUNT -> q.multiselect(cb.count(hn));
                case SIMPLE -> q.multiselect(hn.get("hierarchyId"), hn.get("nodeId"), c.get("firstName"),
                        ct.get("name"));
            }
            if (!where.isEmpty()) {
                q.where(where.toArray(new Predicate[0]));
            }
            if (sel == Sel.SIMPLE) {
                q.orderBy(cb.asc(hn.get("hierarchyId")), cb.asc(hn.get("nodeId")));
            } else if (sel != Sel.COUNT) {
                q.orderBy(cb.asc(hn.get("id")));
            }
        }
    }

    /** {@code EntityJoinRenderer#join}: reflective join and on, the {@code on} function called once. */
    static JpaFrom entityJoin(JpaFrom anchor, Class<?> entity, SqmJoinType kind, Function<JpaFrom, Predicate> on) {
        JpaFrom joined = (JpaFrom) invoke(ENTITY_JOIN, anchor, entity, kind);
        on(ENTITY_JOIN, joined, on.apply(joined));
        return joined;
    }

    static void on(Method join, Object joined, Predicate predicate) {
        invoke(method(join.getReturnType(), "on", Predicate[].class), joined, (Object) new Predicate[] {predicate});
    }

    // ------------------------------------------------------------------------------------------------ running

    static List<String> run(SessionFactory sf, P p, Sel sel, int first, int max) {
        return inEm(sf, em -> {
            D d = new D((HibernateCriteriaBuilder) em.getCriteriaBuilder(), p);
            d.build(sel);
            var query = em.createQuery(d.q);
            if (first > 0) {
                query.setFirstResult(first);
            }
            if (max > 0) {
                query.setMaxResults(max);
            }
            return rows(query.getResultList());
        });
    }

    static List<String> stream(SessionFactory sf, P p, Sel sel) {
        return inEm(sf, em -> {
            D d = new D((HibernateCriteriaBuilder) em.getCriteriaBuilder(), p);
            d.build(sel);
            try (var s = em.createQuery(d.q).setHint("org.hibernate.fetchSize", 100).getResultStream()) {
                return rows(s.toList());
            }
        });
    }

    static List<String> rows(List<?> results) {
        List<String> out = new ArrayList<>();
        for (Object r : results) {
            Object[] cells = r instanceof Tuple t ? t.toArray() : r instanceof Object[] a ? a : new Object[] {r};
            out.add(java.util.Arrays.stream(cells).map(Q::norm).collect(Collectors.joining("|")));
        }
        return out;
    }

    static String norm(Object o) {
        if (o == null) {
            return "~";
        }
        if (o instanceof Timestamp t) {
            return t.toLocalDateTime().toString();
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return o.toString();
    }

    static List<String> nativeRows(SessionFactory sf, String sql) {
        return inEm(sf, em -> rows(em.createNativeQuery(sql).getResultList()));
    }

    // ------------------------------------------------------------------------------------------------ native SQL

    /** The motivating native SQL (§Motivation), with {@code hn.id} added for the order. */
    static String nativeDetails(P p, boolean count) {
        String edgeDeleted = p.incl ? "" : " AND e.is_deleted = false";
        StringBuilder w = new StringBuilder(" WHERE 1 = 1");
        if (p.hierarchy != null) {
            w.append(" AND h.id = ").append(p.hierarchy);
        }
        if (p.manager != null) {
            w.append(" AND e.start_vertex = ").append(p.manager);
        }
        if (p.node != null) {
            w.append(" AND hn.node_id = ").append(p.node);
        }
        if (p.nodeIds != null) {
            w.append(" AND hn.node_id IN (")
                    .append(p.nodeIds.stream().map(String::valueOf).collect(Collectors.joining(", "))).append(")");
        }
        if (!p.incl) {
            w.append(" AND hn.is_deleted = false");
        }
        String cDeleted = p.incl ? "" : " AND c.is_deleted = false";
        String pDeleted = p.incl ? "" : " AND p.is_deleted = false";
        String outer = p.firstName == null ? "" : " WHERE c.first_name = '" + p.firstName + "'";
        return "SELECT " + (count ? "count(*)"
                : "r.id, r.hierarchy_id, r.node_id, c.first_name, ct.name, r.start_vertex, p.first_name, pt.name, "
                        + "r.created_at")
                + " FROM (SELECT hn.id, hn.hierarchy_id, hn.node_id, e.start_vertex, e.created_at"
                + " FROM hierarchy_node hn LEFT JOIN hierarchy h ON h.id = hn.hierarchy_id"
                + (p.source == SqmJoinType.INNER ? " JOIN" : " LEFT JOIN")
                + " hierarchy_edge e ON e.hierarchy_id = h.id AND e.end_vertex = hn.node_id AND e.hops = " + p.hops
                + edgeDeleted + w + ") r"
                + " LEFT JOIN profile c ON c.id = r.node_id" + cDeleted
                + " LEFT JOIN profile_type ct ON ct.id = c.type_id"
                + " LEFT JOIN profile p ON p.id = r.start_vertex" + pDeleted
                + " LEFT JOIN profile_type pt ON pt.id = p.type_id" + outer
                + (count ? "" : " ORDER BY r.id");
    }

    static String nativeSimple(P p) {
        StringBuilder w = new StringBuilder(" WHERE 1 = 1");
        if (p.hierarchy != null) {
            w.append(" AND hn.hierarchy_id = ").append(p.hierarchy);
        }
        if (p.node != null) {
            w.append(" AND hn.node_id = ").append(p.node);
        }
        if (p.firstName != null) {
            w.append(" AND c.first_name = '").append(p.firstName).append("'");
        }
        if (!p.incl) {
            w.append(" AND hn.is_deleted = false");
        }
        return "SELECT DISTINCT hn.hierarchy_id, hn.node_id, c.first_name, ct.name FROM hierarchy_node hn"
                + " LEFT JOIN profile c ON c.id = hn.node_id" + (p.incl ? "" : " AND c.is_deleted = false")
                + " LEFT JOIN profile_type ct ON ct.id = c.type_id" + w + " ORDER BY hn.hierarchy_id, hn.node_id";
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final Pattern JOIN = Pattern.compile("(?i)(?:from|join)\\s+(\\w+)\\s+(\\w+)");

    /** The relations in the order the SQL names them: {@code table alias, ...}. */
    static String joinOrder(String sql) {
        Matcher m = JOIN.matcher(sql.split(" \\|\\| ")[0]);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1) + " " + m.group(2));
        }
        return String.join(" > ", out);
    }

    static int occurrences(String sql, String what) {
        int n = 0;
        for (int i = sql.indexOf(what); i >= 0; i = sql.indexOf(what, i + 1)) {
            n++;
        }
        return n;
    }

    static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        }
    }
}
