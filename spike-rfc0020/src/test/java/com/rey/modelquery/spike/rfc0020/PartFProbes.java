package com.rey.modelquery.spike.rfc0020;

import static com.rey.modelquery.spike.rfc0020.SpikeHarness.expect;
import static com.rey.modelquery.spike.rfc0020.SpikeHarness.inEm;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.BusinessDay;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Sale;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.SaleLine;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.StockSnapshot;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaPredicate;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * F.1, counting rows as {@code count(*)} (RFC 0020 Part F, R-AGG-19): equal rows for {@code count(id)},
 * {@code count(1)} and {@code count(*)} at the eligible sites, the SQL {@code HibernateCriteriaBuilder#count()}
 * renders alone and beside a filtered {@code count(1)}, and the ineligible sites (a LEFT-joined table or source) where
 * the forms differ. The statements are kept for F.1's timings and plans.
 */
final class PartFProbes {

    private PartFProbes() {}

    /** The three row-count forms, in the order the timing cells name them. */
    static final List<String> FORMS = List.of("id", "one", "star");

    private interface Count {
        Expression<Long> count(HibernateCriteriaBuilder cb, JpaFrom<?, ?> counted);
    }

    private static Count form(String name) {
        return switch (name) {
            case "id" -> (cb, f) -> cb.count(f.get("id"));
            case "one" -> (cb, f) -> cb.count(cb.literal(1));
            default -> (cb, f) -> cb.count();
        };
    }

    static void run(SpikeHarness h, SessionFactory sf) {
        groupedRead(h, sf);
        pageTotal(h, sf);
        rendering(h, sf);
        leftTable(h, sf);
        leftSource(h, sf);
        correlated(h, sf);
        composite(h, sf);
        innerVsLeft(h, sf);
        engineToday(h, sf);
    }

    /** RFC 0019's grouped read ({@code A.1/grouped}) over sales: {@code count(...)} grouped by kind. */
    private static void groupedRead(SpikeHarness h, SessionFactory sf) {
        List<List<Object>> results = new ArrayList<>();
        for (String name : FORMS) {
            h.cell("F.1/grouped", "count(" + name + ") group by kind", () -> {
                List<Object> rows = inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                    JpaRoot<Sale> s = q.from(Sale.class);
                    q.multiselect(s.get("kind"), form(name).count(cb, s)).groupBy(s.get("kind"))
                            .orderBy(cb.asc(s.get("kind")));
                    return em.createQuery(q).getResultList().stream().<Object>map(t -> List.of(t.toArray()))
                            .toList();
                });
                h.keep("F.1/grouped/" + name);
                results.add(rows);
                return "rows=" + rows;
            });
        }
        equal(h, "F.1/grouped", results);
    }

    /** The page total of an ungrouped, entity-rooted query over sale lines ({@code qty >= 3}). */
    private static void pageTotal(SpikeHarness h, SessionFactory sf) {
        List<List<Object>> results = new ArrayList<>();
        for (String name : FORMS) {
            h.cell("F.1/page-total", "count(" + name + ") over sale_line where qty >= 3", () -> {
                long count = inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
                    JpaRoot<SaleLine> l = q.from(SaleLine.class);
                    q.select(form(name).count(cb, l)).where(cb.ge(l.get("qty"), 3));
                    return em.createQuery(q).getSingleResult();
                });
                h.keep("F.1/page-total/" + name);
                results.add(List.of(count));
                return "count=" + count;
            });
        }
        equal(h, "F.1/page-total", results);
    }

    /** What {@code count()} and a filtered {@code count(1)} render on this line. */
    private static void rendering(SpikeHarness h, SessionFactory sf) {
        h.cell("F.1/render", "hcb.count() alone", () -> {
            long n = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
                q.from(Sale.class);
                return em.createQuery(q.select(cb.count())).getSingleResult();
            });
            expect(h.sql().toLowerCase().contains("count(*)"), "not count(*)");
            return "count=" + n + "; renders count(*)";
        });
        long[] filtered = new long[2];
        h.cell("F.1/render", "hcb.count(literal(1), p) filtered", () -> {
            filtered[0] = refundCount(sf, (cb, s, p) -> cb.count(cb.literal(1), p));
            String sql = h.sql().toLowerCase();
            return "count=" + filtered[0] + "; literal inlined=" + sql.contains("count(1)") + "; FILTER="
                    + sql.contains("filter (where");
        });
        h.cell("F.1/render", "hcb.count(s.id, p) filtered", () -> {
            filtered[1] = refundCount(sf, (cb, s, p) -> cb.count(s.get("id"), p));
            expect(filtered[0] == filtered[1], "count(1) filter=" + filtered[0] + ", count(id) filter=" + filtered[1]);
            return "count=" + filtered[1] + " (= count(1) filter)";
        });
        h.cell("F.1/render", "HQL count(*) filter (where ...)", () -> {
            long n = inEm(sf, em -> em.createQuery("select count(*) filter (where s.kind = 'REFUND') from Sale s",
                    Long.class).getSingleResult());
            expect(n == filtered[0], "count=" + n);
            return "count=" + n + "; FILTER=" + h.sql().toLowerCase().contains("filter (where");
        });
    }

    private interface Filtered {
        Expression<Long> count(HibernateCriteriaBuilder cb, JpaRoot<Sale> s, JpaPredicate refund);
    }

    private static long refundCount(SessionFactory sf, Filtered count) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
            JpaRoot<Sale> s = q.from(Sale.class);
            return em.createQuery(q.select(count.count(cb, s, cb.equal(s.get("kind"), "REFUND"))))
                    .getSingleResult();
        });
    }

    /**
     * A filtered count over a LEFT-joined table (sales LEFT join customer, 40% NULL): {@code count(c.id) filter} and
     * {@code count(1) filter} differ, which is why F.2 keeps the id for an ineligible count.
     */
    private static void leftTable(SpikeHarness h, SessionFactory sf) {
        h.cell("F.1/left-table", "count(c.id) filter vs count(1) filter, sale LEFT join customer", () -> {
            Tuple t = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                JpaRoot<Sale> s = q.from(Sale.class);
                Join<Sale, ?> c = s.join("customer", JoinType.LEFT);
                JpaPredicate refund = cb.equal(s.get("kind"), "REFUND");
                q.multiselect(cb.count(c.get("id"), refund), cb.count(cb.literal(1), refund));
                return em.createQuery(q).getSingleResult();
            });
            long id = t.get(0, Long.class);
            long one = t.get(1, Long.class);
            expect(id < one, "count(c.id) filter=" + id + ", count(1) filter=" + one);
            return "count(c.id) filter=" + id + " < count(1) filter=" + one + " (refunds with no customer)";
        });
    }

    /** A filtered count over a LEFT-joined source with unmatched rows: the key against {@code count(1)}. */
    private static void leftSource(SpikeHarness h, SessionFactory sf) {
        h.cell("F.1/left-source", "count(x.mq_businessDate) filter vs count(1) filter, day LEFT join sales",
                () -> {
                    Tuple t = inEm(sf, em -> {
                        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                        JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                        JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
                        JpaFrom<?, Tuple> x = PartAProbes.join(d, PartAProbes.salesCte(cb, q, null),
                                SqmJoinType.LEFT, j -> cb.equal(j.get("mq_businessDate"), d.get("businessDate")));
                        JpaPredicate counted = cb.gt(d.get("countedCash"), BigDecimal.ZERO);
                        q.multiselect(cb.count(x.get("mq_businessDate"), counted), cb.count(cb.literal(1), counted),
                                cb.count(x.get("mq_businessDate")), cb.count());
                        return em.createQuery(q).getSingleResult();
                    });
                    long key = t.get(0, Long.class);
                    long one = t.get(1, Long.class);
                    expect(key < one, "count(key) filter=" + key + ", count(1) filter=" + one);
                    return "count(key) filter=" + key + " < count(1) filter=" + one + "; unfiltered count(key)="
                            + t.get(2) + ", count(*)=" + t.get(3);
                });
    }

    /** A correlated count over the sub-select root's id, every outer day (335 with zero matches). */
    private static void correlated(SpikeHarness h, SessionFactory sf) {
        List<List<Object>> results = new ArrayList<>();
        for (String name : FORMS) {
            h.cell("F.1/correlated", "(select count(" + name + ") from sale where date = d.date) per day", () -> {
                List<Object> counts = inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                    JpaRoot<BusinessDay> d = q.from(BusinessDay.class);
                    Subquery<Long> sub = q.subquery(Long.class);
                    JpaRoot<Sale> s = (JpaRoot<Sale>) sub.from(Sale.class);
                    sub.select(form(name).count(cb, s)).where(cb.equal(s.get("businessDate"),
                            d.get("businessDate")));
                    q.multiselect(d.get("id"), sub).orderBy(cb.asc(d.get("id")));
                    return em.createQuery(q).getResultList().stream().<Object>map(t -> t.get(1)).toList();
                });
                long zero = counts.stream().filter(c -> ((Long) c) == 0L).count();
                expect(counts.size() == Rfc0020Dataset.DAYS && zero == Rfc0020Dataset.DAYS - Rfc0020Dataset.SALES_DAYS,
                        "days=" + counts.size() + ", zero=" + zero);
                results.add(counts);
                return "days=" + counts.size() + ", zero-match days=" + zero;
            });
        }
        equal(h, "F.1/correlated", results);
    }

    /** {@code count(root)} over the composite-id {@code stock_snapshot}, before ({@code count(root)}) and after. */
    private static void composite(SpikeHarness h, SessionFactory sf) {
        List<List<Object>> results = new ArrayList<>();
        String[] names = {"count(root)", "count(1)", "count(*)"};
        for (int i = 0; i < names.length; i++) {
            int at = i;
            h.cell("F.1/composite", names[i] + " over stock_snapshot", () -> {
                long n = inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
                    JpaRoot<StockSnapshot> r = q.from(StockSnapshot.class);
                    Expression<Long> count = at == 0 ? cb.count(r) : at == 1 ? cb.count(cb.literal(1)) : cb.count();
                    return em.createQuery(q.select(count)).getSingleResult();
                });
                results.add(List.of(n));
                return "count=" + n;
            });
        }
        equal(h, "F.1/composite", results);
    }

    /** {@code Agg.count(table)} over an INNER join (forms equal) and a LEFT join (forms differ), by day. */
    private static void innerVsLeft(SpikeHarness h, SessionFactory sf) {
        for (JoinType type : List.of(JoinType.INNER, JoinType.LEFT)) {
            h.cell("F.1/inner-vs-left", "count(c.id) vs count(*), sale " + type + " join customer, by day", () -> {
                List<Tuple> rows = inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    JpaCriteriaQuery<Tuple> q = cb.createTupleQuery();
                    JpaRoot<Sale> s = q.from(Sale.class);
                    Join<Sale, ?> c = s.join("customer", type);
                    Path<LocalDate> date = s.get("businessDate");
                    q.multiselect(date, cb.count(c.get("id")), cb.count()).groupBy(date).orderBy(cb.asc(date));
                    return em.createQuery(q).getResultList();
                });
                long differ = rows.stream().filter(t -> !t.get(1).equals(t.get(2))).count();
                expect(type == JoinType.INNER ? differ == 0 : differ == rows.size(), "differing days=" + differ);
                return "days=" + rows.size() + ", days where count(c.id) != count(*): " + differ;
            });
        }
    }

    public record DayLines(LocalDate businessDate, Long lines) {}

    public record Line(Long id, Integer qty) {}

    private static final TableField<SaleLine, SaleLine> LINE = TableField.root(SaleLine.class);
    private static final TableField<Sale, Sale> SALE = TableField.root(Sale.class);
    private static final OrderedColumnField<DayLines, Sale, LocalDate> DAY =
            ColumnField.of(DayLines.class, SALE, "businessDate", LocalDate.class);
    private static final AggregateField<DayLines, Long> SALES = Agg.<DayLines>count(SALE).named("lines");
    private static final OrderedColumnField<Line, SaleLine, Long> LINE_ID =
            ColumnField.of(Line.class, LINE, "id", Long.class);
    private static final OrderedColumnField<Line, SaleLine, Integer> QTY =
            ColumnField.of(Line.class, LINE, "qty", Integer.class);

    /** What model-query renders today for {@code Agg.count(root)} and a page total (R-EXE-05). */
    private static void engineToday(SpikeHarness h, SessionFactory sf) {
        h.cell("F.1/engine", "Agg.count(root) grouped by day, today", () -> {
            ModelQuery<Sale, ?, DayLines> query = ModelQuery.builder(SALE,
                            row -> new DayLines(row.get(DAY), row.get(SALES)))
                    .select(SelectSet.of(DAY, SALES)).groupBy(DAY).orderBy(DAY.asc()).build();
            List<DayLines> rows = inEm(sf, em -> ModelQueryExecutor.create(em, Sale.class,
                    ModelQueryConfig.defaults()).list(query, Limit.unlimited()));
            expect(rows.size() == Rfc0020Dataset.SALES_DAYS, "rows=" + rows.size());
            return "days=" + rows.size() + "; first " + rows.get(0);
        });
        h.cell("F.1/engine", "page total of sale lines where qty >= 3, today", () -> {
            ModelQuery<SaleLine, ?, Line> query = ModelQuery.builder(LINE,
                            row -> new Line(row.get(LINE_ID), row.get(QTY)))
                    .select(SelectSet.of(LINE_ID, QTY)).where(f -> f.gte(QTY, 3)).orderBy(LINE_ID.asc()).build();
            long total = inEm(sf, em -> ModelQueryExecutor.create(em, SaleLine.class,
                    ModelQueryConfig.defaults()).count(query));
            return "total=" + total;
        });
    }

    private static void equal(SpikeHarness h, String probe, List<List<Object>> results) {
        h.cell(probe, "all forms equal", () -> {
            expect(results.size() >= 2, "a form failed");
            for (List<Object> r : results) {
                expect(r.equals(results.get(0)), "forms differ: " + results);
            }
            return "equal over " + results.size() + " forms";
        });
    }
}
