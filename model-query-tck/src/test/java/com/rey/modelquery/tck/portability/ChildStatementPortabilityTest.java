package com.rey.modelquery.tck.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeyGroups;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.portability.internal.CallResult;
import com.rey.modelquery.portability.internal.DryCall;
import com.rey.modelquery.portability.internal.DryRun;
import com.rey.modelquery.portability.internal.OfflineUnit;
import com.rey.modelquery.portability.internal.Outcome;
import com.rey.modelquery.tck.portability.child.ChildEntities;
import com.rey.modelquery.tck.portability.child.KeyChildRow;
import com.rey.modelquery.tck.portability.child.KeyParentRow;
import com.rey.modelquery.tck.portability.child.QKeyChildRow;
import com.rey.modelquery.tck.portability.child.QKeyParentRow;
import com.rey.modelquery.tck.portability.child.QOrderWithTree;
import com.rey.modelquery.tck.portability.child.QTreeBuyer;
import com.rey.modelquery.tck.portability.child.QTreeCustomer;
import com.rey.modelquery.tck.portability.child.QTreeLine;
import com.rey.modelquery.tck.portability.child.QTreeOrder;
import com.rey.modelquery.tck.portability.child.QTreeOrderFeed;
import com.rey.modelquery.tck.portability.child.QTreeOrderSource;
import com.rey.modelquery.tck.portability.child.TreeCustomer;
import com.rey.modelquery.tck.portability.child.TreeOrder;
import com.rey.modelquery.tck.portability.child.TreeOrderSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AC-INS-17: a fetch plan's child statements rendered on placeholder keys after a reached parent, on H2, PostgreSQL,
 * MySQL and MariaDB without a container, with bound values and under {@code value_handling_mode=inline}.
 */
class ChildStatementPortabilityTest {

    private static final Target H2 = Target.of(DatabaseVendor.H2, "2.3.232");
    private static final Target MARIADB = Target.of(DatabaseVendor.MARIADB, "11.4");
    private static final List<Target> TARGETS = List.of(H2, Target.of(DatabaseVendor.POSTGRESQL, "16.4"),
            Target.of(DatabaseVendor.MYSQL, "8.0.36"), MARIADB);
    private static final OfflineUnit BOUND = OfflineUnit.ofClasses(ChildEntities.ALL, List.of(), Map.of());
    private static final OfflineUnit INLINE = OfflineUnit.ofClasses(ChildEntities.ALL, List.of(),
            Map.of("hibernate.criteria.value_handling_mode", "inline"));

    private static final QueryParam<String> STATUS_FROM = QueryParam.of("statusFrom", String.class);
    private static final QueryParam<String> NAME_FROM = QueryParam.of("nameFrom", String.class);

    /** Customers, their orders, and each order's lines and its customer by key and through the association. */
    private static FetchPlan<TreeCustomer> tree() {
        return FetchPlan.of(QTreeCustomer.ALL).child(QTreeCustomer.ORDERS, FetchPlan.of(QTreeOrder.ALL)
                .child(QTreeOrder.LINES, FetchPlan.of(QTreeLine.ALL))
                .child(QTreeOrder.CUSTOMER, FetchPlan.of(QTreeBuyer.ALL))
                .child(QTreeOrder.BUYER, FetchPlan.of(QTreeBuyer.ALL)));
    }

    private static final List<String> TREE_PATHS = List.of("orders", "orders.lines", "orders.customer",
            "orders.buyer");

