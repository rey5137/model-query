package com.rey.modelquery.tck.portability;

import static com.rey.modelquery.jpa.spi.DatabaseVendor.H2;
import static com.rey.modelquery.jpa.spi.DatabaseVendor.MYSQL;
import static com.rey.modelquery.jpa.spi.DatabaseVendor.POSTGRESQL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.portability.internal.CallResult;
import com.rey.modelquery.portability.internal.CallResult.Statement;
import com.rey.modelquery.portability.internal.DryCall;
import com.rey.modelquery.portability.internal.DryRun;
import com.rey.modelquery.portability.internal.OfflineUnit;
import com.rey.modelquery.portability.internal.Outcome;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** AC-INS-13: the COUNT page's second statement, on each Tier-1 vendor without a container. */
class CountPagePortabilityTest {

    private static final List<Target> TARGETS = List.of(Target.of(H2, "2.5.252"), Target.of(POSTGRESQL, "17.0"),
            Target.of(MYSQL, "8.4.0"));
    private static final PageSpec PAGE = PageSpec.ofOffset(2, 3);

    record Value(Long id, Object value) {}
    record OrderRow(Long id, String status, BigDecimal total) {}
    static final class Inner {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final ColumnField<Value, OrderEntity, Long> ID =
            ColumnField.of(Value.class, ROOT, "id", Long.class);
    private static final ColumnField<Value, OrderEntity, String> STATUS =
            ColumnField.of(Value.class, ROOT, "status", String.class);
    private static final ColumnField<Inner, OrderEntity, Long> INNER_ID =
            ColumnField.of(Inner.class, ROOT, "id", Long.class);
    private static final ColumnField<Inner, OrderEntity, String> INNER_STATUS =
            ColumnField.of(Inner.class, ROOT, "status", String.class);
    private static final ColumnField<Inner, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(Inner.class, ROOT, "total", BigDecimal.class);
    private static final ExpressionField<Value, BigDecimal> SUM = Correlated.sum(Value.class,
            SubSelect.of(TOTAL).where(f -> f.lte(INNER_ID, 6L).ne(INNER_STATUS, "NEW")),
            (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));

    // Restates col.CorrelatedExecutionTest.orderedSum: its snapshot includes COUNT's real content statement.
    private static final ModelQuery.Builder<OrderEntity, Long, Value> ORDERED_SUM =
            ModelQuery.builder(ROOT, row -> new Value(row.get(ID), row.get(SUM)))
                    .select(SelectSet.of(ID, SUM)).primaryKey(PrimaryKey.of(ID))
                    .where(f -> f.lte(ID, 8L).isNotNull(SUM)).orderBy(SUM.asc(), ID.asc());

