package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.JoinPlan;
import com.rey.modelquery.core.KeyGroups;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.portability.internal.CallResult;
import com.rey.modelquery.portability.internal.Outcome;
import com.rey.modelquery.portability.internal.SpikeDry;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Part C's three questions on one target and one unit (bound or inline values). Each child load runs on its own
 * recording, depth first, on two placeholder keys and no parent row, through the production executor's private
 * {@code childrenOf} ({@link Engine}); a key-grouped load also runs the RFC's catch-all rule. Every expectation below
 * is what the RFC claims; a cell whose value says {@code FINDING} passes because it reproduces what the results file
 * reports against the RFC.
 */
final class Probes {
    static final QueryParam<String> STATUS_FROM = QueryParam.of("statusFrom", String.class);
    static final QueryParam<String> NAME_FROM = QueryParam.of("nameFrom", String.class);

    /** One walked child load: its path, its key type, and the call's classification (empty: no placeholder). */
    record Line(String path, Class<?> keyType, Optional<SpikeDry.Cell> cell, String note) {
        String value() {
            String type = keyType.getSimpleName();
            if (cell.isEmpty()) {
                return "key " + type + ": no placeholder (failing line); " + note;
            }
            CallResult result = cell.get().result();
            return "key " + type + ": " + result.outcome() + " " + result.detail() + "; accesses="
                    + cell.get().accesses().size() + ", last=" + last(cell.get().accesses()) + note;
        }

        Outcome outcome() {
            return cell.map(c -> c.result().outcome()).orElse(null);
        }

        String sql() {
            return cell.map(c -> c.result().detail()).orElse("");
        }
    }

    private final Cells cells;
    private final Target target;
    private final String vendor;
    private final EntityManagerFactory emf;
    private final String mode;
    private final ModelQueryConfig config = ModelQueryConfig.defaults();
    /** Each bound-mode child statement by target, path and keys, for the inline run to compare against. */
    private static final Map<String, String> BOUND_SQL = new java.util.HashMap<>();
    private int calls;
    private int inlineCompared;
    private int inlineDiffer;

    Probes(Cells cells, Target target, EntityManagerFactory emf, String mode) {
        this.cells = cells;
        this.target = target;
        this.vendor = target.vendor().name() + (mode.isEmpty() ? "" : "/" + mode);
        this.emf = emf;
        this.mode = mode;
    }

    // ---- item 1: every child load reached on placeholders, at every depth

    void item1() {
        FetchPlan<CustomerTree> tree = tree();
        parent("1", "three-level-parent", QCustomerTree.query().fetch(tree).build());
        expectAll("1", "three-level", walk("", tree, Map.of()), 4);

        FetchPlan<OrderWithBuyer> nested = FetchPlan.of(QOrderWithBuyer.ALL).join(QOrderWithBuyer.CUSTOMER_JOIN, tree);
        parent("1", "join-nested-parent", QOrderWithBuyer.query().fetch(nested).build());
        expectAll("1", "join-nested", walk("", nested, Map.of()), 4);

        FetchPlan<OrderSrcFeed> feed = FetchPlan.of(QOrderSrcFeed.ALL).child(QOrderSrcFeed.LINES,
                FetchPlan.of(QLineRow.ALL));
        // MariaDB's profile has no CTE support: the parent is refused (MQ4015), so the RFC's harness walks no child.
        parent("1", "source-root-parent", QOrderSrcFeed.query(QOrderSrc.source().build()).fetch(feed).build(),
                target.vendor() == DatabaseVendor.MARIADB ? Outcome.REFUSED : Outcome.REACHED);
        expectAll("1", "source-root", walk("", feed, Map.of()), 1);

        // Key groups: with a catch-all (verifyMissing, groupWhen), without (assumeInRange), and a group function
        // that can't take a placeholder (a lookup of known keys).
        keyGroups("verifyMissing", null, KeyGroups.by(Long.class, (Long order) -> order % 3)
                .bind(NAME_FROM, g -> "n" + g).verifyMissing(-1L), Outcome.REACHED, Outcome.REACHED);
        keyGroups("groupWhen", KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind(STATUS_FROM, g -> "s" + g).groupWhen((keys, groups) -> true, -1L).assumeInRange(), null,
                Outcome.REACHED, Outcome.REACHED);
        keyGroups("assumeInRange", KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind(STATUS_FROM, g -> "s" + g).assumeInRange(), null, Outcome.REACHED, Outcome.REACHED);
        Map<Long, String> regions = Map.of(101L, "north", 102L, "south");
        keyGroups("lookup-no-catch-all", KeyGroups.by(Long.class, regions::get)
                .bind(STATUS_FROM, g -> g).assumeInRange(), null, Outcome.REFUSED, Outcome.REFUSED);
        keyGroups("lookup-with-catch-all", KeyGroups.by(Long.class, regions::get)
                .bind(STATUS_FROM, g -> g).groupWhen((keys, groups) -> true, "all").assumeInRange(), null,
                Outcome.REACHED, Outcome.REFUSED);
    }

