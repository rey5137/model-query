package com.rey.modelquery.spike.rfc0020;

import static com.rey.modelquery.spike.rfc0020.SpikeHarness.expect;
import static com.rey.modelquery.spike.rfc0020.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.BusinessDay;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.RankRow;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.SaleLine;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Tag;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.query.NullPrecedence;
import org.hibernate.query.SortDirection;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaOrder;
import org.hibernate.query.criteria.JpaPredicate;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.criteria.JpaWindow;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.query.sqm.tree.expression.SqmExpression;
import org.hibernate.query.sqm.tree.expression.SqmOrderedSetAggregateFunction;
import org.hibernate.query.sqm.tree.select.SqmSortSpecification;

/**
 * B.1 and B.2, the first value in a group (RFC 0020 Part B, R-AGG-16). B.1 reads {@code arrayAgg} plus
 * {@code arrayGet} three ways: Criteria with its single {@code JpaOrder} (no room for the appended id), the same
 * expression with the id appended to its {@code within group} order through the SQM tree, and HQL; over
 * {@code rank_row} (ties, a NULL key, a NULL value, a converted value, a filter the first row fails). B.2 is the
 * ranked-source route ({@code row_number} in a CTE, a CTE over it on rank 1, joined as Part A) on every vendor. Both
 * run against two proxies of LitePOS's blocked methods: the top item per business day and per category.
 */
final class PartBProbes {

    private PartBProbes() {}

    /** One route's first-by expression over {@code r}, ordered by {@code k} desc with {@code nulls}. */
    private interface Route {
        Expression<?> first(HibernateCriteriaBuilder cb, JpaRoot<RankRow> r, String value, NullPrecedence nulls,
                JpaPredicate filter);
    }

    private static final Map<Integer, Object> FIRST_V = values("a", "e", null, "h");
    private static final Map<Integer, Object> FIRST_V_NULLS_FIRST = values("a", "d", null, "h");
    private static final Map<Integer, Object> FIRST_V_OK = values("b", "e", null, "h");
    private static final Map<Integer, Object> FIRST_TAG = values(Tag.HIGH, Tag.LOW, Tag.HIGH, Tag.HIGH);

    static void run(SpikeHarness h, SessionFactory sf) {
        Route single = (cb, r, value, nulls, filter) -> cb.arrayGet(arrayAgg(cb, r, value, nulls, filter), 1);
        Route withId = (cb, r, value, nulls, filter) -> {
            JpaExpression<Object[]> array = arrayAgg(cb, r, value, nulls, filter);
            appendId(array, r);
            return cb.arrayGet(array, 1);
        };
        Route pattern = (cb, r, value, nulls, filter) -> {
            JpaExpression<Object[]> array = arrayAgg(cb, r, value, nulls, filter);
            appendId(array, r);
            // Typed as the value's Java type, as the renderer would; Object.class would override the element type.
            return cb.function(SpikeFunctions.FIRST, r.get(value).getJavaType(), array);
        };
        crafted(h, sf, "criteria single order", single, false);
        crafted(h, sf, "criteria + appended id (SQM)", withId, true);
        crafted(h, sf, "pattern (?1)[1] + appended id", pattern, true);
        Route untyped = (cb, r, value, nulls, filter) -> {
            JpaExpression<Object[]> array = arrayAgg(cb, r, value, nulls, filter);
            appendId(array, r);
            // No implied result type: the pattern's element-type resolver types the result.
            return cb.function(SpikeFunctions.FIRST, null, array);
        };
        crafted(h, sf, "pattern (?1)[1] untyped + appended id", untyped, true);
        hql(h, sf);
        caseForm(h, sf);
        ranked(h, sf);
        litePos(h, sf, "arrayGet", false);
        litePos(h, sf, "pattern (?1)[1]", true);
        litePosRanked(h, sf);
    }