    private static final ColumnField<OrderRow, OrderEntity, Long> ORDER_ID =
            ColumnField.of(OrderRow.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> ORDER_STATUS =
            ColumnField.of(OrderRow.class, ROOT, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> ORDER_TOTAL =
            ColumnField.of(OrderRow.class, ROOT, "total", BigDecimal.class);
    // Restates pag.InlineCountTest.NEW: the committed window statement is exe-18-inline-count, statement 0.
    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> NEW =
            ModelQuery.builder(ROOT, row ->
                    new OrderRow(row.get(ORDER_ID), row.get(ORDER_STATUS), row.get(ORDER_TOTAL)))
                    .select(SelectSet.of(ORDER_ID, ORDER_STATUS, ORDER_TOTAL)).primaryKey(PrimaryKey.of(ORDER_ID))
                    .where(f -> f.eq(ORDER_STATUS, "NEW")).orderBy(ORDER_ID.asc());

    @Test
    void ac_ins_13_count_content_and_keys_equal_the_committed_statements_for_each_vendor_and_line() {
        AtomicInteger invocations = new AtomicInteger();
        var plain = ORDERED_SUM.build();
        var keys = ORDERED_SUM.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        List<CallResult> results = run(List.of(call("count", executor -> {
            invocations.incrementAndGet();
            return executor.page(plain, PAGE, CountMode.COUNT);
        }), call("keys", executor -> executor.page(keys, PAGE, CountMode.COUNT))));
        assertThat(invocations).hasValue(2 * TARGETS.size());
        assertThat(results).hasSize(4 * TARGETS.size());
        for (Target target : TARGETS) {
            List<String> expected = SqlSnapshots.expected(target.vendor(), "col-41-correlated-paging");
            assertStatements(results, "count", target, List.of(Statement.COUNT, Statement.CONTENT),
                    expected.subList(0, 2));
            assertStatements(results, "keys", target, List.of(Statement.COUNT, Statement.KEYS),
                    List.of(expected.get(0), expected.get(2)));
        }
    }

    @Test
    void ac_ins_13_inline_window_fallback_and_only_count_have_their_own_line_shapes() {
        var distinct = NEW.customize((spec, joins, query, cb, phase) -> query.distinct(true)).build();
        List<CallResult> results = run(List.of(
                call("window", executor -> executor.page(NEW.build(), PAGE, CountMode.INLINE_COUNT)),
                call("fallback", executor -> executor.page(distinct, PAGE, CountMode.INLINE_COUNT)),
                call("only", executor -> executor.page(ORDERED_SUM.build(), PAGE, CountMode.ONLY_COUNT))));
        assertThat(results).hasSize(4 * TARGETS.size());
        for (Target target : TARGETS) {
            List<CallResult> window = results(results, "window", target);
            assertThat(window).hasSize(1);
            assertThat(window.get(0).statement()).isEqualTo(Statement.CONTENT_AND_COUNT);
            assertThat(window.get(0).outcome()).isEqualTo(Outcome.REACHED);
            assertThat(SqlSnapshots.normalize(window.get(0).detail()))
                    .isEqualTo(SqlSnapshots.expected(target.vendor(), "exe-18-inline-count").get(0));
            assertThat(window.get(0).line()).startsWith("window [content+count] on ");
            List<CallResult> fallback = results(results, "fallback", target);
            assertThat(fallback).extracting(CallResult::statement).containsExactly(Statement.COUNT, Statement.CONTENT);
            assertThat(fallback).extracting(CallResult::outcome).containsOnly(Outcome.REACHED);
            assertThat(fallback.get(1).detail()).contains("select distinct ").doesNotContain(" over(");
            List<CallResult> only = results(results, "only", target);
            assertThat(only).hasSize(1);
            assertThat(only.get(0).statement()).isEqualTo(Statement.NONE);
            assertThat(only.get(0).outcome()).isEqualTo(Outcome.REACHED);
            assertThat(only.get(0).line()).startsWith("only on ");
        }
    }

    @Test
    void ac_ins_13_a_swallowed_earlier_stop_does_not_replay_the_later_page() {
        AtomicInteger invocations = new AtomicInteger();
        List<CallResult> results = run(List.of(call("earlier", executor -> {
            invocations.incrementAndGet();
            try {
                executor.count(ORDERED_SUM.build());
            } catch (RuntimeException ignored) {
                // Deliberately swallow the provider-wrapped stub stop before entering the page.
            }
            return executor.page(ORDERED_SUM.build(), PAGE, CountMode.COUNT);
        })));
        assertThat(invocations).hasValue(TARGETS.size());
        assertThat(results).hasSize(TARGETS.size());
        assertThat(results).extracting(CallResult::statement).containsOnly(Statement.NONE);
        assertThat(results).extracting(CallResult::outcome).containsOnly(Outcome.REACHED);
        assertThat(results).allSatisfy(result -> assertThat(result.line()).startsWith("earlier on "));
    }

    @Test
    void ac_ins_13_the_report_counts_both_page_lines_and_keeps_their_target_order() {
        var check = PortabilityCheck.of(classes(), ModelQueryConfig.defaults())
                .targets(TARGETS.toArray(Target[]::new))
                .call("page", OrderEntity.class, executor -> executor.page(ORDERED_SUM.build(), PAGE, CountMode.COUNT))
                .call("empty", OrderEntity.class, executor -> null);
        AssertionError failure = catchThrowableOfType(check::assertPortable, AssertionError.class);
        assertThat(failure).isNotNull();
        List<String> lines = failure.getMessage().lines().toList();
        assertThat(lines).hasSize(10);
        assertThat(lines.get(0)).isEqualTo("Not portable: 3 of 9 results failed");
        for (int i = 0; i < TARGETS.size(); i++) {
            String target = TARGETS.get(i).toString();
            assertThat(lines.get(1 + 2 * i)).contains("page [count] on " + target + ": REACHED");
            assertThat(lines.get(2 + 2 * i)).contains("page [content] on " + target + ": REACHED");
            assertThat(lines.get(7 + i)).contains("empty on " + target + ": NO_STATEMENT");
        }
    }

    private static void assertStatements(List<CallResult> results, String label, Target target,
            List<Statement> statements, List<String> sql) {
        List<CallResult> page = results(results, label, target);
        assertThat(page).extracting(CallResult::statement).containsExactlyElementsOf(statements);
        assertThat(page).extracting(CallResult::outcome).containsOnly(Outcome.REACHED);
        assertThat(page).extracting(result -> SqlSnapshots.normalize(result.detail())).containsExactlyElementsOf(sql);
        for (int i = 0; i < statements.size(); i++) {
            assertThat(page.get(i).line()).startsWith(label + " [" + statements.get(i).name().toLowerCase() + "] on ");
        }
    }

    private static List<CallResult> results(List<CallResult> results, String label, Target target) {
        return results.stream().filter(result -> result.label().equals(label) && result.target().equals(target))
                .toList();
    }

    private static DryCall call(String label, Function<ModelQueryExecutor<OrderEntity>, ?> body) {
        return DryCall.of(label, OrderEntity.class, body, false);
    }

    private static List<CallResult> run(List<DryCall> calls) {
        return DryRun.run(OfflineUnit.ofClasses(classes(), List.of(), Map.of()), TARGETS,
                ModelQueryConfig.defaults(), calls);
    }

    private static List<Class<?>> classes() {
        return OfflineBootTest.TCK_UNIT.stream().<Class<?>>map(name -> {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }
}