    private static FetchPlan<CustomerTree> tree() {
        return FetchPlan.of(QCustomerTree.ALL).child(QCustomerTree.ORDERS, FetchPlan.of(QOrderLines.ALL)
                .child(QOrderLines.LINES, FetchPlan.of(QLineRow.ALL))
                .child(QOrderLines.CUSTOMER, FetchPlan.of(QCustomerRow.ALL))
                .child(QOrderLines.BUYER, FetchPlan.of(QCustomerRow.ALL)));
    }

    /**
     * A tree whose {@code orders} load, or its {@code orders.buyer} load, carries {@code orders} or {@code buyer}
     * key groups; each grouped load runs under the RFC rule and under production's {@code childrenOf}.
     */
    private void keyGroups(String name, KeyGroups<?, ?> orders, KeyGroups<?, ?> buyer, Outcome rfcRule,
            Outcome production) {
        FetchPlan<OrderLines> level2 = FetchPlan.of(QOrderLines.ALL);
        level2 = buyer == null ? level2 : level2.child(QOrderLines.BUYER, FetchPlan.of(QCustomerRow.ALL), q -> q
                .where(f -> f.compare(QCustomerRow.NAME, Op.GTE, Expr.param(NAME_FROM))).keyGroups(buyer));
        FetchPlan<CustomerTree> plan = orders == null ? FetchPlan.of(QCustomerTree.ALL).child(QCustomerTree.ORDERS,
                level2) : FetchPlan.of(QCustomerTree.ALL).child(QCustomerTree.ORDERS, level2, q -> q
                .where(f -> f.compare(QOrderLines.STATUS, Op.GTE, Expr.param(STATUS_FROM))).keyGroups(orders));
        String grouped = orders != null ? ".orders" : ".orders.buyer";
        for (boolean rule : new boolean[] {true, false}) {
            Map<String, Line> lines = walk("", plan, Map.of(grouped, rule));
            Line line = lines.get(grouped);
            Outcome expected = rule ? rfcRule : production;
            cells.record(vendor, "1", "key-groups-" + name + (rule ? "-rfc-rule" : "-production"),
                    line.outcome() == expected, "expected " + expected + "; " + grouped + " " + line.value());
        }
    }

    // ---- item 2: placeholder key types

    void item2() {
        FetchPlan<KeyParentRow> keys = FetchPlan.of(QKeyParentRow.ALL);
        for (var child : List.of(QKeyParentRow.BY_INT, QKeyParentRow.BY_STR, QKeyParentRow.BY_UUID,
                QKeyParentRow.BY_DATE, QKeyParentRow.BY_COLOR, QKeyParentRow.BY_MONO, QKeyParentRow.BY_NOTHING,
                QKeyParentRow.BY_CODE, HandChildren.BY_BYTES)) {
            keys = keys.child(child, FetchPlan.of(QKeyChildRow.ALL));
        }
        parent("2", "key-types-parent", QKeyParentRow.query().fetch(keys).build());
        Map<String, Line> lines = walk("", keys, Map.of());
        for (String path : List.of(".byInt", ".byStr", ".byUuid", ".byDate", ".byColor", ".byMono")) {
            Line line = lines.get(path);
            cells.record(vendor, "2", "placeholder" + path, line.outcome() == Outcome.REACHED, line.value());
        }
        for (String path : List.of(".byNothing", ".byCode", ".byBytes")) {
            Line line = lines.get(path);
            cells.record(vendor, "2", "placeholder" + path, line.cell().isEmpty(), line.value());
        }
        // The types the rule has no placeholder for, given hand-made ones: is their statement reachable at all?
        Map<String, List<Object>> made = new LinkedHashMap<>();
        made.put(".byCode", List.of(new Entities.Code("a"), new Entities.Code("b")));
        made.put(".byBytes", List.of(new byte[] {0}, new byte[] {1}));
        for (var entry : made.entrySet()) {
            ChildLoad<?, ?> load = loadAt(keys, entry.getKey());
            Line line = run(entry.getKey(), load, entry.getValue(), false);
            cells.record(vendor, "2", "hand-made" + entry.getKey(), true, line.value());
        }

        FetchPlan<EmbParentRow> emb = FetchPlan.of(QEmbParentRow.ALL)
                .child(QEmbParentRow.BY_REGION, FetchPlan.of(QEmbChildRow.ALL))
                .child(HandChildren.BY_EMB_ID, FetchPlan.of(QEmbChildRow.ALL));
        Map<String, Line> embLines = walk("", emb, Map.of());
        cells.record(vendor, "2", "embedded-id-component", embLines.get(".byRegion").outcome() == Outcome.REACHED,
                embLines.get(".byRegion").value());
        cells.record(vendor, "2", "embedded-id-whole", embLines.get(".byEmbId").cell().isEmpty(),
                embLines.get(".byEmbId").value());
        Line whole = run(".byEmbId", loadAt(emb, ".byEmbId"), List.of(new Entities.EmbKey("r", 0L),
                new Entities.EmbKey("r", 1L)), false);
        cells.record(vendor, "2", "hand-made.byEmbId", true, whole.value() + tupleShape(whole.sql()));

        FetchPlan<IdcParentRow> idc = FetchPlan.of(QIdcParentRow.ALL).child(QIdcParentRow.BY_NO,
                FetchPlan.of(QIdcChildRow.ALL));
        Line idcLine = walk("", idc, Map.of()).get(".byNo");
        cells.record(vendor, "2", "id-class-component", idcLine.outcome() == Outcome.REACHED, idcLine.value());

        // Only the Java type matters: other values of the type give the same text, and a value of another type is
        // rejected (or not) before the stub.
        ChildLoad<?, ?> orders = loadAt(tree(), ".orders");
        Line zeroOne = run(".orders", orders, List.of(0L, 1L), false);
        Line other = run(".orders", orders, List.of(41L, 97L), false);
        cells.record(vendor, "2", "same-text-other-values", zeroOne.sql().equals(other.sql())
                && zeroOne.outcome() == Outcome.REACHED, "(0,1) and (41,97) give the same statement: "
                + zeroOne.sql().equals(other.sql()) + "; " + other.value());
        Line wrong = run(".orders", orders, List.of("a", "b"), false);
        cells.record(vendor, "2", "wrong-java-type", true, "String keys for a Long key: " + wrong.value());
        Line one = run(".orders", orders, List.of(0L), false);
        cells.record(vendor, "2", "one-placeholder-shape", true, "one key: " + one.sql());
    }