    private static void crafted(SpikeHarness h, SessionFactory sf, String route, Route form, boolean exact) {
        h.cell("B.1/first-by", route + ": v by k desc nulls last", () -> {
            Map<Integer, Object> got = grouped(sf, (cb, r) -> form.first(cb, r, "v", NullPrecedence.LAST, null));
            // Without the appended id the ties of groups 1 and 4 are the database's pick.
            expect(exact ? got.equals(FIRST_V) : got.get(2).equals("e") && got.get(3) == null, "got " + got);
            return "got " + got + (exact ? "" : " (ties unresolved: group 1 a|b, group 4 h|i)");
        });
        if (!exact) {
            return;
        }
        h.cell("B.1/first-by", route + ": v by k desc nulls first", () -> {
            Map<Integer, Object> got = grouped(sf, (cb, r) -> form.first(cb, r, "v", NullPrecedence.FIRST, null));
            expect(got.equals(FIRST_V_NULLS_FIRST), "got " + got);
            return "got " + got;
        });
        h.cell("B.1/first-by", route + ": converted tag", () -> {
            Map<Integer, Object> got = grouped(sf, (cb, r) -> form.first(cb, r, "tag", NullPrecedence.LAST, null));
            expect(got.equals(FIRST_TAG), "got " + got);
            return "got " + got + "; type=" + got.get(1).getClass().getSimpleName();
        });
        h.cell("B.1/filtered", route + ": native FILTER (ok = true), first row fails it", () -> {
            Map<Integer, Object> got = grouped(sf, (cb, r) -> form.first(cb, r, "v", NullPrecedence.LAST,
                    cb.isTrue(r.get("ok"))));
            expect(got.equals(FIRST_V_OK), "got " + got);
            return "got " + got + "; FILTER clause=" + h.sql().toLowerCase().contains("filter (where");
        });
    }

    /** HQL, which takes several sort keys in {@code within group}. */
    private static void hql(SpikeHarness h, SessionFactory sf) {
        String order = "within group (order by r.k desc nulls last, r.id)";
        h.cell("B.1/hql", "array_get(array_agg(r.v) " + order + ", 1)", () -> {
            Map<Integer, Object> got = hqlGrouped(sf, "array_get(array_agg(r.v) " + order + ", 1)");
            expect(got.equals(FIRST_V), "got " + got);
            return "got " + got;
        });
        h.cell("B.1/hql", "array_agg(r.v) " + order + " filter (where r.ok)", () -> {
            Map<Integer, Object> got = hqlGrouped(sf, "array_get(array_agg(r.v) " + order
                    + " filter (where r.ok = true), 1)");
            expect(got.equals(FIRST_V_OK), "got " + got);
            return "got " + got;
        });
    }

    /**
     * The engine's CASE form of a filtered first-by, wrong when the first row in order fails the filter; read through
     * the pattern so the rendering does not fail first on PostgreSQL.
     */
    private static void caseForm(SpikeHarness h, SessionFactory sf) {
        h.cell("B.1/filtered", "CASE form array_agg(case when ok then v end ...)[1]", () -> {
            Map<Integer, Object> got = grouped(sf, (cb, r) -> {
                JpaExpression<Object[]> array = cb.arrayAgg(order(cb, r, NullPrecedence.LAST),
                        cb.selectCase().when(cb.isTrue(r.get("ok")), r.get("v")));
                appendId(array, r);
                return cb.function(SpikeFunctions.FIRST, String.class, array);
            });
            expect(got.equals(FIRST_V_OK), "got " + got + ", expected " + FIRST_V_OK);
            return "got " + got;
        });
    }

