package com.rey.modelquery.spike.rfc0020;

import static com.rey.modelquery.spike.rfc0020.SpikeHarness.expect;
import static com.rey.modelquery.spike.rfc0020.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.NullPair;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Promo;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Sale;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

/**
 * E.1, null-safe equality (RFC 0020 Part E, R-FLT-29): the value form, the two-operand OR form, the bare OR form and
 * Hibernate's {@code notDistinctFrom}, alone, under {@code not(...)} and in a correlated {@code exists}, on the exact
 * {@code null_pair} rows (x,x), (x,y), (x,null), (null,x), (null,null), and as counts over sales and promos.
 */
final class PartEProbes {

    private PartEProbes() {}

    /** A predicate over {@code root}; a correlated sub-query is created from {@code outer}, the statement's query. */
    private interface Where<T> {
        Predicate on(HibernateCriteriaBuilder cb, CriteriaQuery<?> outer, Root<T> root);
    }

    /** A two-operand form: the predicate over {@code a} and {@code b}. */
    private interface Form {
        Predicate on(HibernateCriteriaBuilder cb, Expression<?> a, Expression<?> b);
    }

    static Predicate valueForm(CriteriaBuilder cb, Expression<String> column, String value) {
        return value == null ? cb.isNull(column) : cb.and(cb.equal(column, value), cb.isNotNull(column));
    }

    static Predicate valueDistinct(CriteriaBuilder cb, Expression<String> column, String value) {
        return value == null ? cb.isNotNull(column) : cb.or(cb.isNull(column), cb.notEqual(column, value));
    }

    static Predicate orForm(CriteriaBuilder cb, Expression<?> a, Expression<?> b) {
        return cb.or(cb.and(cb.equal(a, b), cb.isNotNull(a), cb.isNotNull(b)), cb.and(cb.isNull(a), cb.isNull(b)));
    }

    static Predicate bareForm(CriteriaBuilder cb, Expression<?> a, Expression<?> b) {
        return cb.or(cb.equal(a, b), cb.and(cb.isNull(a), cb.isNull(b)));
    }

    private static final Form OR = PartEProbes::orForm;
    private static final Form BARE = PartEProbes::bareForm;
    private static final Form NATIVE = HibernateCriteriaBuilder::notDistinctFrom;
    private static final Form EQ = (cb, a, b) -> cb.equal(a, b);

    static void run(SpikeHarness h, SessionFactory sf) {
        valueCells(h, sf);
        twoOperandCells(h, sf);
        existsCells(h, sf);
        saleExistsCells(h, sf);
    }

    private static void valueCells(SpikeHarness h, SessionFactory sf) {
        pairs(h, sf, "E.1/value", "notDistinctFrom(a,'x') as a = ? and a is not null", List.of(1L, 2L, 3L),
                (cb, outer, p) -> valueForm(cb, p.get("a"), "x"));
        pairs(h, sf, "E.1/value", "notDistinctFrom(a,null) as a is null", List.of(4L, 5L),
                (cb, outer, p) -> valueForm(cb, p.get("a"), null));
        pairs(h, sf, "E.1/value", "distinctFrom(a,'x') as a is null or a <> ?", List.of(4L, 5L),
                (cb, outer, p) -> valueDistinct(cb, p.get("a"), "x"));
        pairs(h, sf, "E.1/value", "distinctFrom(a,null) as a is not null", List.of(1L, 2L, 3L),
                (cb, outer, p) -> valueDistinct(cb, p.get("a"), null));
        pairs(h, sf, "E.1/value-not", "not(a = ? and a is not null) keeps the NULL rows", List.of(4L, 5L),
                (cb, outer, p) -> cb.not(valueForm(cb, p.get("a"), "x")));
        pairs(h, sf, "E.1/value-not", "not(a is null)", List.of(1L, 2L, 3L),
                (cb, outer, p) -> cb.not(valueForm(cb, p.get("a"), null)));
        pairs(h, sf, "E.1/value-not", "plain not(a = ?) drops the NULL rows (baseline)", List.of(),
                (cb, outer, p) -> cb.not(cb.equal(p.get("a"), "x")));
        pairs(h, sf, "E.1/value-native", "hcb.notDistinctFrom(a,'x')", List.of(1L, 2L, 3L),
                (cb, outer, p) -> cb.notDistinctFrom(p.get("a"), "x"));
        pairs(h, sf, "E.1/value-native", "hcb.notDistinctFrom(a,(Object) null)", List.of(4L, 5L),
                (cb, outer, p) -> cb.notDistinctFrom(p.get("a"), (Object) null));
        pairs(h, sf, "E.1/value-native", "not(hcb.notDistinctFrom(a,'x'))", List.of(4L, 5L),
                (cb, outer, p) -> cb.not(cb.notDistinctFrom(p.get("a"), "x")));
    }

