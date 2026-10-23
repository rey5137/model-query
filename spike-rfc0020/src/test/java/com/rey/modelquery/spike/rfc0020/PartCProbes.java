package com.rey.modelquery.spike.rfc0020;

import static com.rey.modelquery.spike.rfc0020.SpikeHarness.expect;
import static com.rey.modelquery.spike.rfc0020.SpikeHarness.inEm;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Customer;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Sale;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaPredicate;

/**
 * C.1 and C.2, an aggregate's {@code filter} (RFC 0020 Part C, R-AGG-17). C.1 renders {@code count}, {@code sum},
 * {@code avg} with a {@code JpaPredicate}, {@code min}/{@code max} through {@code functionAggregate}, a filtered
 * {@code countDistinct} (HQL, no Criteria overload) and the engine's CASE form, over sales grouped by business date
 * with {@code kind = 'REFUND'}: the SQL (FILTER or CASE), equal rows, and the Java type read. C.2 filters over the
 * optional {@code customer} to-one (40% NULL foreign keys) beside an unfiltered grouped total.
 */
final class PartCProbes {

    private PartCProbes() {}

    /** One aggregate over {@code s} filtered by {@code p}: the native filtered form and the CASE form. */
    private interface Agg2 {
        Expression<?> render(HibernateCriteriaBuilder cb, Root<Sale> s, JpaPredicate p);
    }

    private record Rows(Map<LocalDate, Object> values, String type) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void run(SpikeHarness h, SessionFactory sf) {
        pair(h, sf, "count(id)",
                (cb, s, p) -> cb.count(s.get("id"), p),
                (cb, s, p) -> cb.count(cb.selectCase().when(p, s.get("id"))));
        pair(h, sf, "sum(total)",
                (cb, s, p) -> cb.sum(s.<BigDecimal>get("total"), p),
                (cb, s, p) -> cb.sum((Expression) cb.<BigDecimal>selectCase().when(p, s.get("total"))));
        pair(h, sf, "avg(total)",
                (cb, s, p) -> cb.avg(s.<BigDecimal>get("total"), p),
                (cb, s, p) -> cb.avg((Expression) cb.<BigDecimal>selectCase().when(p, s.get("total"))));
        pair(h, sf, "min(total) functionAggregate",
                (cb, s, p) -> cb.functionAggregate("min", BigDecimal.class, p, s.get("total")),
                (cb, s, p) -> cb.min((Expression) cb.<BigDecimal>selectCase().when(p, s.get("total"))));
        pair(h, sf, "max(total) functionAggregate",
                (cb, s, p) -> cb.functionAggregate("max", BigDecimal.class, p, s.get("total")),
                (cb, s, p) -> cb.max((Expression) cb.<BigDecimal>selectCase().when(p, s.get("total"))));
        countDistinct(h, sf);
        engine(h, sf);
        optionalPath(h, sf);
    }

    private static void pair(SpikeHarness h, SessionFactory sf, String name, Agg2 filtered, Agg2 caseForm) {
        Rows[] rows = new Rows[2];
        boolean[] filterClause = new boolean[1];
        h.cell("C.1/filter", name + " FILTER", () -> {
            rows[0] = grouped(sf, (cb, s) -> filtered.render(cb, s, refund(cb, s)));
            filterClause[0] = h.sql().toLowerCase().contains("filter (where");
            return "renders FILTER: " + filterClause[0] + "; groups=" + rows[0].values().size() + "; type="
                    + rows[0].type();
        });
        h.cell("C.1/case", name + " CASE", () -> {
            rows[1] = grouped(sf, (cb, s) -> caseForm.render(cb, s, refund(cb, s)));
            return "groups=" + rows[1].values().size() + "; type=" + rows[1].type();
        });
        h.cell("C.1/equal", name + " FILTER = CASE", () -> {
            expect(rows[0] != null && rows[1] != null, "a form failed");
            expect(rows[0].values().size() == Rfc0020Dataset.SALES_DAYS, "groups=" + rows[0].values().size());
            List<LocalDate> differ = differ(rows[0].values(), rows[1].values());
            expect(differ.isEmpty(), "groups differ: " + differ);
            return "equal over " + rows[0].values().size() + " groups; types " + rows[0].type() + " / "
                    + rows[1].type();
        });
    }

    private static JpaPredicate refund(HibernateCriteriaBuilder cb, Root<Sale> s) {
        return cb.equal(s.get("kind"), "REFUND");
    }