    @Test
    void ac_ins_17_a_three_level_plan_renders_each_child_depth_first_on_two_placeholder_keys() {
        var q = QTreeCustomer.query().fetch(tree()).build();
        List<CallResult> bound = run(BOUND, list("TREE", ChildEntities.TreeCustomerEntity.class, q));
        List<CallResult> inline = run(INLINE, list("TREE", ChildEntities.TreeCustomerEntity.class, q));
        for (Target target : TARGETS) {
            List<CallResult> lines = on(bound, target);
            assertThat(lines).extracting(CallResult::child).containsExactly(concat("", TREE_PATHS));
            assertThat(lines).allSatisfy(line -> assertThat(line.outcome()).isEqualTo(Outcome.REACHED));
            assertThat(lines.subList(1, lines.size())).allSatisfy(line -> {
                assertThat(line.detail()).contains("in (?,?)");
                assertThat(line.firstStatementOnly()).isFalse();
            });
            assertThat(lines.get(2).line()).startsWith("TREE child orders.lines on " + target + ": REACHED select ")
                    .contains("tree_line");
            assertThat(lines.get(4).detail()).contains("join tree_customer"); // through the association
            // Under value_handling_mode=inline every key stays a parameter: the same text as bound (D-177).
            assertThat(on(inline, target)).extracting(CallResult::line)
                    .containsExactlyElementsOf(lines.stream().map(CallResult::line).toList());
        }
    }

    @Test
    void ac_ins_17_a_child_under_a_join_nested_model_is_walked_under_the_join_path() {
        var plan = FetchPlan.of(QOrderWithTree.ALL).join(QOrderWithTree.CUSTOMER_JOIN, tree());
        var q = QOrderWithTree.query().fetch(plan).build();
        for (OfflineUnit unit : List.of(BOUND, INLINE)) {
            List<CallResult> results = run(unit, list("NESTED", ChildEntities.TreeOrderEntity.class, q));
            for (Target target : TARGETS) {
                List<CallResult> lines = on(results, target);
                assertThat(lines).extracting(CallResult::child).containsExactly(concat("",
                        TREE_PATHS.stream().map(path -> "customer." + path).toList()));
                assertThat(lines).allSatisfy(line -> assertThat(line.outcome()).isEqualTo(Outcome.REACHED));
            }
        }
    }

    @Test
    void ac_ins_17_a_source_rooted_parent_refused_on_mariadb_walks_no_child_there() {
        var plan = FetchPlan.of(QTreeOrderFeed.ALL).child(QTreeOrderFeed.LINES, FetchPlan.of(QTreeLine.ALL));
        var q = QTreeOrderFeed.query(QTreeOrderSource.source().build()).fetch(plan).build();
        List<CallResult> results = run(BOUND, list("FEED", TreeOrderSource.class, q));
        for (Target target : TARGETS) {
            List<CallResult> lines = on(results, target);
            if (target.equals(MARIADB)) {
                assertThat(lines).singleElement().satisfies(line -> assertThat(line.line())
                        .startsWith("FEED on " + MARIADB + ": REFUSED MQ4015"));
            } else {
                assertThat(lines).extracting(CallResult::child).containsExactly("", "lines");
                assertThat(lines).allSatisfy(line -> assertThat(line.outcome()).isEqualTo(Outcome.REACHED));
                assertThat(lines.get(1).detail()).contains("in (?,?)");
            }
        }
    }

    @Test
    void ac_ins_17_key_groups_render_one_round_with_the_catch_all_or_the_first_placeholders_group() {
        // With a catch-all: verifyMissing on the to-one child through the association, groupWhen on a to-many one.
        var verifyMissing = KeyGroups.by(Long.class, (Long order) -> order % 3).bind(NAME_FROM, g -> "n" + g)
                .verifyMissing(-1L);
        var groupWhen = KeyGroups.by(Long.class, (Long customer) -> customer % 3).bind(STATUS_FROM, g -> "s" + g)
                .groupWhen((keys, groups) -> true, -1L).assumeInRange();
        // Without one: the first placeholder's group, asked with the child load as owner.
        var assumeInRange = KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind(STATUS_FROM, g -> "s" + g).assumeInRange();
        // A group function that knows only real keys takes a placeholder under a catch-all only.
        Map<Long, String> regions = Map.of(101L, "north", 102L, "south");
        var lookupWithCatchAll = KeyGroups.by(Long.class, regions::get).bind(STATUS_FROM, g -> g)
                .groupWhen((keys, groups) -> true, "all").assumeInRange();
        // A failing value of the second placeholder's group is never asked: only the first placeholder's group is.
        var otherGroupFails = KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind(STATUS_FROM, g -> g == 1 ? null : "s").assumeInRange();
        for (OfflineUnit unit : List.of(BOUND, INLINE)) {
            assertGroupedChild(unit, grouped(null, verifyMissing), "orders.buyer", "tree_customer");
            for (var groups : List.of(groupWhen, assumeInRange, lookupWithCatchAll, otherGroupFails)) {
                assertGroupedChild(unit, grouped(groups, null), "orders", "tree_order");
            }
        }
    }

