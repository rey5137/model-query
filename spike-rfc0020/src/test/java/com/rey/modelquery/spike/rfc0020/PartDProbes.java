package com.rey.modelquery.spike.rfc0020;

import static com.rey.modelquery.spike.rfc0020.SpikeHarness.describe;
import static com.rey.modelquery.spike.rfc0020.SpikeHarness.expect;
import static com.rey.modelquery.spike.rfc0020.SpikeHarness.inEm;

import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.MoneyRow;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.RoundRow;
import com.rey.modelquery.spike.rfc0020.Rfc0020Entities.Sale;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.criteria.JpaPredicate;

/**
 * D.1 exact average and D.2 rounding (RFC 0020 Part D, R-AGG-18, R-COL-37). D.1 renders the two candidates over
 * {@code money_row}'s {@code numeric(12,2)}, {@code bigint}, {@code integer} and {@code smallint} columns, with and
 * without a per-input-type argument cast, for group 1 (exact average 5/3) and zero rows; the value Hibernate reads and
 * the JDBC column type a replay of the same SQL reads. D.2 rounds 2.345, -2.345, 2.5 and -2.5, and scales 31 to 38.
 */
final class PartDProbes {

    private PartDProbes() {}

    private static final BigDecimal FIVE_THIRDS = new BigDecimal(5);

    private interface Candidate {
        Expression<?> render(HibernateCriteriaBuilder cb, Path<? extends Number> x);
    }

    private static final String[] NAMES = {"avg (Double)", "cand1 avg typed BigDecimal", "cand1 avg(cast(x))",
        "cand2 sum(x)/count(x)", "cand2 sum(cast(x))/count(x)", "cand1 avg(sql cast(x))",
        "cand2 sum(sql cast(x))/count(x)"};

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Candidate[] CANDIDATES = {
        (cb, x) -> cb.avg((Expression) x),
        (cb, x) -> cb.functionAggregate("avg", BigDecimal.class, (JpaPredicate) null, x),
        (cb, x) -> cb.functionAggregate("avg", BigDecimal.class, (JpaPredicate) null, x.as(BigDecimal.class)),
        (cb, x) -> cb.quot(cb.sum((Expression) x), cb.count(x)),
        (cb, x) -> cb.quot(cb.sum(x.as(BigDecimal.class)), cb.count(x)),
        // Expression#as is only a Java-type cast and renders nothing; these two render a SQL cast to numeric.
        (cb, x) -> cb.functionAggregate("avg", BigDecimal.class, (JpaPredicate) null,
                cb.cast((JpaExpression<?>) x, BigDecimal.class)),
        (cb, x) -> cb.quot(cb.sum(cb.cast((JpaExpression<?>) x, BigDecimal.class)), cb.count(x)),
    };

    private static final String[] COLUMNS = {"amount", "bigVal", "intVal", "smallVal"};
    private static final String[] COLUMN_TYPES = {"numeric(12,2)", "bigint", "integer", "smallint"};

    static void run(SpikeHarness h, SessionFactory sf) {
        for (int c = 0; c < COLUMNS.length; c++) {
            for (int k = 0; k < CANDIDATES.length; k++) {
                average(h, sf, COLUMNS[c], COLUMN_TYPES[c], k, 1);
                average(h, sf, COLUMNS[c], COLUMN_TYPES[c], k, 99);
            }
        }
        nativeAverage(h);
        saleTotal(h, sf);
        rounding(h, sf);
        scales(h, sf);
        wideScales(h, sf);
    }