    /** No {@code countDistinct(Expression, JpaPredicate)} on either line: the HQL clause against the CASE form. */
    private static void countDistinct(SpikeHarness h, SessionFactory sf) {
        Map<LocalDate, Object> hql = new LinkedHashMap<>();
        Rows[] caseRows = new Rows[1];
        h.cell("C.1/filter", "countDistinct(customer.id) FILTER via HQL", () -> {
            List<Object[]> result = inEm(sf, em -> em.createQuery("select s.businessDate, count(distinct "
                    + "s.customer.id) filter (where s.kind = 'REFUND') from Sale s group by s.businessDate "
                    + "order by s.businessDate", Object[].class).getResultList());
            result.forEach(r -> hql.put((LocalDate) r[0], r[1]));
            return "Criteria: no countDistinct(Expression, JpaPredicate) on HibernateCriteriaBuilder; HQL renders "
                    + "FILTER: " + h.sql().toLowerCase().contains("filter (where") + "; type="
                    + type(hql.values());
        });
        h.cell("C.1/case", "countDistinct(customer.id) CASE", () -> {
            caseRows[0] = grouped(sf, (cb, s) -> cb.countDistinct(
                    cb.selectCase().when(refund(cb, s), s.get("customer").get("id"))));
            return "groups=" + caseRows[0].values().size() + "; type=" + caseRows[0].type();
        });
        h.cell("C.1/equal", "countDistinct(customer.id) FILTER = CASE", () -> {
            expect(!hql.isEmpty() && caseRows[0] != null, "a form failed");
            List<LocalDate> differ = differ(hql, caseRows[0].values());
            expect(differ.isEmpty(), "groups differ: " + differ);
            return "equal over " + hql.size() + " groups";
        });
    }

    /** A grouped model read through the engine: the R-AGG-13 CASE field against an {@code Agg.of} FILTER field. */
    public record DayRefunds(LocalDate businessDate, BigDecimal caseForm, BigDecimal filterForm) {}

    private static final TableField<Sale, Sale> ROOT = TableField.root(Sale.class);
    private static final OrderedColumnField<DayRefunds, Sale, LocalDate> DATE =
            ColumnField.of(DayRefunds.class, ROOT, "businessDate", LocalDate.class);
    private static final ColumnField<DayRefunds, Sale, String> KIND =
            ColumnField.of(DayRefunds.class, ROOT, "kind", String.class);
    private static final ColumnField<DayRefunds, Sale, BigDecimal> TOTAL =
            ColumnField.of(DayRefunds.class, ROOT, "total", BigDecimal.class);
    private static final AggregateField<DayRefunds, BigDecimal> CASE_FORM = Agg.sum(
            Expr.cases(DayRefunds.class, BigDecimal.class).when(f -> f.eq(KIND, "REFUND"), TOTAL).orNull())
            .named("caseForm");
    private static final AggregateField<DayRefunds, BigDecimal> FILTER_FORM = Agg.of("filterForm", BigDecimal.class,
            (ctx, cb) -> {
                HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
                @SuppressWarnings("unchecked")
                Expression<BigDecimal> sum = (Expression<BigDecimal>) (Expression<?>) hcb.sum(TOTAL.path(ctx),
                        hcb.equal(KIND.path(ctx), "REFUND"));
                return sum;
            });
    private static final ModelQuery<Sale, ?, DayRefunds> ENGINE_QUERY = ModelQuery.builder(ROOT,
                    row -> new DayRefunds(row.get(DATE), row.get(CASE_FORM), row.get(FILTER_FORM)))
            .select(SelectSet.of(DATE, CASE_FORM, FILTER_FORM))
            .groupBy(DATE)
            .orderBy(DATE.asc())
            .build();

    private static void engine(SpikeHarness h, SessionFactory sf) {
        h.cell("C.1/engine", "Agg.sum(Expr.cases(...).orNull()) vs Agg.of(hcb.sum(x, p))", () -> {
            List<DayRefunds> rows = inEm(sf, em -> ModelQueryExecutor.create(em, Sale.class,
                    ModelQueryConfig.defaults()).list(ENGINE_QUERY, Limit.unlimited()));
            expect(rows.size() == Rfc0020Dataset.SALES_DAYS, "rows=" + rows.size());
            for (DayRefunds row : rows) {
                expect(same(row.caseForm(), row.filterForm()), "differs on " + row);
            }
            DayRefunds first = rows.get(0);
            return "equal over " + rows.size() + " groups; first " + first + "; types "
                    + SpikeHarness.describe(first.caseForm()) + " / " + SpikeHarness.describe(first.filterForm());
        });
    }