    @Test
    void ac_ins_17_a_group_function_failing_on_a_placeholder_is_the_checkers_limit_and_walks_no_grandchild() {
        Map<Long, String> regions = Map.of(101L, "north", 102L, "south");
        var lookup = KeyGroups.by(Long.class, regions::get).bind(STATUS_FROM, g -> g).assumeInRange();
        var nullValue = KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind(STATUS_FROM, g -> g == 0 ? null : "s").assumeInRange();
        // The functions' own exceptions propagate unwrapped from the engine; the line keeps their class and message.
        var throwingGroup = KeyGroups.by(Long.class, (Long customer) -> regions.get(customer).toUpperCase())
                .bind(STATUS_FROM, g -> g).assumeInRange();
        var throwingValue = KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind(STATUS_FROM, g -> statusOf(g)).assumeInRange();
        Map<KeyGroups<?, ?>, String> codes = Map.of(lookup, "MQ2607", nullValue, "MQ1502", throwingGroup,
                "java.lang.NullPointerException", throwingValue, "java.lang.IllegalStateException: no status for 0");
        codes.forEach((groups, code) -> {
            List<CallResult> results = run(BOUND, list("GROUPED", ChildEntities.TreeCustomerEntity.class,
                    QTreeCustomer.query().fetch(grouped(groups, null)).build()));
            for (Target target : TARGETS) {
                List<CallResult> lines = on(results, target);
                // orders failed, so its lines and customers are not walked.
                assertThat(lines).extracting(CallResult::child).containsExactly("", "orders");
                assertThat(lines.get(1).passes()).isFalse();
                assertThat(lines.get(1).line()).startsWith("GROUPED child orders on " + target + ": HARNESS_ERROR "
                        + "the checker's limit, not the application's error: the key groups of ")
                        .contains("have no catch-all, and their group of the placeholder key 0 fails: " + code);
            }
        });
    }

    @Test
    void ac_ins_17_each_key_type_has_two_placeholders_a_one_constant_enum_one_and_the_rest_a_failing_line() {
        FetchPlan<KeyParentRow> plan = FetchPlan.of(QKeyParentRow.ALL);
        for (var child : List.of(QKeyParentRow.BY_INT, QKeyParentRow.BY_STR, QKeyParentRow.BY_UUID,
                QKeyParentRow.BY_DATE, QKeyParentRow.BY_COLOR, QKeyParentRow.BY_MONO, QKeyParentRow.BY_NOTHING,
                QKeyParentRow.BY_CODE, BY_BYTES)) {
            plan = plan.child(child, FetchPlan.of(QKeyChildRow.ALL));
        }
        var q = QKeyParentRow.query().fetch(plan).build();
        for (OfflineUnit unit : List.of(BOUND, INLINE)) {
            List<CallResult> results = run(unit, list("KEYS", ChildEntities.KeyParentEntity.class, q));
            for (Target target : TARGETS) {
                Map<String, CallResult> lines = new LinkedHashMap<>();
                on(results, target).forEach(line -> lines.put(line.child(), line));
                assertThat(lines.keySet()).containsExactly("", "byInt", "byStr", "byUuid", "byDate", "byColor",
                        "byMono", "byNothing", "byCode", "byBytes");
                for (String path : List.of("byInt", "byStr", "byUuid", "byDate", "byColor")) {
                    assertThat(lines.get(path).outcome()).as(path).isEqualTo(Outcome.REACHED);
                    assertThat(lines.get(path).detail()).as(path).contains("in (?,?)");
                }
                assertThat(lines.get("byMono").outcome()).isEqualTo(Outcome.REACHED);
                assertThat(lines.get("byMono").detail()).contains("in (?)").doesNotContain("in (?,?)");
                Map<String, Class<?>> none = Map.of("byNothing", ChildEntities.Nothing.class, "byCode",
                        ChildEntities.Code.class, "byBytes", byte[].class);
                none.forEach((path, type) -> assertThat(lines.get(path).line()).startsWith("KEYS child " + path
                        + " on " + target + ": HARNESS_ERROR the checker can't build a placeholder key of "
                        + type.getName() + " for "));
            }
        }
    }