    /** Plan item 5: the database's own {@code avg} per input type over JDBC, no Hibernate (H2's AVG(INTEGER) type). */
    private static void nativeAverage(SpikeHarness h) {
        h.cell("D.1/native-avg", "avg(amount), avg(big_val), avg(int_val), avg(small_val) over JDBC", () -> {
            try (Connection conn = h.raw().getConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select avg(amount), avg(big_val), avg(int_val), avg(small_val) "
                         + "from money_row where grp = 1")) {
                ResultSetMetaData md = rs.getMetaData();
                rs.next();
                List<String> out = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    out.add(md.getColumnTypeName(i) + "(" + md.getPrecision(i) + "," + md.getScale(i) + ")="
                            + describe(rs.getObject(i)));
                }
                return out.toString();
            }
        });
    }

    private static void average(SpikeHarness h, SessionFactory sf, String column, String type, int k, int group) {
        String probe = group == 1 ? "D.1/avg" : "D.1/avg-zero-rows";
        h.cell(probe, type + " " + NAMES[k], () -> {
            Object value = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                CriteriaQuery<Tuple> q = cb.createTupleQuery();
                Root<MoneyRow> r = q.from(MoneyRow.class);
                q.multiselect(CANDIDATES[k].render(cb, r.get(column))).where(cb.equal(r.get("grp"), group));
                return em.createQuery(q).getSingleResult().get(0);
            });
            List<String> jdbc = h.replayColumns(h.lastStatement());
            String out = "hibernate=" + describe(value) + "; jdbc=" + jdbc;
            if (group == 99) {
                expect(value == null, "zero rows read " + describe(value) + "; " + out);
            } else {
                expect(exact(value), "not the exact 5/3 as a BigDecimal: " + out);
            }
            return out;
        });
    }

    /** A {@code BigDecimal} with at least 2 decimals equal to 5/3 at its own scale (half-up or truncated). */
    private static boolean exact(Object value) {
        if (!(value instanceof BigDecimal bd) || bd.scale() < 2) {
            return false;
        }
        BigDecimal up = FIVE_THIRDS.divide(BigDecimal.valueOf(3), bd.scale(), RoundingMode.HALF_UP);
        BigDecimal down = FIVE_THIRDS.divide(BigDecimal.valueOf(3), bd.scale(), RoundingMode.DOWN);
        return bd.compareTo(up) == 0 || bd.compareTo(down) == 0;
    }

    /** Over the 30,000 sale totals: each typed candidate against the database's own {@code avg(total)} over JDBC. */
    private static void saleTotal(SpikeHarness h, SessionFactory sf) {
        BigDecimal[] database = new BigDecimal[1];
        h.cell("D.1/sale-total", "database avg(total) over JDBC getBigDecimal", () -> {
            try (Connection conn = h.raw().getConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select avg(total) from sale")) {
                rs.next();
                database[0] = rs.getBigDecimal(1);
                return describe(database[0]);
            }
        });
        for (int k = 1; k < CANDIDATES.length; k++) {
            int at = k;
            h.cell("D.1/sale-total", NAMES[k], () -> {
                Object value = inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    CriteriaQuery<Tuple> q = cb.createTupleQuery();
                    Root<Sale> s = q.from(Sale.class);
                    q.multiselect(CANDIDATES[at].render(cb, s.get("total")));
                    return em.createQuery(q).getSingleResult().get(0);
                });
                expect(value instanceof BigDecimal, "read " + describe(value));
                BigDecimal bd = (BigDecimal) value;
                int scale = Math.min(bd.scale(), database[0].scale());
                boolean equal = bd.setScale(scale, RoundingMode.HALF_UP)
                        .compareTo(database[0].setScale(scale, RoundingMode.HALF_UP)) == 0;
                String out = describe(bd) + " vs database " + describe(database[0]) + ", equal at scale " + scale
                        + ": " + equal + (bd.compareTo(database[0]) == 0 ? " (identical)" : "");
                expect(equal, out);
                return out;
            });
        }
    }

    private static void rounding(SpikeHarness h, SessionFactory sf) {
        roundCell(h, sf, 2, List.of("2.35", "-2.35", "2.50", "-2.50"));
        roundCell(h, sf, 0, List.of("2", "-2", "3", "-3"));
        h.cell("D.2/round", "round(sum(amount)/count(amount), 2) over 5/3", () -> {
            Object value = inEm(sf, em -> {
                HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                CriteriaQuery<Tuple> q = cb.createTupleQuery();
                Root<MoneyRow> r = q.from(MoneyRow.class);
                @SuppressWarnings("unchecked")
                Expression<BigDecimal> avg = (Expression<BigDecimal>) CANDIDATES[3].render(cb, r.get("amount"));
                q.multiselect(cb.round(avg, 2)).where(cb.equal(r.get("grp"), 1));
                return em.createQuery(q).getSingleResult().get(0);
            });
            expect(value instanceof BigDecimal bd && bd.compareTo(new BigDecimal("1.67")) == 0,
                    "read " + describe(value));
            return describe(value) + "; jdbc=" + h.replayColumns(h.lastStatement());
        });
    }

    private static void roundCell(SpikeHarness h, SessionFactory sf, int scale, List<String> expected) {
        h.cell("D.2/round", "round(v, " + scale + ") over 2.345, -2.345, 2.500, -2.500", () -> {
            List<Object> values = roundValues(sf, scale, null);
            List<String> read = new ArrayList<>();
            boolean ok = true;
            for (int i = 0; i < values.size(); i++) {
                read.add(describe(values.get(i)));
                ok &= values.get(i) instanceof BigDecimal bd && bd.compareTo(new BigDecimal(expected.get(i))) == 0;
            }
            String out = read + "; jdbc first row=" + h.replayColumns(h.lastStatement());
            expect(ok, "expected half away from zero " + expected + ", read " + out);
            return out;
        });
    }

    /** {@code round(v, s)} for s = 31..38 over 2.345: the scale each vendor returns, or its error. */
    private static void scales(SpikeHarness h, SessionFactory sf) {
        for (int scale = 30; scale <= 38; scale++) {
            int s = scale;
            h.cell("D.2/scale", "round(2.345, " + s + ")", () -> {
                Object value = roundValues(sf, s, 1L).get(0);
                String out = describe(value) + "; jdbc=" + h.replayColumns(h.lastStatement());
                expect(value instanceof BigDecimal bd && bd.scale() >= Math.min(s, 3)
                        && bd.compareTo(new BigDecimal("2.345")) == 0, "read " + out);
                return out;
            });
        }
    }

    /** 1/3 to 40 decimals: a value with more decimals than MySQL's DECIMAL scale limit of 30. */
    private static final BigDecimal ONE_THIRD = BigDecimal.ONE.divide(BigDecimal.valueOf(3), 40, RoundingMode.DOWN);

    /**
     * {@code round(1/3 at 40 decimals, s)} for s = 30..38 through {@code CriteriaBuilder#round} over a bound
     * {@code BigDecimal}: PASS when the value read equals 1/3 rounded half-up to s (a vendor may pad it to the input's
     * scale); otherwise the vendor's clamp (the digits read) or its error is the data.
     */
    private static void wideScales(SpikeHarness h, SessionFactory sf) {
        for (int scale = 30; scale <= 38; scale++) {
            int s = scale;
            h.cell("D.2/scale-wide", "round(1/3 to 40 decimals, " + s + ")", () -> {
                Object value = inEm(sf, em -> {
                    HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
                    CriteriaQuery<Tuple> q = cb.createTupleQuery();
                    Root<RoundRow> r = q.from(RoundRow.class);
                    q.multiselect(cb.round(cb.literal(ONE_THIRD), s)).where(cb.equal(r.get("id"), 1L));
                    return em.createQuery(q).getSingleResult().get(0);
                });
                String out = describe(value) + "; jdbc=" + h.replayColumns(h.lastStatement());
                BigDecimal expected = ONE_THIRD.setScale(s, RoundingMode.HALF_UP);
                expect(value instanceof BigDecimal bd && bd.compareTo(expected) == 0,
                        "expected " + expected.toPlainString() + ", read " + out);
                return out;
            });
        }
    }

    private static List<Object> roundValues(SessionFactory sf, int scale, Long id) {
        return inEm(sf, em -> {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
            CriteriaQuery<Tuple> q = cb.createTupleQuery();
            Root<RoundRow> r = q.from(RoundRow.class);
            q.multiselect(cb.round(r.<BigDecimal>get("v"), scale)).orderBy(cb.asc(r.get("id")));
            if (id != null) {
                q.where(cb.equal(r.get("id"), id));
            }
            return em.createQuery(q).getResultList().stream().map(t -> t.get(0)).toList();
        });
    }
}
