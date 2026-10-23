package com.rey.modelquery.spike.rfc0020;

import static com.rey.modelquery.spike.rfc0020.SpikeHarness.expect;
import static com.rey.modelquery.spike.rfc0020.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.BusinessDay;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.InventoryMovement;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Sale;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.SaleLine;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.query.NullPrecedence;
import org.hibernate.query.SortDirection;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.criteria.JpaSubQuery;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * A.2, joining a source (RFC 0020 Part A, R-SRC-21/22, A.5): the {@code join} hook over
 * {@code JpaFrom#join(JpaCteCriteria, SqmJoinType)}, called reflectively as a renderer compiled against one line must
 * call it on the other, with {@code on} applied through whichever type the line returns. The source is "day sales",
 * {@code sale} grouped by business date ({@code mq_businessDate}, {@code mq_cash}); the outer root is
 * {@code business_day} (365 days, 30 with sales). Every row read is checked against a plain grouped read.
 */
final class PartAProbes {

    private PartAProbes() {}

    private static final Method CTE_JOIN = method(JpaFrom.class, "join", JpaCteCriteria.class, SqmJoinType.class);
    private static final Method DERIVED_JOIN = method(JpaFrom.class, "join", Subquery.class, SqmJoinType.class);

    /** One statement over the outer root {@code d} and the joined source {@code x}. */
    private interface Shape {
        void build(HibernateCriteriaBuilder cb, JpaCriteriaQuery<Tuple> q, JpaFrom<?, BusinessDay> d,
                JpaFrom<?, Tuple> x);
    }

    static void run(SpikeHarness h, SessionFactory sf) {
        Map<LocalDate, BigDecimal> reference = reference(sf);
        returnTypes(h, sf);
        for (SqmJoinType type : List.of(SqmJoinType.LEFT, SqmJoinType.INNER)) {
            statementKinds(h, sf, type, reference);
        }
        toOnePath(h, sf, reference);
        twoSources(h, sf, reference);
        twoJoinsOfOneModel(h, sf, reference);
        countUnmatched(h, sf);
        sourceOverSource(h, sf, reference);
        whereAndOrder(h, sf, reference);
        nextToRootCte(h, sf, reference);
        derivedTable(h, sf, reference);
    }

    /** The A.5 claim: each overload's declared return type, and the runtime type's relation to JPA's Join. */
    private static void returnTypes(SpikeHarness h, SessionFactory sf) {
        h.cell("A.2/types", "JpaFrom#join(JpaCteCriteria, SqmJoinType) declared return", () -> declared(CTE_JOIN));
        h.cell("A.2/types", "JpaFrom#join(Subquery, SqmJoinType) declared return", () -> declared(DERIVED_JOIN));
        h.cell("A.2/types", "runtime type of the CTE join", () -> inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
            Object joined = invoke(CTE_JOIN, d, salesCte(cb, q, null), SqmJoinType.LEFT);
            return joined.getClass().getName() + "; instanceof jakarta Join="
                    + (joined instanceof Join<?, ?>);
        }));
    }

    private static String declared(Method m) {
        Class<?> type = m.getReturnType();
        return type.getName() + "; is a jakarta Join=" + Join.class.isAssignableFrom(type) + "; on(Predicate...)="
                + (method(type, "on", Predicate[].class) != null);
    }

    /** R-SRC-12's statement kinds: content page, count, both primary-key-first steps, an export page, byKeys. */
    private static void statementKinds(SpikeHarness h, SessionFactory sf, SqmJoinType type,
            Map<LocalDate, BigDecimal> reference) {
        String probe = "A.2/" + type.name();
        boolean left = type == SqmJoinType.LEFT;
        h.cell(probe, "content page (date desc, offset 20, limit 20)", () -> {
            List<Object[]> rows = read(sf, type, (cb, q, d, x) -> q.multiselect(d.get("id"), d.get("businessDate"),
                    x.get("mq_cash")).orderBy(cb.desc(d.get("businessDate"))), 20, 20);
            int matched = check(rows, reference, left);
            expect(rows.size() == (left ? 20 : 10), "rows=" + rows.size());
            return "rows=" + rows.size() + ", matched=" + matched;
        });
        h.cell(probe, "count", () -> {
            long count = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
                JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
                join(d, salesCte(cb, q, null), type, x -> cb.equal(x.get("mq_businessDate"), d.get("businessDate")));
                return em.createQuery(q.select(cb.count(d))).getSingleResult();
            });
            long expected = left ? Rfc0020Dataset.DAYS : Rfc0020Dataset.SALES_DAYS;
            expect(count == expected, "count=" + count + ", expected " + expected);
            return "count=" + count;
        });
        List<Long> ids = new ArrayList<>();
        h.cell(probe, "primary-key-first step 1 (ids)", () -> {
            for (Object[] row : read(sf, type, (cb, q, d, x) -> q.multiselect(d.get("id"))
                    .orderBy(cb.desc(d.get("businessDate"))), 20, 20)) {
                ids.add((Long) row[0]);
            }
            expect(ids.size() == (left ? 20 : 10), "ids=" + ids.size());
            return "ids=" + ids;
        });
        h.cell(probe, "primary-key-first step 2 (rows by id)", () -> {
            expect(!ids.isEmpty(), "step 1 failed");
            List<Object[]> rows = read(sf, type, (cb, q, d, x) -> q.multiselect(d.get("id"), d.get("businessDate"),
                    x.get("mq_cash")).where(d.get("id").in(ids)).orderBy(cb.asc(d.get("id"))), 0, 0);
            expect(rows.size() == ids.size(), "rows=" + rows.size());
            return "rows=" + rows.size() + ", matched=" + check(rows, reference, left);
        });
        h.cell(probe, "export page (keyset id > 300, limit 100)", () -> {
            List<Object[]> rows = read(sf, type, (cb, q, d, x) -> q.multiselect(d.get("id"), d.get("businessDate"),
                    x.get("mq_cash")).where(cb.gt(d.get("id"), 300L)).orderBy(cb.asc(d.get("id"))), 0, 100);
            expect(rows.size() == (left ? 65 : 30), "rows=" + rows.size());
            return "rows=" + rows.size() + ", matched=" + check(rows, reference, left);
        });
        h.cell(probe, "byKeys chunk (ids 1, 340, 365, 999)", () -> {
            List<Object[]> rows = read(sf, type, (cb, q, d, x) -> q.multiselect(d.get("id"), d.get("businessDate"),
                    x.get("mq_cash")).where(d.get("id").in(1L, 340L, 365L, 999L)).orderBy(cb.asc(d.get("id"))),
                    0, 0);
            expect(rows.size() == (left ? 3 : 2), "rows=" + rows.size());
            return "rows=" + rows.size() + ", matched=" + check(rows, reference, left);
        });
        h.cell(probe, "fetch-plan parent read (date desc, offset 20, limit 20), then children by parent key", () -> {
            // The parent read carries the join (R-SRC-12's list); the child read is keyed by the parents' dates only.
            List<Object[]> parents = read(sf, type, (cb, q, d, x) -> q.multiselect(d.get("id"),
                    d.get("businessDate"), x.get("mq_cash")).orderBy(cb.desc(d.get("businessDate"))), 20, 20);
            int matched = check(parents, reference, left);
            expect(parents.size() == (left ? 20 : 10), "parents=" + parents.size());
            List<LocalDate> dates = parents.stream().map(r -> (LocalDate) r[1]).toList();
            Map<LocalDate, Long> children = inEm(sf, em -> {
                Map<LocalDate, Long> out = new LinkedHashMap<>();
                for (Object[] r : em.createQuery("select s.businessDate, count(s.id) from Sale s "
                        + "where s.businessDate in :dates group by s.businessDate", Object[].class)
                        .setParameter("dates", dates).getResultList()) {
                    out.put((LocalDate) r[0], (Long) r[1]);
                }
                return out;
            });
            for (Object[] parent : parents) {
                long expected = parent[2] == null ? 0 : Rfc0020Dataset.SALES_PER_DAY;
                expect(children.getOrDefault((LocalDate) parent[1], 0L) == expected,
                        "children of " + parent[1] + ": " + children.get((LocalDate) parent[1]));
            }
            return "parents=" + parents.size() + ", matched=" + matched + ", children="
                    + children.values().stream().mapToLong(Long::longValue).sum();
        });
    }

    /** {@code on} over a to-one path: sale lines joined to day sales on {@code l.sale.businessDate}. */
    private static void toOnePath(SpikeHarness h, SessionFactory sf, Map<LocalDate, BigDecimal> reference) {
        for (SqmJoinType type : List.of(SqmJoinType.LEFT, SqmJoinType.INNER)) {
            for (boolean explicit : new boolean[] {false, true}) {
                String name = (explicit ? "explicit to-one join" : "implicit path in on") + " " + type.name();
                h.cell("A.2/to-one", name, () -> inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                    JpaRoot<SaleLine> l = q.from(SaleLine.class);
                    Path<LocalDate> date = explicit ? l.join("sale").get("businessDate")
                            : l.get("sale").get("businessDate");
                    JpaFrom<?, Tuple> x = join(l, salesCte(cb, q, null), type,
                            j -> cb.equal(j.get("mq_businessDate"), date));
                    q.multiselect(l.get("id"), date, x.get("mq_cash")).orderBy(cb.asc(l.get("id")));
                    List<Tuple> rows = em.createQuery(q).setMaxResults(30).getResultList();
                    expect(rows.size() == 30, "rows=" + rows.size());
                    for (Tuple t : rows) {
                        expect(PartCProbes.same(t.get(2), reference.get((LocalDate) t.get(1))), "row " + t.get(0));
                    }
                    long count = em.createQuery(q.multiselect(cb.count(l)).orderBy(List.of())).getSingleResult()
                            .get(0, Long.class);
                    long lines = (long) Rfc0020Dataset.SALES_DAYS * Rfc0020Dataset.SALES_PER_DAY
                            * Rfc0020Dataset.LINES_PER_SALE;
                    expect(count == lines, "count=" + count);
                    return "30 rows equal to reference; count=" + count;
                }));
            }
        }
    }

    /** Two joined sources: day sales and day inventory movements, both LEFT. */
    private static void twoSources(SpikeHarness h, SessionFactory sf, Map<LocalDate, BigDecimal> reference) {
        h.cell("A.2/two-sources", "sales + movements, LEFT", () -> inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
            JpaFrom<?, Tuple> x = join(d, salesCte(cb, q, null), SqmJoinType.LEFT,
                    j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
            JpaFrom<?, Tuple> m = join(d, movementsCte(cb, q), SqmJoinType.LEFT,
                    j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
            q.multiselect(d.get("id"), d.get("businessDate"), x.get("mq_cash"), m.get("mq_qty"))
                    .orderBy(cb.asc(d.get("id")));
            List<Tuple> rows = em.createQuery(q).getResultList();
            expect(rows.size() == Rfc0020Dataset.DAYS, "rows=" + rows.size());
            int matched = 0;
            for (Tuple t : rows) {
                expect(PartCProbes.same(t.get(2), reference.get((LocalDate) t.get(1))), "cash on " + t.get(1));
                expect(t.get(3) != null, "no movements on " + t.get(1));
                matched += t.get(2) == null ? 0 : 1;
            }
            return "rows=" + rows.size() + ", sales matched=" + matched + ", movements matched=" + rows.size();
        }));
    }

    /**
     * Two joins of one source model: two CTEs of one body shape with different {@code where} (SALE, REFUND), and one
     * CTE joined twice.
     */
    private static void twoJoinsOfOneModel(SpikeHarness h, SessionFactory sf,
            Map<LocalDate, BigDecimal> reference) {
        h.cell("A.2/one-model-twice", "two CTEs of one model (kind SALE, kind REFUND)", () -> inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
            JpaFrom<?, Tuple> sale = join(d, salesCte(cb, q, "SALE"), SqmJoinType.LEFT,
                    j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
            JpaFrom<?, Tuple> refund = join(d, salesCte(cb, q, "REFUND"), SqmJoinType.LEFT,
                    j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
            q.multiselect(d.get("businessDate"), sale.get("mq_cash"), refund.get("mq_cash"))
                    .orderBy(cb.asc(d.get("id")));
            List<Tuple> rows = em.createQuery(q).getResultList();
            expect(rows.size() == Rfc0020Dataset.DAYS, "rows=" + rows.size());
            for (Tuple t : rows) {
                BigDecimal total = reference.get((LocalDate) t.get(0));
                BigDecimal sum = t.get(1) == null && t.get(2) == null ? null
                        : orZero(t.get(1)).add(orZero(t.get(2)));
                expect(PartCProbes.same(sum, total), "SALE + REFUND differs from the total on " + t.get(0));
            }
            return "rows=" + rows.size() + "; SALE + REFUND = total on every day";
        }));
        h.cell("A.2/one-model-twice", "one CTE joined twice", () -> inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
            JpaCteCriteria<Tuple> cte = salesCte(cb, q, null);
            JpaFrom<?, Tuple> x1 = join(d, cte, SqmJoinType.LEFT,
                    j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
            JpaFrom<?, Tuple> x2 = join(d, cte, SqmJoinType.LEFT,
                    j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
            q.multiselect(d.get("businessDate"), x1.get("mq_cash"), x2.get("mq_cash")).orderBy(cb.asc(d.get("id")));
            List<Tuple> rows = em.createQuery(q).getResultList();
            expect(rows.size() == Rfc0020Dataset.DAYS, "rows=" + rows.size());
            for (Tuple t : rows) {
                expect(PartCProbes.same(t.get(1), reference.get((LocalDate) t.get(0)))
                        && PartCProbes.same(t.get(2), t.get(1)), "differs on " + t.get(0));
            }
            return "rows=" + rows.size() + "; both joins equal to reference";
        }));
    }

    /** A.2's {@code Agg.count} over a LEFT-joined unmatched source: {@code count(1)} against the key. */
    private static void countUnmatched(SpikeHarness h, SessionFactory sf) {
        h.cell("A.2/count-unmatched", "count(1), count(x.mq_businessDate), count(d.id), count(*) over LEFT",
                () -> inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                    JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
                    JpaFrom<?, Tuple> x = join(d, salesCte(cb, q, null), SqmJoinType.LEFT,
                            j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
                    q.multiselect(cb.count(cb.literal(1)), cb.count(x.get("mq_businessDate")), cb.count(d),
                            cb.count());
                    Tuple t = em.createQuery(q).getSingleResult();
                    long one = t.get(0, Long.class);
                    long key = t.get(1, Long.class);
                    expect(one == Rfc0020Dataset.DAYS && key == Rfc0020Dataset.SALES_DAYS,
                            "count(1)=" + one + ", count(key)=" + key);
                    return "count(1)=" + one + ", count(key)=" + key + ", count(d)=" + t.get(2) + ", count(*)="
                            + t.get(3) + " (an unmatched row counts 1 under count(1), 0 under the key)";
                }));
    }

    /** A joined source over a source: a CTE reading the day-sales CTE, keeping days above the median cash. */
    private static void sourceOverSource(SpikeHarness h, SessionFactory sf, Map<LocalDate, BigDecimal> reference) {
        BigDecimal median = median(reference);
        long above = reference.values().stream().filter(v -> v.compareTo(median) > 0).count();
        h.cell("A.2/source-over-source", "LEFT join to a CTE over a CTE (cash > median)", () -> inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
            JpaCteCriteria<Tuple> inner = salesCte(cb, q, null);
            JpaCriteriaQuery<Tuple> body = cb.createTupleQuery();
            JpaRoot<Tuple> r = body.from(inner);
            body.multiselect(r.get("mq_businessDate").alias("mq_businessDate"), r.get("mq_cash").alias("mq_cash"))
                    .where(cb.gt(r.get("mq_cash"), median));
            JpaFrom<?, Tuple> x = join(d, q.with(body), SqmJoinType.LEFT,
                    j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
            q.multiselect(d.get("businessDate"), x.get("mq_cash")).orderBy(cb.asc(d.get("id")));
            List<Tuple> rows = em.createQuery(q).getResultList();
            long matched = rows.stream().filter(t -> t.get(1) != null).count();
            expect(rows.size() == Rfc0020Dataset.DAYS && matched == above,
                    "rows=" + rows.size() + ", matched=" + matched + ", expected " + above);
            for (Tuple t : rows) {
                if (t.get(1) != null) {
                    expect(PartCProbes.same(t.get(1), reference.get((LocalDate) t.get(0))), "cash on " + t.get(0));
                }
            }
            return "rows=" + rows.size() + ", matched=" + matched;
        }));
    }

    /** An outer {@code where} and an order on a joined column, and the order alone (NULLs last). */
    private static void whereAndOrder(SpikeHarness h, SessionFactory sf, Map<LocalDate, BigDecimal> reference) {
        BigDecimal median = median(reference);
        long above = reference.values().stream().filter(v -> v.compareTo(median) > 0).count();
        h.cell("A.2/where-order", "where x.mq_cash > median order by x.mq_cash desc", () -> {
            List<Object[]> rows = read(sf, SqmJoinType.LEFT, (cb, q, d, x) -> q.multiselect(d.get("id"),
                    d.get("businessDate"), x.get("mq_cash")).where(cb.gt(x.get("mq_cash"), median))
                    .orderBy(cb.desc(x.get("mq_cash")), cb.asc(d.get("id"))), 0, 20);
            check(rows, reference, false);
            descending(rows);
            expect(rows.size() == above, "rows=" + rows.size() + ", expected " + above);
            return "rows=" + rows.size() + ", descending, equal to reference";
        });
        h.cell("A.2/where-order", "order by x.mq_cash desc nulls last, limit 40", () -> {
            List<Object[]> rows = read(sf, SqmJoinType.LEFT, (cb, q, d, x) -> q.multiselect(d.get("id"),
                    d.get("businessDate"), x.get("mq_cash")).orderBy(cb.sort((JpaExpression<?>) x.get("mq_cash"),
                    SortDirection.DESCENDING, NullPrecedence.LAST), cb.asc(d.get("id"))), 0, 40);
            check(rows, reference, true);
            descending(rows.subList(0, 30));
            expect(rows.subList(30, 40).stream().allMatch(r -> r[2] == null), "NULLs not last");
            return "30 matched rows descending, then 10 NULL rows";
        });
    }

    /** A joined CTE next to a root CTE: the root is a CTE over business days after day 300. */
    private static void nextToRootCte(SpikeHarness h, SessionFactory sf, Map<LocalDate, BigDecimal> reference) {
        h.cell("A.2/root-cte", "root CTE (days > 300) LEFT join day sales", () -> inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaCriteriaQuery<Tuple> rootBody = cb.createTupleQuery();
            JpaRoot<BusinessDay> b = rootBody.from(BusinessDay.class);
            rootBody.multiselect(b.get("id").alias("mq_id"), b.get("businessDate").alias("mq_businessDate"))
                    .where(cb.gt(b.get("id"), 300L));
            JpaRoot<Tuple> root = q.from(q.with(rootBody));
            JpaFrom<?, Tuple> x = join(root, salesCte(cb, q, null), SqmJoinType.LEFT,
                    j -> cb.equal(j.get("mq_businessDate"), root.get("mq_businessDate")));
            q.multiselect(root.get("mq_id"), root.get("mq_businessDate"), x.get("mq_cash"))
                    .orderBy(cb.asc(root.get("mq_id")));
            List<Object[]> rows = em.createQuery(q).getResultList().stream().map(Tuple::toArray).toList();
            int matched = check(rows, reference, true);
            expect(rows.size() == 65 && matched == Rfc0020Dataset.SALES_DAYS, "rows=" + rows.size()
                    + ", matched=" + matched);
            return "rows=" + rows.size() + ", matched=" + matched;
        }));
    }

    /** The derived-table overload {@code join(Subquery, SqmJoinType)} with the same body (A.3's fallback). */
    private static void derivedTable(SpikeHarness h, SessionFactory sf, Map<LocalDate, BigDecimal> reference) {
        for (SqmJoinType type : List.of(SqmJoinType.LEFT, SqmJoinType.INNER)) {
            h.cell("A.2/derived", "join(Subquery, " + type.name() + ")", () -> inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
                JpaSubQuery<Tuple> sub = q.subquery(Tuple.class);
                JpaRoot<Sale> s = sub.from(Sale.class);
                sub.multiselect(s.get("businessDate").alias("mq_businessDate"),
                        cb.sum(s.<BigDecimal>get("cashTotal")).alias("mq_cash")).groupBy(s.get("businessDate"));
                @SuppressWarnings("unchecked")
                JpaFrom<?, Tuple> x = (JpaFrom<?, Tuple>) invoke(DERIVED_JOIN, d, sub, type);
                on(DERIVED_JOIN, x, cb.equal(x.get("mq_businessDate"), d.get("businessDate")));
                q.multiselect(d.get("id"), d.get("businessDate"), x.get("mq_cash")).orderBy(cb.asc(d.get("id")));
                List<Object[]> rows = em.createQuery(q).getResultList().stream().map(Tuple::toArray).toList();
                boolean left = type == SqmJoinType.LEFT;
                int matched = check(rows, reference, left);
                expect(rows.size() == (left ? Rfc0020Dataset.DAYS : Rfc0020Dataset.SALES_DAYS),
                        "rows=" + rows.size());
                return "rows=" + rows.size() + ", matched=" + matched;
            }));
        }
    }

    /** The day-sales source body, registered on {@code top}: {@code mq_businessDate}, {@code mq_cash}. */
    static JpaCteCriteria<Tuple> salesCte(HibernateCriteriaBuilder cb, JpaCriteriaQuery<?> top, String kind) {
        JpaCriteriaQuery<Tuple> body = cb.createTupleQuery();
        JpaRoot<Sale> s = body.from(Sale.class);
        body.multiselect(s.get("businessDate").alias("mq_businessDate"),
                cb.sum(s.<BigDecimal>get("cashTotal")).alias("mq_cash"));
        if (kind != null) {
            body.where(cb.equal(s.get("kind"), kind));
        }
        body.groupBy(s.get("businessDate"));
        return top.with(body);
    }

    private static JpaCteCriteria<Tuple> movementsCte(HibernateCriteriaBuilder cb, JpaCriteriaQuery<?> top) {
        JpaCriteriaQuery<Tuple> body = cb.createTupleQuery();
        JpaRoot<InventoryMovement> m = body.from(InventoryMovement.class);
        body.multiselect(m.get("businessDate").alias("mq_businessDate"),
                cb.sum(m.<Integer>get("qty")).alias("mq_qty")).groupBy(m.get("businessDate"));
        return top.with(body);
    }

    /**
     * The proposed {@code SourceRenderer#join} hook: the CTE join and its {@code on}, both called reflectively so one
     * binary serves 6.6 ({@code JpaJoinedFrom}) and 7.4 ({@code JpaJoin}).
     */
    @SuppressWarnings("unchecked")
    static JpaFrom<?, Tuple> join(JpaFrom<?, ?> outer, JpaCteCriteria<Tuple> source, SqmJoinType type,
            Function<JpaFrom<?, Tuple>, Predicate> on) {
        JpaFrom<?, Tuple> joined = (JpaFrom<?, Tuple>) invoke(CTE_JOIN, outer, source, type);
        on(CTE_JOIN, joined, on.apply(joined));
        return joined;
    }

    private static void on(Method join, Object joined, Predicate predicate) {
        invoke(method(join.getReturnType(), "on", Predicate[].class), joined, (Object) new Predicate[] {predicate});
    }

    private static List<Object[]> read(SessionFactory sf, SqmJoinType type, Shape shape, int first, int max) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
            JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
            JpaFrom<?, Tuple> x = join(d, salesCte(cb, q, null), type,
                    j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
            shape.build(cb, q, d, x);
            var query = em.createQuery(q).setFirstResult(first);
            if (max > 0) {
                query.setMaxResults(max);
            }
            return rows(query.getResultList());
        });
    }

    private static List<Object[]> rows(List<Tuple> tuples) {
        return tuples.stream().map(Tuple::toArray).toList();
    }

    /** Each {@code [id, date, cash]} row's cash equals the reference; returns the matched count. */
    private static int check(List<Object[]> rows, Map<LocalDate, BigDecimal> reference, boolean left) {
        int matched = 0;
        for (Object[] row : rows) {
            BigDecimal expected = reference.get((LocalDate) row[1]);
            expect(PartCProbes.same(row[2], expected), "cash on " + row[1] + ": " + row[2] + ", expected "
                    + expected);
            expect(left || row[2] != null, "unmatched row under INNER: " + row[1]);
            matched += row[2] == null ? 0 : 1;
        }
        return matched;
    }

    private static void descending(List<Object[]> rows) {
        for (int i = 1; i < rows.size(); i++) {
            expect(((BigDecimal) rows.get(i - 1)[2]).compareTo((BigDecimal) rows.get(i)[2]) >= 0, "not descending");
        }
    }

    /** The cash total per business date, read by a plain grouped query (no join). */
    static Map<LocalDate, BigDecimal> reference(SessionFactory sf) {
        return inEm(sf, em -> {
            Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
            for (Object[] r : em.createQuery("select s.businessDate, sum(s.cashTotal) from Sale s "
                    + "group by s.businessDate", Object[].class).getResultList()) {
                out.put((LocalDate) r[0], (BigDecimal) r[1]);
            }
            return out;
        });
    }

    private static BigDecimal median(Map<LocalDate, BigDecimal> reference) {
        List<BigDecimal> values = new ArrayList<>(reference.values());
        values.sort(null);
        return values.get(values.size() / 2);
    }

    private static BigDecimal orZero(Object v) {
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
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