    @Test
    void ac_ins_17_a_count_page_walks_its_children_once_after_both_statements_and_count_walks_none() {
        var q = QTreeCustomer.query().fetch(tree()).build();
        List<CallResult> page = run(BOUND, DryCall.of("PAGE", ChildEntities.TreeCustomerEntity.class,
                x -> x.page(q, PageSpec.ofOffset(0, 5), CountMode.COUNT), false));
        List<CallResult> count = run(BOUND, DryCall.of("COUNT", ChildEntities.TreeCustomerEntity.class,
                x -> x.count(q), false));
        for (Target target : TARGETS) {
            assertThat(on(page, target)).extracting(CallResult::statement, CallResult::child).containsExactly(
                    tuple(CallResult.Statement.COUNT, ""),
                    tuple(CallResult.Statement.CONTENT, ""),
                    tuple(CallResult.Statement.NONE, "orders"),
                    tuple(CallResult.Statement.NONE, "orders.lines"),
                    tuple(CallResult.Statement.NONE, "orders.customer"),
                    tuple(CallResult.Statement.NONE, "orders.buyer"));
            assertThat(on(count, target)).extracting(CallResult::child).containsExactly("");
        }
    }

    @Test
    void ac_ins_17_the_check_reports_each_child_line_under_its_call_and_fails_on_one() {
        var plan = FetchPlan.of(QKeyParentRow.ALL).child(QKeyParentRow.BY_INT, FetchPlan.of(QKeyChildRow.ALL))
                .child(QKeyParentRow.BY_CODE, FetchPlan.of(QKeyChildRow.ALL));
        var q = QKeyParentRow.query().fetch(plan).build();
        AssertionError failure = catchThrowableOfType(PortabilityCheck.of(ChildEntities.ALL,
                ModelQueryConfig.defaults()).targets(H2).call("KEYS", ChildEntities.KeyParentEntity.class,
                        x -> x.list(q, Limit.of(10)))::assertPortable, AssertionError.class);
        List<String> lines = Arrays.asList(failure.getMessage().split("\n"));
        assertThat(lines.get(0)).isEqualTo("Not portable: 1 of 3 results failed");
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("KEYS on " + H2 + ": REACHED select "));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("KEYS child byInt on " + H2 + ": REACHED select ").contains("in (?,?)"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("KEYS child byCode on " + H2 + ": HARNESS_ERROR the checker can't build a placeholder"));
    }

    // ---- helpers

    /**
     * {@link #tree()} with key groups on {@code orders} (its {@code status} bounded below by the group's value) or on
     * {@code orders.buyer} (its {@code name} bounded below).
     */
    private static FetchPlan<TreeCustomer> grouped(KeyGroups<?, ?> orders, KeyGroups<?, ?> buyer) {
        FetchPlan<TreeOrder> level2 = FetchPlan.of(QTreeOrder.ALL).child(QTreeOrder.LINES, FetchPlan.of(QTreeLine.ALL))
                .child(QTreeOrder.CUSTOMER, FetchPlan.of(QTreeBuyer.ALL));
        level2 = buyer == null ? level2.child(QTreeOrder.BUYER, FetchPlan.of(QTreeBuyer.ALL))
                : level2.child(QTreeOrder.BUYER, FetchPlan.of(QTreeBuyer.ALL), c -> c
                        .where(f -> f.compare(QTreeBuyer.NAME, Op.GTE, Expr.param(NAME_FROM))).keyGroups(buyer));
        return orders == null ? FetchPlan.of(QTreeCustomer.ALL).child(QTreeCustomer.ORDERS, level2)
                : FetchPlan.of(QTreeCustomer.ALL).child(QTreeCustomer.ORDERS, level2, c -> c
                        .where(f -> f.compare(QTreeOrder.STATUS, Op.GTE, Expr.param(STATUS_FROM))).keyGroups(orders));
    }

    /** A value function that knows only real groups and throws for any other. */
    private static String statusOf(long group) {
        if (group < 100) {
            throw new IllegalStateException("no status for " + group);
        }
        return "s" + group;
    }

    /** Every child of {@code plan} reached, the grouped one at {@code path} in one round on both placeholders. */
    private static void assertGroupedChild(OfflineUnit unit, FetchPlan<TreeCustomer> plan, String path,
            String table) {
        List<CallResult> results = run(unit, list("GROUPED", ChildEntities.TreeCustomerEntity.class,
                QTreeCustomer.query().fetch(plan).build()));
        for (Target target : TARGETS) {
            List<CallResult> lines = on(results, target);
            assertThat(lines).extracting(CallResult::child).containsExactly(concat("", TREE_PATHS));
            assertThat(lines).allSatisfy(line -> assertThat(line.outcome()).as(line.line())
                    .isEqualTo(Outcome.REACHED));
            CallResult grouped = lines.stream().filter(line -> line.child().equals(path)).findFirst().orElseThrow();
            assertThat(grouped.detail()).contains(table).contains("in (?,?)").contains(">=?");
        }
    }

    /** A hand-made {@code byte[]} child, which the processor refuses ({@code MQ3404}); {@code with} keeps the row. */
    private static final ChildField<KeyParentRow, KeyChildRow> BY_BYTES = new ChildField<>() {
        @Override
        public String name() {
            return "byBytes";
        }

        @Override
        public ColumnField<KeyParentRow, ?, ?> key() {
            return ColumnField.of(KeyParentRow.class, TableField.root(ChildEntities.KeyParentEntity.class),
                    "bytesKey", byte[].class);
        }

        @Override
        public Optional<ColumnField<KeyChildRow, ?, ?>> foreignKey() {
            return Optional.of(ColumnField.of(KeyChildRow.class, TableField.root(ChildEntities.KeyChildEntity.class),
                    "bytesKey", byte[].class));
        }

        @Override
        public Optional<TableField<?, ?>> through() {
            return Optional.empty();
        }

        @Override
        public boolean isToMany() {
            return true;
        }

        @Override
        public ModelQuery.Builder<?, ?, KeyChildRow> query() {
            return QKeyChildRow.query();
        }

        @Override
        public KeyParentRow with(KeyParentRow parent, List<KeyChildRow> children) {
            return parent;
        }
    };

    private static <E> DryCall list(String label, Class<E> root, ModelQuery<E, ?, ?> q) {
        return DryCall.of(label, root, x -> x.list(q, Limit.of(10)), false);
    }

    private static List<CallResult> run(OfflineUnit unit, DryCall call) {
        return DryRun.run(unit, TARGETS, ModelQueryConfig.defaults(), List.of(call));
    }

    private static List<CallResult> on(List<CallResult> results, Target target) {
        return results.stream().filter(result -> result.target().equals(target)).toList();
    }

    private static String[] concat(String first, List<String> rest) {
        return Stream.concat(Stream.of(first), rest.stream())
                .toArray(String[]::new);
    }
}
