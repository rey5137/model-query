package com.rey.modelquery.spike.rfc0024;

import static com.rey.modelquery.spike.rfc0024.Harness.equal;
import static com.rey.modelquery.spike.rfc0024.Harness.expect;
import static com.rey.modelquery.spike.rfc0024.Harness.row;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.SpikeCore;
import com.rey.modelquery.spike.rfc0024.Entities.SpCustomer;
import com.rey.modelquery.spike.rfc0024.Entities.SpOrder;
import com.rey.modelquery.spike.rfc0024.PartA.QBuyer;
import com.rey.modelquery.spike.rfc0024.PartB.BuyerB;
import com.rey.modelquery.spike.rfc0024.PartB.CustomerTotals;
import com.rey.modelquery.spike.rfc0024.PartB.OrderView;
import com.rey.modelquery.spike.rfc0024.PartB.QBuyerB;
import com.rey.modelquery.spike.rfc0024.PartB.QCustomerTotals;
import com.rey.modelquery.spike.rfc0024.PartB.QOrderSpend;
import com.rey.modelquery.spike.rfc0024.PartB.QOrderView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.hibernate.SessionFactory;

/** The four items of RFC 0024 §Spike plan. */
final class Probes {

    private static final String TOTALS = "(select customer_id, sum(amount) spent from sp_payment group by customer_id)";
    private static final String TOTALS_GE2 = "(select customer_id, sum(amount) spent from sp_payment "
            + "where amount >= 2 group by customer_id)";

    private final Harness h;
    private final SessionFactory sf;

    Probes(Harness harness, SessionFactory sessions) {
        this.h = harness;
        this.sf = sessions;
    }

    // ---------------------------------------------------------------- item 1: Part A presence

    void item1() {
        h.cell("1", "baseline-presentBy-on-entity-join-root", () -> {
            var join = new PartA(JoinKind.LEFT).buyer;
            return refused(MqCode.MQ1104, () -> join.root().presentBy(QBuyer.KEY));
        });
        h.cell("1", "emulated-presentBy-keeps-name", () -> {
            PartA a = new PartA(JoinKind.LEFT);
            expect("buyer".equals(SpikeCore.joinName(a.buyerTable)), "name lost");
            expect(SpikeCore.isJoinedEntity(a.buyerTable), "not a joined-entity root");
            expect(a.buyerTable.equals(a.buyer.root()), "join identity changed");
            expect(SpikeCore.presenceKey(a.buyerTable) == QBuyer.KEY, "presence key lost");
            return "name=buyer, joined-entity, equal to BUYER.root(), presenceKey=QBuyer.KEY; table propertyPath='"
                    + SpikeCore.propertyPath(a.buyerTable) + "' (RFC: 'buyer'; not emulated); buyer name column "
                    + a.buyerName.identityPath() + " has propertyPath '" + SpikeCore.propertyPath(a.buyerName)
                    + "'";
        });
        for (JoinKind kind : JoinKind.values()) {
            PartA a = new PartA(kind);
            String k = kind.name().toLowerCase(java.util.Locale.ROOT);
            String join = kind == JoinKind.LEFT ? "left join" : "join";
            h.cell("1", k + "-rows", () -> {
                var rows = h.list(sf, SpOrder.class, a.query().select(a.all).orderBy(a.id.asc()).build());
                h.sql("item1-" + k + "-rows");
                equal(text(rows, r -> r.buyer().map(b -> row(r.id(), r.customerId(), b.id(), b.name(), b.email()))
                        .orElse(row(r.id(), r.customerId(), null, null, null))),
                        h.nativeRows("select o.id, o.customer_id, c.id, c.name, c.email from sp_order o " + join
                                + " sp_customer c on c.id = o.customer_id order by o.id"));
                var byId = new java.util.HashMap<Long, PartA.OrderWithBuyer>();
                rows.forEach(r -> byId.put(r.id(), r));
                if (kind == JoinKind.LEFT) {
                    expect(byId.get(2L).buyer().isEmpty(), "order 2 (customer 999, a miss) must be empty");
                    expect(byId.get(3L).buyer().isEmpty(), "order 3 (no customer) must be empty");
                } else {
                    expect(!byId.containsKey(2L) && !byId.containsKey(3L), "INNER kept a miss");
                }
                var nullBuyer = byId.get(4L).buyer().orElseThrow(() -> new Harness.Mismatch("order 4 empty"));
                expect(nullBuyer.name() == null && nullBuyer.email() == null, "order 4 buyer has values");
                return "rows=" + rows.size() + "; order 4 present with name/email NULL; misses empty="
                        + (kind == JoinKind.LEFT) + "; count=" + count(a.query().select(a.all).build());
            });
            h.cell("1", k + "-columnset-without-key", () -> {
                var narrowed = a.query().select(SelectSet.of(a.id, a.buyerName)).orderBy(a.id.asc()).build();
                var rows = h.list(sf, SpOrder.class, narrowed);
                h.sql("item1-" + k + "-narrowed");
                String sql = h.lastSql();
                equal(text(rows, r -> row(r.id(), r.buyer().isPresent(), r.buyer().map(PartA.Buyer::name)
                                .orElse(null))),
                        h.nativeRows("select o.id, case when c.id is null then 'false' else 'true' end, c.name "
                                + "from sp_order o " + join + " sp_customer c on c.id = o.customer_id order by o.id"));
                expect(rows.stream().filter(r -> r.id() == 4L).allMatch(r -> r.buyer().isPresent()),
                        "order 4 must be present: the key was not selected");
                return "rows=" + rows.size() + "; key selected though not asked; selections=" + selections(sql);
            });
        }
    }