    /**
     * C.2: {@code sum(total) filter (where customer.vip)} over the optional customer, beside the unfiltered grouped
     * total and row count, against a baseline without any join (no row dropped). The filter's join is an explicit
     * LEFT join, and then the implicit path Hibernate infers.
     */
    private static void optionalPath(SpikeHarness h, SessionFactory sf) {
        List<Object>[] baseline = new List[1];
        h.cell("C.2/baseline", "sum(total), count(id) per day, no join", () -> {
            baseline[0] = groupedMany(sf, (cb, s) -> List.of(cb.sum(s.<BigDecimal>get("total")), cb.count(s)));
            return "groups=" + baseline[0].size();
        });
        optional(h, sf, baseline, "LEFT join FILTER", true, false);
        optional(h, sf, baseline, "LEFT join CASE", true, true);
        optional(h, sf, baseline, "implicit path FILTER", false, false);
        optional(h, sf, baseline, "implicit path CASE", false, true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void optional(SpikeHarness h, SessionFactory sf, List<Object>[] baseline, String name,
            boolean leftJoin, boolean caseForm) {
        h.cell("C.2/optional", name, () -> {
            List<Object> rows = groupedMany(sf, (cb, s) -> {
                Path<Boolean> vip = leftJoin
                        ? s.<Sale, Customer>join("customer", JoinType.LEFT).get("vip")
                        : s.get("customer").get("vip");
                JpaPredicate p = cb.isTrue(vip);
                Expression<?> filtered = caseForm
                        ? cb.sum((Expression) cb.<BigDecimal>selectCase().when(p, s.get("total")))
                        : cb.sum(s.<BigDecimal>get("total"), p);
                return List.of(cb.sum(s.<BigDecimal>get("total")), cb.count(s), filtered);
            });
            expect(baseline[0] != null, "baseline failed");
            List<String> dropped = new ArrayList<>();
            for (int i = 0; i < baseline[0].size(); i++) {
                Object[] base = (Object[]) baseline[0].get(i);
                Object[] row = i < rows.size() ? (Object[]) rows.get(i) : null;
                if (row == null || !same(base[1], row[1]) || !same(base[2], row[2])) {
                    dropped.add(base[0] + ": " + base[1] + "/" + base[2] + " vs "
                            + (row == null ? "missing" : row[1] + "/" + row[2]));
                }
            }
            Object[] first = (Object[]) rows.get(0);
            String join = h.sql().toLowerCase().contains("left join") ? "left join"
                    : h.sql().toLowerCase().contains(" join ") ? "inner join" : "no join";
            expect(dropped.isEmpty(), join + "; unfiltered totals differ from the no-join baseline: "
                    + dropped.subList(0, Math.min(3, dropped.size())));
            return join + "; unfiltered total and count equal the baseline over " + rows.size()
                    + " groups; first day vip sum=" + SpikeHarness.describe(first[3]);
        });
    }

    private interface Select {
        Expression<?> select(HibernateCriteriaBuilder cb, Root<Sale> s);
    }

    private interface SelectMany {
        List<Expression<?>> select(HibernateCriteriaBuilder cb, Root<Sale> s);
    }

    private static Rows grouped(SessionFactory sf, Select select) {
        List<Object> rows = groupedMany(sf, (cb, s) -> List.of(select.select(cb, s)));
        Map<LocalDate, Object> values = new LinkedHashMap<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            values.put((LocalDate) r[0], r[1]);
        }
        return new Rows(values, type(values.values()));
    }

    /** Rows of {@code [businessDate, selections...]} grouped and ordered by the business date. */
    private static List<Object> groupedMany(SessionFactory sf, SelectMany select) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            CriteriaQuery<Tuple> q = cb.createTupleQuery();
            Root<Sale> s = q.from(Sale.class);
            List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
            selections.add(s.get("businessDate"));
            selections.addAll(select.select(cb, s));
            q.multiselect(selections).groupBy(s.get("businessDate")).orderBy(cb.asc(s.get("businessDate")));
            List<Object> out = new ArrayList<>();
            for (Tuple t : em.createQuery(q).getResultList()) {
                out.add(t.toArray());
            }
            return out;
        });
    }

    private static String type(Iterable<Object> values) {
        for (Object v : values) {
            if (v != null) {
                return v.getClass().getSimpleName();
            }
        }
        return "all null";
    }

    private static List<LocalDate> differ(Map<LocalDate, Object> a, Map<LocalDate, Object> b) {
        List<LocalDate> out = new ArrayList<>();
        for (Map.Entry<LocalDate, Object> e : a.entrySet()) {
            if (!same(e.getValue(), b.get(e.getKey()))) {
                out.add(e.getKey());
            }
        }
        if (!a.keySet().equals(b.keySet())) {
            out.add(null);
        }
        return out;
    }

    /** Equal values, numbers compared by value (a {@code BigDecimal} scale or a {@code Double} read do not matter). */
    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0
                    || Math.abs(x.doubleValue() - y.doubleValue()) < 1e-9 * Math.max(1, Math.abs(x.doubleValue()));
        }
        return Objects.equals(a, b);
    }
}