    // ---- item 3: refusals the child stage raises

    void item3() {
        // MQ1502 from a key group's value: raised on the child's line only when the placeholder's group is the one
        // whose value fails.
        keyGroupsRefusal("mq1502-placeholder-group", KeyGroups.by(Long.class, (Long c) -> c % 3)
                .bind(STATUS_FROM, g -> g == 0 ? null : "s").assumeInRange(), Outcome.REFUSED);
        keyGroupsRefusal("mq1502-other-group", KeyGroups.by(Long.class, (Long c) -> c % 3)
                .bind(STATUS_FROM, g -> g == 2 ? null : "s").assumeInRange(), Outcome.REACHED);
        keyGroupsRefusal("mq2607-other-key", KeyGroups.by(Long.class, (Long c) -> c == 5 ? null : c % 3)
                .bind(STATUS_FROM, g -> "s").assumeInRange(), Outcome.REACHED);
        // A to-one child by key: MQ2601 (two rows for a key) needs rows, so the stub cannot raise it.
        Line toOne = walk("", tree(), Map.of()).get(".orders.customer");
        cells.record(vendor, "3", "to-one-child-mq2601-unseen", toOne.outcome() == Outcome.REACHED, toOne.value());
        if (!mode.isEmpty()) {
            cells.record(vendor, "2", "inline-text-equals-bound", inlineCompared > 0 && inlineDiffer == 0,
                    inlineCompared + " child statements reached in both modes, " + inlineDiffer + " differ");
        }
    }

    private void keyGroupsRefusal(String name, KeyGroups<?, ?> groups, Outcome expected) {
        FetchPlan<CustomerTree> plan = FetchPlan.of(QCustomerTree.ALL).child(QCustomerTree.ORDERS,
                FetchPlan.of(QOrderLines.ALL), q -> q
                        .where(f -> f.compare(QOrderLines.STATUS, Op.GTE, Expr.param(STATUS_FROM)))
                        .keyGroups(groups));
        Line line = walk("", plan, Map.of(".orders", true)).get(".orders");
        cells.record(vendor, "3", name, line.outcome() == expected, "expected " + expected + "; " + line.value());
    }

    // ---- the harness emulation

    /** The parent call itself, as the dry run reaches it today. */
    private void parent(String item, String name, ModelQuery<?, ?, ?> q) {
        parent(item, name, q, Outcome.REACHED);
    }

    private void parent(String item, String name, ModelQuery<?, ?, ?> q, Outcome expected) {
        SpikeDry.Cell cell = SpikeDry.run(emf, target, config, name, q.rootEntity(), executor ->
                executor.list(cast(q), Limit.of(10)));
        cells.record(vendor, item, name, cell.result().outcome() == expected, cell.result().outcome() + " "
                + cell.result().detail());
    }