    // ---------------------------------------------------------------- item 2: Part B re-rooting

    private static final String ORDER_VIEW_NATIVE = "select o.id, c.id, c.name, c.region_id, cr.name, ct.spent, "
            + "r.id, r.name, r.region_id, rr.name, rt.spent from sp_order o "
            + "left join sp_customer c on c.id = o.customer_id "
            + "left join sp_customer r on r.id = o.referrer_id "
            + "left join %s ct on ct.customer_id = c.id left join %s rt on rt.customer_id = r.id "
            + "left join sp_region cr on cr.id = c.region_id left join sp_region rr on rr.id = r.region_id "
            + "order by o.id";

    void item2() {
        SourceQuery<CustomerTotals> totals = QCustomerTotals.source().build();
        h.cell("2", "nested-model-standalone", () -> {
            var rows = h.list(sf, SpCustomer.class, QBuyerB.query(totals).select(QBuyerB.ALL)
                    .orderBy(QBuyerB.ID.asc()).build());
            equal(text(rows, b -> row(b.id(), b.name(), b.regionId(), b.regionName(), b.spent())),
                    h.nativeRows("select c.id, c.name, c.region_id, r.name, t.spent from sp_customer c left join "
                            + "sp_region r on r.id = c.region_id left join " + TOTALS + " t on t.customer_id = c.id "
                            + "order by c.id"));
            return "rows=" + rows.size();
        });
        h.cell("2", "today-row-scoped-hazard", () -> {
            var rows = h.list(sf, SpOrder.class, QOrderView.query(QOrderView.TODAY, totals, totals)
                    .select(QOrderView.ALL).orderBy(QOrderView.ID.asc()).build());
            int wrong = 0;
            String sample = null;
            for (OrderView view : rows) {
                for (BuyerB b : List.of(view.customer(), view.referrer()).stream().flatMap(Optional::stream)
                        .toList()) {
                    if (b.regionName() != null && b.regionName().equals(b.name())) {
                        wrong++;
                        if (sample == null) {
                            sample = "order " + view.id() + " buyer " + b.id() + " regionName='" + b.regionName()
                                    + "' (its own name; region is " + b.regionId() + ") spent=" + b.spent();
                        }
                    }
                }
            }
            expect(wrong > 0, "the hazard did not reproduce: today's scoped row did not read the root's name");
            return "INV-5 hazard reproduced: " + wrong + " nested values read the nested root's own `name` "
                    + "instead of the region's; e.g. " + sample;
        });
        h.cell("2", "emulated-scoped-two-fields-same-type", () -> {
            var rows = h.list(sf, SpOrder.class, QOrderView.query(totals, totals).select(QOrderView.ALL)
                    .orderBy(QOrderView.ID.asc()).build());
            h.sql("item2-order-view");
            equal(text(rows, Probes::orderView), h.nativeRows(ORDER_VIEW_NATIVE.formatted(TOTALS, TOTALS)));
            var nine = rows.stream().filter(v -> v.id() == 9L).findFirst().orElseThrow();
            expect(nine.customer().equals(nine.referrer()), "order 9 has one person twice");
            var one = rows.stream().filter(v -> v.id() == 1L).findFirst().orElseThrow();
            expect("North".equals(one.customer().orElseThrow().regionName())
                    && "South".equals(one.referrer().orElseThrow().regionName()), "order 1 regions crossed");
            return "rows=" + rows.size() + "; each @Join field reads its own region and totals; order 1 customer "
                    + "North/12.5, referrer South/3.25";
        });
        String narrowedNative = "select o.id, case when c.id is null then 'false' else 'true' end, null, cr.name, "
                + "ct.spent from sp_order o left join sp_customer c on c.id = o.customer_id left join "
                + TOTALS + " ct on ct.customer_id = c.id left join sp_region cr on cr.id = c.region_id order by o.id";
        Function<OrderView, String> narrowedText = v -> row(v.id(), v.customer().isPresent(),
                v.customer().map(BuyerB::name).orElse(null), v.customer().map(BuyerB::regionName).orElse(null),
                v.customer().map(BuyerB::spent).orElse(null));
        h.cell("2", "columnset-only-nested-joined-columns-loses-presence", () -> {
            var narrowed = QOrderView.query(totals, totals)
                    .select(SelectSet.of(QOrderView.ID, QOrderView.CUSTOMER_REGION_NAME, QOrderView.CUSTOMER_SPENT))
                    .orderBy(QOrderView.ID.asc()).build();
            var rows = h.list(sf, SpOrder.class, narrowed);
            h.sql("item2-narrowed-joined-only");
            String selected = selections(h.lastSql());
            var got = text(rows, narrowedText);
            var right = h.nativeRows(narrowedNative);
            int lost = 0;
            for (int i = 0; i < got.size(); i++) {
                lost += got.get(i).startsWith(rows.get(i).id() + "|false") && right.get(i).contains("|true|") ? 1 : 0;
            }
            expect(lost > 0, "the presence gap did not reproduce");
            return "FINDING: selecting only customer.regionName and customer.spent adds no presence key (they sit "
                    + "on customer_region / customer_totals, not under the presentBy join), so " + lost + "/"
                    + rows.size() + " present customers read Optional.empty(); selections=" + selected;
        });
        h.cell("2", "columnset-only-nested-joined-columns-with-scope-key", () -> {
            var narrowed = QOrderView.query(totals, totals)
                    .select(SelectSet.of(QOrderView.ID, QOrderView.CUSTOMER_REGION_NAME, QOrderView.CUSTOMER_SPENT,
                            QOrderView.CUSTOMER_ID))
                    .orderBy(QOrderView.ID.asc()).build();
            var rows = h.list(sf, SpOrder.class, narrowed);
            equal(text(rows, narrowedText), h.nativeRows(narrowedNative));
            return "rows=" + rows.size() + "; with the scope's key added (what the engine must do for a column on "
                    + "a mapped joined root) every row matches";
        });
        h.cell("2", "paths-of-prefixed-columns", () -> {
            var paths = new java.util.LinkedHashMap<String, String>();
            for (var column : List.of(QOrderView.CUSTOMER_NAME, QOrderView.CUSTOMER_REGION_NAME,
                    QOrderView.REFERRER_REGION_NAME, QOrderView.CUSTOMER_SPENT, QOrderView.REFERRER_SPENT)) {
                paths.put(column.identityPath(), SpikeCore.propertyPath(column));
            }
            return "identityPath -> propertyPath: " + paths;
        });
        h.cell("2", "broken-map-missing-entry", () -> {
            var missing = SpikeCore.nameMap(QOrderView.CUSTOMER_TABLE, QBuyerB.TOTALS, QOrderView.CUSTOMER_TOTALS);
            RowMapper<OrderView> mapper = r -> new OrderView(r.get(QOrderView.ID),
                    QOrderView.nested(SpikeCore.scoped(r, missing)), Optional.empty());
            try {
                var rows = h.list(sf, SpOrder.class, QOrderView.query(mapper, totals, totals)
                        .select(QOrderView.ALL).orderBy(QOrderView.ID.asc()).build());
                throw new Harness.Mismatch("read without failing: " + text(rows, Probes::orderView));
            } catch (IllegalStateException expected) {
                return "loud: " + expected.getMessage();
            }
        });
        h.cell("2", "broken-map-swapped-prefix", () -> {
            try {
                SpikeCore.nameMap(QOrderView.CUSTOMER_TABLE, QBuyerB.REGION, QOrderView.REFERRER_REGION);
                throw new Harness.Mismatch("a referrer join mapped under the customer scope was accepted");
            } catch (IllegalStateException expected) {
                return "loud at definition: " + expected.getMessage();
            }
        });
        h.cell("2", "broken-map-wrong-kind", () -> {
            try {
                SpikeCore.nameMap(QOrderView.CUSTOMER_TABLE, QBuyerB.REGION, QOrderView.CUSTOMER_TOTALS);
                throw new Harness.Mismatch("an entity join mapped to a source join was accepted");
            } catch (IllegalStateException expected) {
                return "loud at definition: " + expected.getMessage();
            }
        });
    }