    private static void twoOperandCells(SpikeHarness h, SessionFactory sf) {
        twoOperand(h, sf, "or-form", OR, List.of(1L, 5L), List.of(2L, 3L, 4L));
        twoOperand(h, sf, "bare-or", BARE, List.of(1L, 5L), List.of(2L));
        twoOperand(h, sf, "native", NATIVE, List.of(1L, 5L), List.of(2L, 3L, 4L));
        pairs(h, sf, "E.1/two-operand", "native distinctFrom(a,b)", List.of(2L, 3L, 4L),
                (cb, outer, p) -> cb.distinctFrom(p.get("a"), p.get("b")));
        pairs(h, sf, "E.1/two-operand", "eq(a,b) baseline", List.of(1L),
                (cb, outer, p) -> cb.equal(p.get("a"), p.get("b")));
    }

    private static void twoOperand(SpikeHarness h, SessionFactory sf, String name, Form form, List<Long> rows,
            List<Long> notRows) {
        pairs(h, sf, "E.1/two-operand", name, rows, (cb, outer, p) -> form.on(cb, p.get("a"), p.get("b")));
        pairs(h, sf, "E.1/two-operand-not", "not(" + name + ")", notRows,
                (cb, outer, p) -> cb.not(form.on(cb, p.get("a"), p.get("b"))));
    }

    /** Outer {@code p} where some other row {@code q} has {@code q.b} null-safely equal to {@code p.b}. */
    private static void existsCells(SpikeHarness h, SessionFactory sf) {
        List<Long> nullSafe = List.of(1L, 3L, 4L, 5L);
        exists(h, sf, "or-form", OR, nullSafe, false);
        exists(h, sf, "bare-or", BARE, nullSafe, false);
        exists(h, sf, "native", NATIVE, nullSafe, false);
        exists(h, sf, "eq baseline", EQ, List.of(1L, 4L), false);
        exists(h, sf, "or-form", OR, List.of(2L), true);
        exists(h, sf, "bare-or", BARE, List.of(2L), true);
        exists(h, sf, "native", NATIVE, List.of(2L), true);
    }

    private static void exists(SpikeHarness h, SessionFactory sf, String name, Form form, List<Long> expected,
            boolean negated) {
        String probe = negated ? "E.1/exists-not" : "E.1/exists";
        pairs(h, sf, probe, name, expected, (cb, outer, p) -> {
            Subquery<Long> sub = outer.subquery(Long.class);
            Root<NullPair> q = sub.from(NullPair.class);
            sub.select(q.get("id")).where(form.on(cb, q.get("b"), p.get("b")), cb.notEqual(q.get("id"), p.get("id")));
            return negated ? cb.not(cb.exists(sub)) : cb.exists(sub);
        });
    }

    /** Sales whose promo code null-safely matches some promo's code (ten promos have a NULL code). */
    private static void saleExistsCells(SpikeHarness h, SessionFactory sf) {
        long[] counts = new long[4];
        Form[] forms = {OR, NATIVE, BARE, EQ};
        String[] names = {"or-form", "native", "bare-or", "eq baseline"};
        for (int i = 0; i < forms.length; i++) {
            int at = i;
            h.cell("E.1/exists-sale", names[i], () -> {
                counts[at] = saleCount(sf, (cb, outer, s) -> {
                    Subquery<Long> sub = outer.subquery(Long.class);
                    Root<Promo> p = sub.from(Promo.class);
                    sub.select(p.get("id")).where(forms[at].on(cb, p.get("code"), s.get("promoCode")));
                    return cb.exists(sub);
                });
                return "count=" + counts[at];
            });
        }
        h.cell("E.1/exists-sale", "or-form = native = bare-or = eq + NULL codes", () -> {
            long nulls = saleCount(sf, (cb, outer, s) -> cb.isNull(s.get("promoCode")));
            expect(counts[0] == counts[1] && counts[1] == counts[2] && counts[0] == counts[3] + nulls,
                    "counts or/native/bare/eq=" + counts[0] + "/" + counts[1] + "/" + counts[2] + "/" + counts[3]
                            + ", NULL codes=" + nulls);
            return "or=native=bare=" + counts[0] + ", eq=" + counts[3] + ", NULL codes=" + nulls;
        });
    }

    private static long saleCount(SessionFactory sf,
            Where<Sale> where) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            CriteriaQuery<Long> q = cb.createQuery(Long.class);
            Root<Sale> s = q.from(Sale.class);
            q.select(cb.count(s)).where(where.on(cb, q, s));
            return em.createQuery(q).getSingleResult();
        });
    }

    private static void pairs(SpikeHarness h, SessionFactory sf, String probe, String name, List<Long> expected,
            Where<NullPair> where) {
        h.cell(probe, name, () -> {
            List<Long> ids = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                CriteriaQuery<Long> q = cb.createQuery(Long.class);
                Root<NullPair> p = q.from(NullPair.class);
                q.select(p.get("id")).where(where.on(cb, q, p)).orderBy(cb.asc(p.get("id")));
                return em.createQuery(q).getResultList();
            });
            expect(ids.equals(expected), "ids=" + ids + ", expected " + expected);
            return "ids=" + ids;
        });
    }
}