    private void expectAll(String item, String shape, Map<String, Line> lines, int expected) {
        boolean all = lines.size() == expected;
        for (Line line : lines.values()) {
            boolean ok = line.outcome() == Outcome.REACHED;
            all &= ok;
            cells.record(vendor, item, shape + line.path(), ok, line.value());
        }
        cells.record(vendor, item, shape + "-all", all, lines.size() + " child loads walked, expected " + expected);
    }

    /**
     * Every child load of {@code plan} and of its join plans, depth first, each on two placeholders of its key type;
     * a path in {@code rule} runs its key groups under the RFC's rule ({@code true}) or production's ({@code false}).
     */
    private Map<String, Line> walk(String path, FetchPlan<?> plan, Map<String, Boolean> rule) {
        Map<String, Line> lines = new LinkedHashMap<>();
        walk(path, plan, rule, lines);
        return lines;
    }

    private void walk(String path, FetchPlan<?> plan, Map<String, Boolean> rule, Map<String, Line> lines) {
        for (ChildLoad<?, ?> load : plan.childLoads()) {
            String at = path + "." + load.field().name();
            Class<?> type = Placeholders.boxed(load.field().key().attributeType());
            Optional<List<Object>> keys = Placeholders.of(type);
            lines.put(at, keys.isEmpty() ? new Line(at, type, Optional.empty(), "child " + load)
                    : run(at, load, keys.get(), rule.getOrDefault(at, true)));
            load.query().fetch().ifPresent(child -> walk(at, child, rule, lines));
        }
        for (JoinPlan<?, ?> join : plan.joinPlans()) {
            walk(path + "." + join.field().name(), join.plan(), rule, lines);
        }
    }

    private Line run(String at, ChildLoad<?, ?> load, List<Object> keys, boolean rfcRule) {
        Class<?> type = Placeholders.boxed(load.field().key().attributeType());
        boolean[] inline = new boolean[1];
        SpikeDry.Cell cell = SpikeDry.run(emf, target, config, at, load.query().rootEntity(), executor -> {
            inline[0] = Engine.renderOptions(executor).inlinesValues();
            if (rfcRule && load.keyGroups().isPresent()) {
                KeyGroups<?, ?> groups = load.keyGroups().get();
                Map<QueryParam<?>, Object> values = groups.catchAll()
                        .orElseGet(() -> groups.values(groups.groupOf(keys.get(0), null), null));
                Engine.oneRun(executor, load, keys, values);
            } else {
                Engine.childrenOf(executor, load, keys);
            }
            return null;
        });
        // Bound and inline probes of a target make the same calls in the same order, so the n-th call pairs up.
        String key = target + "#" + calls++ + at;
        String sql = cell.result().outcome() == Outcome.REACHED ? cell.result().detail() : null;
        String compared = "";
        if (mode.isEmpty()) {
            BOUND_SQL.put(key, String.valueOf(sql));
        } else if (sql != null && BOUND_SQL.containsKey(key)) {
            boolean same = sql.equals(BOUND_SQL.get(key));
            compared = "; same text as bound: " + same;
            inlineCompared++;
            inlineDiffer += same ? 0 : 1;
        }
        String note = (mode.isEmpty() ? "" : "; inlinesValues=" + inline[0] + compared)
                + (cell.result().outcome() == Outcome.REACHED && !SpikeDry.stoppedAtStatement(cell.thrown())
                        ? "; NOT stopped at the stub" : "");
        return new Line(at, type, Optional.of(cell), note);
    }

    private static ChildLoad<?, ?> loadAt(FetchPlan<?> plan, String path) {
        List<ChildLoad<?, ?>> found = new ArrayList<>();
        collect("", plan, path, found);
        return found.get(0);
    }

    private static void collect(String at, FetchPlan<?> plan, String path, List<ChildLoad<?, ?>> found) {
        for (ChildLoad<?, ?> load : plan.childLoads()) {
            String here = at + "." + load.field().name();
            if (here.equals(path)) {
                found.add(load);
            }
            load.query().fetch().ifPresent(child -> collect(here, child, path, found));
        }
        for (JoinPlan<?, ?> join : plan.joinPlans()) {
            collect(at + "." + join.field().name(), join.plan(), path, found);
        }
    }

    private static String last(List<String> accesses) {
        if (accesses.isEmpty()) {
            return "none";
        }
        String last = accesses.get(accesses.size() - 1);
        int colon = last.indexOf(':');
        return colon < 0 ? last : last.substring(0, colon);
    }

    private static String tupleShape(String sql) {
        String lower = sql.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(") in ((") ? "; shape: tuple IN" : lower.contains(" or ") ? "; shape: expanded OR"
                : "; shape: neither";
    }

    @SuppressWarnings("unchecked")
    private static <M> ModelQuery<Object, ?, M> cast(ModelQuery<?, ?, ?> q) {
        return (ModelQuery<Object, ?, M>) q;
    }
}