    private static String orderView(OrderView v) {
        var cells = new ArrayList<Object>();
        cells.add(v.id());
        for (Optional<BuyerB> b : List.of(v.customer(), v.referrer())) {
            cells.add(b.map(BuyerB::id).orElse(null));
            cells.add(b.map(BuyerB::name).orElse(null));
            cells.add(b.map(BuyerB::regionId).orElse(null));
            cells.add(b.map(BuyerB::regionName).orElse(null));
            cells.add(b.map(BuyerB::spent).orElse(null));
        }
        return row(cells.toArray());
    }

    // ---------------------------------------------------------------- item 3: Part B sources

    void item3() {
        SourceQuery<CustomerTotals> all = QCustomerTotals.source().build();
        SourceQuery<CustomerTotals> ge2 = QCustomerTotals.source()
                .where(f -> f.gte(QCustomerTotals.AMOUNT, new BigDecimal("2.00"))).build();
        String expectedSql = ORDER_VIEW_NATIVE.formatted(TOTALS, TOTALS_GE2);
        h.cell("3", "two-sources-one-type-two-prefixes", () -> {
            var rows = h.list(sf, SpOrder.class, QOrderView.query(all, ge2).select(QOrderView.ALL)
                    .orderBy(QOrderView.ID.asc()).build());
            h.sql("item3-two-sources");
            equal(text(rows, Probes::orderView), h.nativeRows(expectedSql));
            var binds = h.statements().get(0).binds();
            expect(binds.size() == 1 && new BigDecimal("2.00").compareTo((BigDecimal) binds.get(0)) == 0,
                    "the referrer source's 2.00 must bind once: " + binds);
            return "rows=" + rows.size() + "; customer totals over all payments, referrer totals over >= 2.00; "
                    + "binds=" + binds;
        });
        h.cell("3", "swapped-source-parameters", () -> {
            var rows = h.list(sf, SpOrder.class, QOrderView.query(ge2, all).select(QOrderView.ALL)
                    .orderBy(QOrderView.ID.asc()).build());
            var right = h.nativeRows(expectedSql);
            var got = text(rows, Probes::orderView);
            int differ = 0;
            for (int i = 0; i < got.size(); i++) {
                differ += got.get(i).equals(right.get(i)) ? 0 : 1;
            }
            equal(got, h.nativeRows(ORDER_VIEW_NATIVE.formatted(TOTALS_GE2, TOTALS)));
            return "silent, as D-189 says of two joins of one source today: " + differ + "/" + got.size()
                    + " rows read the other prefix's totals; no error";
        });
        h.cell("3", "keyset-paging-over-outer", () -> {
            var query = QOrderView.query(all, ge2).select(QOrderView.ALL).orderBy(QOrderView.ID.asc()).keyset()
                    .build();
            int pages = keysetPages(query, 5, Probes::orderView, expectedSql);
            return "pages=" + pages + " of 5; concatenated = native";
        });
        h.cell("3", "keyset-by-nested-name-no-null-order", () -> refused(MqCode.MQ2202, () -> {
            // Every page is read: where the NULL names land depends on the vendor's default null order.
            var query = QOrderView.query(all, ge2).select(QOrderView.ALL)
                    .orderBy(QOrderView.CUSTOMER_NAME.asc(), QOrderView.ID.asc()).keyset().build();
            keysetPagesAgainst(query, 4, Probes::orderView, List.of());
        }));
        for (var order : List.of(QOrderView.CUSTOMER_NAME, QOrderView.CUSTOMER_REGION_NAME,
                QOrderView.REFERRER_SPENT)) {
            h.cell("3", "keyset-by-" + order.identityPath() + "-nullsLast", () -> {
                var listed = text(h.list(sf, SpOrder.class, QOrderView.query(all, ge2).select(QOrderView.ALL)
                        .orderBy(order.asc().nullsLast(), QOrderView.ID.asc()).build()), Probes::orderView);
                var query = QOrderView.query(all, ge2).select(QOrderView.ALL)
                        .orderBy(order.asc().nullsLast(), QOrderView.ID.asc()).keyset().build();
                int pages = keysetPagesAgainst(query, 4, Probes::orderView, listed);
                return "pages=" + pages + " of 4 ordered by " + order.identityPath()
                        + " nullsLast, id; concatenated = list";
            });
        }
        h.cell("3", "grouped-count-through-both", () -> {
            var grouped = QOrderSpend.query(all, ge2).select(QOrderSpend.ALL)
                    .orderBy(QOrderSpend.CUSTOMER_SPENT.asc(), QOrderSpend.REFERRER_SPENT.asc()).build();
            var groups = h.list(sf, SpOrder.class, grouped);
            String viaRow = "select ct.spent, rt.spent, count(*) from sp_order o "
                    + "left join sp_customer c on c.id = o.customer_id left join sp_customer r on r.id = o.referrer_id "
                    + "left join " + TOTALS + " ct on ct.customer_id = c.id "
                    + "left join " + TOTALS_GE2 + " rt on rt.customer_id = r.id group by ct.spent, rt.spent";
            String viaFk = "select ct.spent, rt.spent, count(*) from sp_order o "
                    + "left join " + TOTALS + " ct on ct.customer_id = o.customer_id "
                    + "left join " + TOTALS_GE2 + " rt on rt.customer_id = o.referrer_id group by ct.spent, rt.spent";
            var byRow = sorted(h.nativeRows(viaRow));
            var fk = sorted(h.nativeRows(viaFk));
            var got = sorted(text(groups, g -> row(g.customerSpent(), g.referrerSpent(), g.orders())));
            h.sql("item3-grouped-list");
            expect(got.equals(byRow) || got.equals(fk), "groups match neither reading:\n  engine " + got
                    + "\n  row " + byRow + "\n  fk " + fk);
            String reading = got.equals(byRow) ? "the joined customer's id" : "FINDING: the FK column o.customer_id "
                    + "(Hibernate shortens customer.id), not the joined row: a dangling key (999) still matches its "
                    + "totals; joined-row reading differs: " + byRow;
            var expected = got;
            long counted = count(grouped);
            h.sql("item3-grouped-count");
            String countSql = h.lastSql();
            expect(counted == expected.size(), "count " + counted + " != groups " + expected.size());
            return "groups=" + groups.size() + "; count=" + counted + "; count statement "
                    + (selections(countSql).toLowerCase(java.util.Locale.ROOT).contains("count(")
                    ? "counts in SQL" : "reads the groups (client-side, D-192)") + "; anchors read " + reading;
        });
    }