    /** B.2 over {@code rank_row}: row_number in a CTE, rank 1 in a CTE over it, read as the root. */
    private static void ranked(SpikeHarness h, SessionFactory sf) {
        for (boolean filtered : new boolean[] {false, true}) {
            h.cell("B.2/ranked", "rank_row" + (filtered ? " where ok" : "") + ", k desc nulls last, id", () -> {
                Map<Integer, Object> got = inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                    JpaCriteriaQuery<Tuple> body = cb.createTupleQuery();
                    JpaRoot<RankRow> r = body.from(RankRow.class);
                    JpaWindow w = cb.createWindow().partitionBy(r.get("grp"))
                            .orderBy(order(cb, r, NullPrecedence.LAST), cb.asc(r.get("id")));
                    body.multiselect(r.get("grp").alias("mq_grp"), r.get("v").alias("mq_v"),
                            cb.rowNumber(w).alias("mq_rank"));
                    if (filtered) {
                        body.where(cb.isTrue(r.get("ok")));
                    }
                    JpaRoot<Tuple> first = q.from(rankOne(cb, q, q.with(body), "mq_grp", "mq_v"));
                    q.multiselect(first.get("mq_grp"), first.get("mq_v")).orderBy(cb.asc(first.get("mq_grp")));
                    Map<Integer, Object> out = new LinkedHashMap<>();
                    em.createQuery(q).getResultList().forEach(t -> out.put((Integer) t.get(0), t.get(1)));
                    return out;
                });
                Map<Integer, Object> expected = filtered ? FIRST_V_OK : FIRST_V;
                expect(got.equals(expected), "got " + got);
                return "got " + got;
            });
        }
    }

    /**
     * Proxies of LitePOS's two blocked methods, {@code (array_agg(name order by qty desc))[1]}: the top item per
     * business day (B.1 grouped; B.2 joined to {@code business_day}) and per category (B.1 grouped; B.2 as root).
     */
    private static void litePos(SpikeHarness h, SessionFactory sf, String route, boolean pattern) {
        Map<Object, String>[] expected = expected(h);
        h.cell("B.1/litepos", "top item per day, " + route + " + appended id", () -> {
            Map<Object, Object> got = lines(sf, false, (cb, l, by) -> topItem(cb, l, pattern));
            h.keep("B.1/litepos/day/" + (pattern ? "pattern" : "arrayGet"));
            expect(got.equals(expected[0]), "differs from the JDBC reference");
            return "days=" + got.size() + ", equal to the JDBC reference";
        });
        h.cell("B.1/litepos", "top item per category, " + route + " + appended id", () -> {
            Map<Object, Object> got = lines(sf, true, (cb, l, by) -> topItem(cb, l, pattern));
            h.keep("B.1/litepos/category/" + (pattern ? "pattern" : "arrayGet"));
            expect(got.equals(expected[1]), "differs from the JDBC reference");
            return "categories=" + got.size() + ", equal to the JDBC reference";
        });
    }

    private static void litePosRanked(SpikeHarness h, SessionFactory sf) {
        Map<Object, String>[] expected = expected(h);
        h.cell("B.2/litepos", "top item per day, ranked source LEFT-joined to business_day", () -> {
            Map<Object, Object> got = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
                JpaCteCriteria<Tuple> top = rankOne(cb, q, rankedLines(cb, q, false), "mq_key", "mq_name");
                JpaFrom<?, Tuple> x = PartAProbes.join(d, top, SqmJoinType.LEFT,
                        j -> cb.equal(j.get("mq_key"), d.get("businessDate")));
                q.multiselect(d.get("businessDate"), x.get("mq_name")).where(cb.isNotNull(x.get("mq_key")))
                        .orderBy(cb.asc(d.get("id")));
                Map<Object, Object> out = new LinkedHashMap<>();
                em.createQuery(q).getResultList().forEach(t -> out.put(t.get(0), t.get(1)));
                return out;
            });
            h.keep("B.2/litepos/day");
            expect(got.equals(expected[0]), "differs from the JDBC reference");
            return "days=" + got.size() + ", equal to the JDBC reference";
        });
        h.cell("B.2/litepos", "top item per category, ranked source as root", () -> {
            Map<Object, Object> got = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                JpaRoot<Tuple> first = q.from(rankOne(cb, q, rankedLines(cb, q, true), "mq_key", "mq_name"));
                q.multiselect(first.get("mq_key"), first.get("mq_name")).orderBy(cb.asc(first.get("mq_key")));
                Map<Object, Object> out = new LinkedHashMap<>();
                em.createQuery(q).getResultList().forEach(t -> out.put(t.get(0), t.get(1)));
                return out;
            });
            h.keep("B.2/litepos/category");
            expect(got.equals(expected[1]), "differs from the JDBC reference");
            return "categories=" + got.size() + ", equal to the JDBC reference";
        });
    }

    private interface LineSelect {
        Expression<?> select(HibernateCriteriaBuilder cb, JpaRoot<SaleLine> l, Path<?> by);
    }

    /** {@code [key, first item name]} over sale lines grouped by the sale's date or the item's category. */
    private static Map<Object, Object> lines(SessionFactory sf, boolean category, LineSelect select) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaRoot<SaleLine> l = q.from(SaleLine.class);
            Path<?> by = category ? l.join("item").get("category") : l.join("sale").get("businessDate");
            q.multiselect(by, select.select(cb, l, by)).groupBy(by).orderBy(cb.asc(by));
            Map<Object, Object> out = new LinkedHashMap<>();
            em.createQuery(q).getResultList().forEach(t -> out.put(t.get(0), t.get(1)));
            return out;
        });
    }

    private static Expression<?> topItem(HibernateCriteriaBuilder cb, JpaRoot<SaleLine> l, boolean pattern) {
        JpaExpression<Object[]> array = cb.arrayAgg(cb.desc(l.get("qty")), l.get("item").get("name"));
        appendIdOf(array, l.get("id"));
        return pattern ? cb.function(SpikeFunctions.FIRST, String.class, array) : cb.arrayGet(array, 1);
    }

    /** Sale lines ranked by qty desc, id within the day or the category: {@code mq_key, mq_name, mq_rank}. */
    private static JpaCteCriteria<Tuple> rankedLines(HibernateCriteriaBuilder cb, JpaCriteriaQuery<?> top,
            boolean category) {
        JpaCriteriaQuery<Tuple> body = cb.createTupleQuery();
        JpaRoot<SaleLine> l = body.from(SaleLine.class);
        Path<?> key = category ? l.join("item").get("category") : l.join("sale").get("businessDate");
        JpaWindow w = cb.createWindow().partitionBy(key).orderBy(cb.desc(l.get("qty")), cb.asc(l.get("id")));
        body.multiselect(key.alias("mq_key"), l.get("item").get("name").alias("mq_name"),
                cb.rowNumber(w).alias("mq_rank"));
        return top.with(body);
    }

    /** A CTE over {@code ranked} keeping rank 1, selecting {@code key} and {@code value}. */
    private static JpaCteCriteria<Tuple> rankOne(HibernateCriteriaBuilder cb, JpaCriteriaQuery<?> top,
            JpaCteCriteria<Tuple> ranked, String key, String value) {
        JpaCriteriaQuery<Tuple> body = cb.createTupleQuery();
        JpaRoot<Tuple> t = body.from(ranked);
        body.multiselect(t.get(key).alias(key), t.get(value).alias(value)).where(cb.equal(t.get("mq_rank"), 1L));
        return top.with(body);
    }

    /** The JDBC reference for both LitePOS proxies: the max qty, then the smallest line id, per day and category. */
    @SuppressWarnings("unchecked")
    private static Map<Object, String>[] expected(SpikeHarness h) {
        Map<Object, String>[] out = new Map[] {new HashMap<>(), new HashMap<>()};
        Map<Object, long[]>[] best = new Map[] {new HashMap<>(), new HashMap<>()};
        try (Connection conn = h.raw().getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select l.id, s.business_date, i.category, i.name, l.qty from sale_line l "
                     + "join sale s on s.id = l.sale_id join item i on i.id = l.item_id")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                Object[] keys = {rs.getDate(2).toLocalDate(), rs.getString(3)};
                int qty = rs.getInt(5);
                for (int i = 0; i < 2; i++) {
                    long[] b = best[i].get(keys[i]);
                    if (b == null || qty > b[0] || (qty == b[0] && id < b[1])) {
                        best[i].put(keys[i], new long[] {qty, id});
                        out[i].put(keys[i], rs.getString(4));
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static JpaExpression<Object[]> arrayAgg(HibernateCriteriaBuilder cb, JpaRoot<RankRow> r, String value,
            NullPrecedence nulls, JpaPredicate filter) {
        JpaOrder order = order(cb, r, nulls);
        Expression<Object> v = r.get(value);
        return filter == null ? cb.arrayAgg(order, v) : cb.arrayAgg(order, filter, v);
    }

    private static JpaOrder order(HibernateCriteriaBuilder cb, JpaRoot<RankRow> r, NullPrecedence nulls) {
        return cb.sort((JpaExpression<?>) r.get("k"), SortDirection.DESCENDING, nulls);
    }

    private static void appendId(JpaExpression<Object[]> array, JpaRoot<RankRow> r) {
        appendIdOf(array, r.get("id"));
    }

    /** Appends {@code id asc} to the aggregate's {@code within group} order: the SQM tree, not the Criteria API. */
    private static void appendIdOf(JpaExpression<Object[]> array, Path<?> id) {
        ((SqmOrderedSetAggregateFunction<?>) array).getWithinGroup().addSortSpecification(
                new SqmSortSpecification((SqmExpression<?>) id, SortDirection.ASCENDING, NullPrecedence.NONE));
    }

    private interface RankSelect {
        Expression<?> select(HibernateCriteriaBuilder cb, JpaRoot<RankRow> r);
    }

    private static Map<Integer, Object> grouped(SessionFactory sf, RankSelect select) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaRoot<RankRow> r = q.from(RankRow.class);
            q.multiselect(r.get("grp"), select.select(cb, r)).groupBy(r.get("grp")).orderBy(cb.asc(r.get("grp")));
            Map<Integer, Object> out = new LinkedHashMap<>();
            em.createQuery(q).getResultList().forEach(t -> out.put((Integer) t.get(0), t.get(1)));
            return out;
        });
    }

    private static Map<Integer, Object> hqlGrouped(SessionFactory sf, String select) {
        return inEm(sf, em -> {
            Map<Integer, Object> out = new LinkedHashMap<>();
            em.createQuery("select r.grp, " + select + " from RankRow r group by r.grp order by r.grp",
                    Object[].class).getResultList().forEach(t -> out.put((Integer) t[0], t[1]));
            return out;
        });
    }

    private static Map<Integer, Object> values(Object... byGroup) {
        Map<Integer, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < byGroup.length; i++) {
            out.put(i + 1, byGroup[i]);
        }
        return out;
    }
}