    // ---------------------------------------------------------------- item 4: Part C chains

    void item4() {
        SpikeCore.chains(false);
        h.cell("4", "baseline-chain-refused", () -> {
            PartC c = new PartC(List.of(JoinKind.LEFT, JoinKind.LEFT));
            return refused(MqCode.MQ1911, () -> c.query(false).select(c.all).build());
        });
        SpikeCore.chains(true);
        try {
            List<List<JoinKind>> chains = List.of(
                    List.of(JoinKind.LEFT, JoinKind.LEFT), List.of(JoinKind.LEFT, JoinKind.INNER),
                    List.of(JoinKind.INNER, JoinKind.LEFT), List.of(JoinKind.INNER, JoinKind.INNER),
                    List.of(JoinKind.LEFT, JoinKind.LEFT, JoinKind.LEFT),
                    List.of(JoinKind.INNER, JoinKind.INNER, JoinKind.INNER),
                    List.of(JoinKind.LEFT, JoinKind.LEFT, JoinKind.INNER),
                    List.of(JoinKind.LEFT, JoinKind.INNER, JoinKind.LEFT),
                    List.of(JoinKind.INNER, JoinKind.LEFT, JoinKind.LEFT));
            for (List<JoinKind> kinds : chains) {
                PartC c = new PartC(kinds);
                h.cell("4", "chain-" + c.label(), () -> chain(c, c.query(false), null, "chain-" + c.label()));
            }
            PartC lll = new PartC(List.of(JoinKind.LEFT, JoinKind.LEFT, JoinKind.LEFT));
            h.cell("4", "chain-LLL-reversed-registration", () -> refusedOr(MqCode.MQ1003, () -> {
                var rows = h.list(sf, SpOrder.class, lll.query(true).select(lll.all).orderBy(lll.id.asc()).build());
                equal(text(rows, lll::text), h.nativeRows(lll.nativeSql(null)));
                return "renders correctly in reverse registration";
            }));
            h.cell("4", "chain-LLL-joinFilter-on-first", () -> chain(lll,
                    lll.query(false).joinFilter(lll.customer, f -> f.eq(lll.customerName, "Ann")), "c.name = 'Ann'",
                    "chain-LLL-filtered"));
            PartC lil = new PartC(List.of(JoinKind.LEFT, JoinKind.INNER, JoinKind.LEFT));
            h.cell("4", "chain-LIL-joinFilter-on-first", () -> refusedOr(MqCode.MQ1912, () -> chain(lil,
                    lil.query(false).joinFilter(lil.customer, f -> f.eq(lil.customerName, "Ann")),
                    "c.name = 'Ann'", "chain-LIL-filtered")));
            h.cell("4", "chain-LLL-keyset-pages", () -> {
                var query = lll.query(false).select(lll.all).orderBy(lll.id.asc()).keyset().build();
                int pages = keysetPages(query, 4, lll::text, lll.nativeSql(null));
                return "pages=" + pages + " of 4; concatenated = native";
            });
            h.cell("4", "combined-partA-over-partB-over-chain", () -> {
                var query = PartC.Combined.query().select(PartC.Combined.ALL).orderBy(PartC.Combined.ID.asc())
                        .build();
                var rows = h.list(sf, SpOrder.class, query);
                h.sql("item4-combined");
                equal(text(rows, r -> r.buyer().map(b -> row(r.id(), b.id(), b.name(), b.regionName()))
                                .orElse(row(r.id(), null, null, null))),
                        h.nativeRows("select o.id, c.id, c.name, r.name from sp_order o left join sp_customer c "
                                + "on c.id = o.customer_id left join sp_region r on r.id = c.region_id order by o.id"));
                Harness.joinOrder(h.lastSql(), "sp_customer", "sp_region");
                return "rows=" + rows.size() + "; @Join(entityJoin) buyer with its own region join reads "
                        + "through buyer_region; count=" + count(query);
            });
        } finally {
            SpikeCore.chains(false);
        }
    }

    private String chain(PartC c, ModelQuery.Builder<SpOrder, Long, PartC.Chain> builder, String filter,
            String name) throws Exception {
        var query = builder.select(c.all).orderBy(c.id.asc()).build();
        var rows = h.list(sf, SpOrder.class, query);
        h.sql("item4-" + name);
        String sql = h.lastSql();
        equal(text(rows, c::text), h.nativeRows(c.nativeSql(filter)));
        if (c.country != null) {
            Harness.joinOrder(sql, "sp_customer", "sp_region", "sp_country");
        } else {
            Harness.joinOrder(sql, "sp_customer", "sp_region");
        }
        long counted = count(query);
        h.sql("item4-" + name + "-count");
        expect(counted == rows.size(), "count " + counted + " != rows " + rows.size());
        return "rows=" + rows.size() + "; count=" + counted + "; joins in creation order; " + joins(sql);
    }

    // ---------------------------------------------------------------- helpers

    private long count(ModelQuery<SpOrder, ?, ?> query) {
        return h.run(sf, SpOrder.class, executor -> executor.count(query));
    }

    private <M> int keysetPages(ModelQuery<SpOrder, ?, M> query, int size, Function<M, String> text, String sql)
            throws Exception {
        return keysetPagesAgainst(query, size, text, h.nativeRows(sql));
    }

    private <M> int keysetPagesAgainst(ModelQuery<SpOrder, ?, M> query, int size, Function<M, String> text,
            List<String> expected) {
        var all = new ArrayList<String>();
        int pages = 0;
        KeysetSpec spec = KeysetSpec.first(size);
        while (true) {
            KeysetSpec current = spec;
            KeysetSlice<M> slice = h.run(sf, SpOrder.class, executor -> executor.page(query, current));
            pages++;
            slice.content().forEach(m -> all.add(text.apply(m)));
            if (!slice.hasNext() || pages > 20) {
                break;
            }
            spec = KeysetSpec.after(slice.nextCursor().orElseThrow(), size);
        }
        equal(all, expected);
        return pages;
    }

    private static List<String> sorted(List<String> rows) {
        var copy = new ArrayList<>(rows);
        java.util.Collections.sort(copy);
        return copy;
    }

    private static <T> List<String> text(List<T> rows, Function<T, String> text) {
        return rows.stream().map(text).toList();
    }

    /** The ON clauses of the captured SQL, for the record. */
    private static String joins(String sql) {
        String lower = sql.toLowerCase(java.util.Locale.ROOT);
        int from = lower.indexOf(" from ");
        int end = lower.indexOf(" order by ");
        return sql.substring(from + 1, end < 0 ? sql.length() : end).replaceAll("\\s+", " ");
    }

    /** The outer select list; a leading {@code with} clause (PostgreSQL, MySQL) is skipped. */
    private static String selections(String sql) {
        String lower = sql.toLowerCase(java.util.Locale.ROOT);
        int start = lower.startsWith("with") ? lower.lastIndexOf(") select ") + 2 : lower.indexOf("select");
        return sql.substring(start + 6, lower.indexOf(" from ", start)).replaceAll("\\s+", " ").trim();
    }

    private static String refused(MqCode code, Runnable body) {
        try {
            body.run();
        } catch (ModelQueryException e) {
            expect(e.code() == code, "expected " + code + ", got " + e.code() + ": " + e.getMessage());
            return "refused " + code + ": " + e.getMessage();
        }
        throw new Harness.Mismatch("not refused; expected " + code);
    }

    /**
     * Runs {@code body}, which checks its rows; a refusal with {@code code} ({@code null}: any code) is the other
     * acceptable result, recorded with its message. Any other refusal fails.
     */
    private static String refusedOr(MqCode code, Harness.Body body) throws Exception {
        try {
            return body.run();
        } catch (ModelQueryException e) {
            expect(code == null || e.code() == code, "expected " + code + ", got " + e.code() + ": "
                    + e.getMessage());
            return "refused " + e.code() + ": " + e.getMessage();
        }
    }
}
