package com.rey.modelquery.tck.agg;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.sql.DataSource;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code Agg}, {@code AggregateField}, {@code groupBy}, {@code having} and the grouped-query rules (api/13). */
class AggregatesTest {

    // ---- order items, every function over one single-group query

    record ItemTotals(Map<AggregateField<ItemTotals, ?>, Object> values) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final TableField<OrderItemEntity, OrderEntity> ITEM_ORDER = TableField.join(ITEMS, "order", INNER);

    private static final ColumnField<ItemTotals, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemTotals.class, ITEMS, "id", Long.class);
    private static final ColumnField<ItemTotals, OrderItemEntity, String> PRODUCT =
            ColumnField.of(ItemTotals.class, ITEMS, "productCode", String.class);
    private static final ColumnField<ItemTotals, OrderItemEntity, Integer> QUANTITY =
            ColumnField.of(ItemTotals.class, ITEMS, "quantity", Integer.class);
    private static final ColumnField<ItemTotals, OrderItemEntity, BigDecimal> UNIT_PRICE =
            ColumnField.of(ItemTotals.class, ITEMS, "unitPrice", BigDecimal.class);
    private static final ColumnField<ItemTotals, OrderEntity, LocalDateTime> PLACED_AT =
            ColumnField.of(ItemTotals.class, ITEM_ORDER, "placedAt", LocalDateTime.class);

    /** Each function with the Java type R-AGG-03 gives it. */
    private static final Map<AggregateField<ItemTotals, ?>, Class<?>> FUNCTIONS = functions();

    private static Map<AggregateField<ItemTotals, ?>, Class<?>> functions() {
        var functions = new LinkedHashMap<AggregateField<ItemTotals, ?>, Class<?>>();
        functions.put(Agg.count(ITEMS), Long.class);
        functions.put(Agg.countDistinct(PRODUCT), Long.class);
        functions.put(Agg.sum(UNIT_PRICE), BigDecimal.class);
        functions.put(Agg.sum(ITEM_ID), Long.class);
        functions.put(Agg.sumAsLong(QUANTITY), Long.class);
        functions.put(Agg.avg(QUANTITY), Double.class);
        functions.put(Agg.avg(UNIT_PRICE), Double.class);
        functions.put(Agg.min(PRODUCT), String.class);
        functions.put(Agg.max(QUANTITY), Integer.class);
        functions.put(Agg.min(UNIT_PRICE), BigDecimal.class);
        functions.put(Agg.max(PLACED_AT), LocalDateTime.class);
        functions.put(Agg.of("priceSpread", BigDecimal.class,
                (ctx, cb) -> cb.diff(cb.max(UNIT_PRICE.path(ctx)), cb.min(UNIT_PRICE.path(ctx)))), BigDecimal.class);
        return functions;
    }

    // ---- orders, totalled per customer, per status or as one group

    record Totals(Long customerId, String status, BigDecimal total, Long count) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);

    private static final ColumnField<Totals, OrderEntity, Long> ID =
            ColumnField.of(Totals.class, ORDERS, "id", Long.class);
    private static final ColumnField<Totals, OrderEntity, String> STATUS =
            ColumnField.of(Totals.class, ORDERS, "status", String.class);
    private static final ColumnField<Totals, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(Totals.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<Totals, CustomerEntity, Long> CUSTOMER_ID =
            ColumnField.of(Totals.class, CUSTOMER, "id", Long.class);
    private static final ColumnField<Totals, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(Totals.class, CUSTOMER, "name", String.class);

    private static final AggregateField<Totals, BigDecimal> SUM_TOTAL = Agg.sum(TOTAL);
    private static final AggregateField<Totals, Long> COUNT = Agg.count(ORDERS);

    private static final ModelQuery.Builder<OrderEntity, Object, Totals> TOTALS = ModelQuery.builder(ORDERS,
            row -> new Totals(row.get(CUSTOMER_ID), row.get(STATUS), row.get(SUM_TOTAL), row.get(COUNT)));

    private static final ModelQuery.Builder<OrderEntity, Object, Totals> PER_CUSTOMER = TOTALS
            .columns(ColumnSet.of(CUSTOMER_ID, SUM_TOTAL))
            .groupBy(CUSTOMER_ID)
            .orderBy(CUSTOMER_ID.asc());

    private static final Optional<BigDecimal> NO_TOTAL = Optional.empty();
    private static final Optional<Long> NO_COUNT = Optional.empty();
    private static final BigDecimal T2500 = new BigDecimal("2500.00");

    // ---- AC-AGG-01

    @TckTest
    void ac_agg_01_every_agg_function_returns_the_r_agg_03_type(TckDatabase db) {
        ColumnSet<ItemTotals> columns = ColumnSet.of();
        for (AggregateField<ItemTotals, ?> aggregate : FUNCTIONS.keySet()) {
            columns = columns.with(aggregate);
        }
        var query = ModelQuery.builder(ITEMS, AggregatesTest::itemTotals).columns(columns).build();
        List<ItemTotals> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "agg-01-functions", ds -> inSession(ds, em -> results.addAll(run(em, query))));
        assertThat(results).hasSize(1);
        Map<AggregateField<ItemTotals, ?>, Object> values = results.get(0).values();
        FUNCTIONS.forEach((aggregate, type) ->
                assertThat(values.get(aggregate)).as(aggregate.name()).isInstanceOf(type));

        Object[] expected = new Object[FUNCTIONS.size()];
        inSession(db, em -> System.arraycopy(em.createQuery("select count(i), count(distinct i.productCode),"
                + " sum(i.unitPrice), sum(i.id), sum(i.quantity), avg(i.quantity), avg(i.unitPrice),"
                + " min(i.productCode), max(i.quantity), min(i.unitPrice), max(o.placedAt),"
                + " max(i.unitPrice) - min(i.unitPrice) from OrderItemEntity i join i.order o", Object[].class)
                .getSingleResult(), 0, expected, 0, expected.length));
        int i = 0;
        for (AggregateField<ItemTotals, ?> aggregate : FUNCTIONS.keySet()) {
            Object want = expected[i++];
            Object got = values.get(aggregate);
            if (want instanceof BigDecimal decimal) {
                assertThat((BigDecimal) got).as(aggregate.name()).isEqualByComparingTo(decimal);
            } else if (want instanceof Number number && got instanceof Number actual) {
                assertThat(actual.doubleValue()).as(aggregate.name()).isEqualTo(number.doubleValue());
            } else {
                assertThat(got).as(aggregate.name()).isEqualTo(want);
            }
        }
        assertThat(values.get(Agg.<ItemTotals>count(ITEMS))).isEqualTo((long) TckFixture.ORDER_ITEMS);
        assertThat(values.get(Agg.countDistinct(PRODUCT))).isEqualTo(50L);
    }

    private static ItemTotals itemTotals(Row row) {
        var values = new LinkedHashMap<AggregateField<ItemTotals, ?>, Object>();
        FUNCTIONS.keySet().forEach(aggregate -> values.put(aggregate, row.get(aggregate)));
        return new ItemTotals(values);
    }

    // ---- AC-AGG-02

    @Test
    void ac_agg_02_sum_over_an_integer_column_throws_mq1403_when_called_and_sum_as_long_accepts_it() {
        assertMq1403(() -> Agg.sum(QUANTITY),
                "ItemTotals.quantity: Agg.sum does not take column type Integer, only [BigDecimal, Double, Long]; "
                        + "use Agg.sumAsLong");
        assertThat(Agg.sumAsLong(QUANTITY).type()).isEqualTo(Long.class);

        // The same attribute declared as other types, to reach every branch of R-AGG-03's table.
        assertMq1403(() -> Agg.sum(itemColumn("quantity", Short.class)), "Agg.sum does not take column type Short");
        assertMq1403(() -> Agg.sum(itemColumn("quantity", Byte.class)), "Agg.sum does not take column type Byte");
        assertMq1403(() -> Agg.sum(itemColumn("unitPrice", Float.class)), "Agg.sum does not take column type Float");
        assertMq1403(() -> Agg.sum(itemColumn("id", BigInteger.class)),
                "Agg.sum does not take column type BigInteger");
        // A Long would truncate a decimal sum: sumAsLong refuses it rather than lose the fraction (INV-5).
        assertMq1403(() -> Agg.sumAsLong(UNIT_PRICE),
                "ItemTotals.unitPrice: Agg.sumAsLong does not take column type BigDecimal, only [Integer, Short, Long, "
                        + "Byte]; use Agg.sum");
        assertMq1403(() -> Agg.sumAsLong(itemColumn("unitPrice", Double.class)),
                "Agg.sumAsLong does not take column type Double");

        assertThat(Agg.sum(UNIT_PRICE).type()).isEqualTo(BigDecimal.class);
        assertThat(Agg.sum(ITEM_ID).type()).isEqualTo(Long.class);
        assertThat(Agg.sum(itemColumn("unitPrice", Double.class)).type()).isEqualTo(Double.class);
        for (Class<? extends Number> integral : List.of(Short.class, Long.class, Byte.class)) {
            assertThat(Agg.sumAsLong(itemColumn("quantity", integral)).type()).isEqualTo(Long.class);
        }
    }

    private static <C> ColumnField<ItemTotals, OrderItemEntity, C> itemColumn(String attribute, Class<C> type) {
        return ColumnField.of(ItemTotals.class, ITEMS, attribute, type);
    }

    private static void assertMq1403(Runnable call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1403))
                .hasMessageContaining(message);
    }

    // ---- AC-AGG-03

    @TckTest
    void ac_agg_03_sum_over_zero_matching_rows_reaches_the_model_as_null_not_zero(TckDatabase db) {
        var query = TOTALS.columns(ColumnSet.of(SUM_TOTAL, COUNT)).where(f -> f.eq(STATUS, "NO SUCH STATUS")).build();
        List<Totals> results = new ArrayList<>();
        inSession(db, em -> results.addAll(run(em, query)));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).total()).isNull();
        assertThat(results.get(0).count()).isZero();
    }

    // ---- AC-AGG-04

    @TckTest
    void ac_agg_04_two_identical_sum_constants_render_one_selection_and_one_row_key(TckDatabase db) {
        AggregateField<Totals, BigDecimal> first = Agg.sum(TOTAL);
        AggregateField<Totals, BigDecimal> second = Agg.sum(TOTAL);
        assertThat(first).isNotSameAs(second).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(ColumnSet.of(first, second).columns()).hasSize(1);
        // as(...) is the way to the same function over the same column twice.
        AggregateField<Totals, BigDecimal> again = first.as("again");
        assertThat(again).isNotEqualTo(first);
        assertThat(ColumnSet.of(first, again).columns()).hasSize(2);

        var once = TOTALS.columns(ColumnSet.of(first, second)).build();
        var twice = TOTALS.columns(ColumnSet.of(first, again)).build();
        List<Row> rows = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "agg-04-identical-sums", ds -> inSession(ds, em -> {
            assertThat(once.buildQuery(em.getCriteriaBuilder(), Phase.MODEL).query().getSelection()
                    .getCompoundSelectionItems()).hasSize(1);
            rows.addAll(rows(em, once));
            rows.addAll(rows(em, twice));
        }));
        assertThat(rows).hasSize(2);
        BigDecimal total = rows.get(0).get(first);
        assertThat(total).isNotNull();
        assertThat(rows.get(0).get(second)).isEqualTo(total);
        assertThat(rows.get(1).get(first)).isEqualTo(total);
        assertThat(rows.get(1).get(again)).isEqualTo(total);
    }

    // ---- AC-AGG-05

    @Test
    void ac_agg_05_two_agg_of_fields_with_the_same_name_and_different_expressions_throw_mq1103() {
        AggregateField<Totals, BigDecimal> largest =
                Agg.of("largest", BigDecimal.class, (ctx, cb) -> cb.max(TOTAL.path(ctx)));
        AggregateField<Totals, BigDecimal> alsoLargest =
                Agg.of("largest", BigDecimal.class, (ctx, cb) -> cb.max(TOTAL.path(ctx)));
        assertThat(largest).isEqualTo(alsoLargest); // keyed by name (R-AGG-02)
        var base = TOTALS.columns(ColumnSet.of(largest));

        assertMq1103(() -> ColumnSet.of(largest, alsoLargest), "ColumnSet.largest");
        assertMq1103(() -> ColumnSet.of(largest).with(alsoLargest), "ColumnSet.largest");
        assertMq1103(() -> base.orderBy(alsoLargest.desc()).build(), "OrderEntity.largest");
        assertMq1103(() -> base.having(h -> h.gt(alsoLargest, BigDecimal.ONE)).build(), "OrderEntity.largest");
        assertMq1103(() -> base.having(h -> h.or(a -> a.gt(alsoLargest, NO_TOTAL), b -> b.gt(COUNT, 1L))).build(),
                "OrderEntity.largest");

        // One definition named twice is one selection; as(...) gives a second definition its own key.
        assertThat(ColumnSet.of(largest, largest).columns()).hasSize(1);
        base.orderBy(largest.desc()).having(h -> h.gt(largest, BigDecimal.ONE)).build();
        TOTALS.columns(ColumnSet.of(largest, alsoLargest.as("other"))).build();
    }

    private static void assertMq1103(Runnable call, String named) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1103))
                .hasMessageStartingWith("MQ1103: " + named + ": ");
    }

    // ---- AC-AGG-06

    @Test
    void ac_agg_06_an_aggregate_in_where_and_a_plain_column_in_having_fail_to_compile(@TempDir Path out)
            throws IOException {
        assertThat(compile(out, "where(f -> f.eq(STATUS, \"PAID\")).having(h -> h.gt(COUNT, 1L))")).isEmpty();
        assertThat(compile(out, "where(f -> f.gt(COUNT, 1L))")).isNotEmpty();
        assertThat(compile(out, "where(f -> f.isNull(COUNT))")).isNotEmpty();
        assertThat(compile(out, "having(h -> h.eq(STATUS, \"PAID\"))")).isNotEmpty();
        assertThat(compile(out, "having(h -> h.isNull(STATUS))")).isNotEmpty();
    }

    /** Compiles a query whose builder is continued by {@code call}, and returns the errors on that line. */
    private static List<String> compile(Path out, String call) throws IOException {
        String source = String.join("\n",
                "package probe;",
                "import com.rey.modelquery.core.*;",
                "class Probe {",
                "    static final class Entity {}",
                "    record Model() {}",
                "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
                "    static final ColumnField<Model, Entity, String> STATUS =",
                "            ColumnField.of(Model.class, ROOT, \"status\", String.class);",
                "    static final AggregateField<Model, Long> COUNT = Agg.count(ROOT);",
                "    static final ModelQuery.Builder<Entity, Object, Model> BASE = ModelQuery.builder(ROOT,",
                "            row -> new Model()).columns(ColumnSet.of(STATUS, COUNT)).groupBy(STATUS);",
                "    static final ModelQuery<Entity, Object, Model> QUERY = BASE." + call + ".build();",
                "}");
        long probeLine = 12;
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///probe/Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, null)) {
            List<String> options = List.of("-proc:none", "-d", out.toString(),
                    "-classpath", System.getProperty("java.class.path"));
            boolean compiled = javac.getTask(null, files, diagnostics, options, null, List.of(file)).call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    // Any error elsewhere means the probe itself is broken, not the call under test.
                    assertThat(d.getLineNumber()).as(d.toString()).isEqualTo(probeLine);
                    errors.add(d.getMessage(null));
                }
            }
            assertThat(compiled).isEqualTo(errors.isEmpty());
            return errors;
        }
    }

    // ---- AC-AGG-07

    @TckTest
    void ac_agg_07_a_having_or_whose_every_branch_was_skipped_returns_the_same_groups_as_without_it(TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "agg-07-skipped-having", ds -> inSession(ds, em -> {
            results.add(customers(em, PER_CUSTOMER));
            // Every branch skipped, one of them over an aggregate that is not selected: no HAVING at all.
            results.add(customers(em, PER_CUSTOMER.having(h -> h.or(a -> a.gt(SUM_TOTAL, NO_TOTAL),
                    b -> b.lt(COUNT, NO_COUNT)))));
            // One branch skipped: it is dropped, and the other stands alone.
            results.add(customers(em, PER_CUSTOMER.having(h -> h.or(a -> a.gt(SUM_TOTAL, Optional.of(T2500)),
                    b -> b.lt(COUNT, NO_COUNT)))));
            results.add(customers(em, PER_CUSTOMER.having(h -> h.gt(SUM_TOTAL, T2500))));
            // not, when and apply follow the same rules.
            results.add(customers(em, PER_CUSTOMER.having(h -> h.not(g -> g.gt(SUM_TOTAL, NO_TOTAL))
                    .when(false, g -> g.gt(SUM_TOTAL, T2500))
                    .apply(g -> g.eq(COUNT, NO_COUNT)))));
        }));
        List<Long> all = jpqlCustomers(db, "");
        List<Long> over = jpqlCustomers(db, "having sum(o.total) > 2500");
        assertThat(all).hasSize(TckFixture.CUSTOMERS);
        assertThat(over).isNotEmpty().hasSizeLessThan(all.size());
        assertThat(results.get(0)).isEqualTo(all);
        assertThat(results.get(1)).isEqualTo(all);
        assertThat(results.get(2)).isEqualTo(over);
        assertThat(results.get(3)).isEqualTo(over);
        assertThat(results.get(4)).isEqualTo(all);
    }

    private static List<Long> customers(EntityManager em, ModelQuery.Builder<?, ?, Totals> query) {
        return run(em, query.build()).stream().map(Totals::customerId).toList();
    }

    private static List<Long> jpqlCustomers(TckDatabase db, String having) {
        List<Long> ids = new ArrayList<>();
        inSession(db, em -> ids.addAll(em.createQuery("select c.id from OrderEntity o join o.customer c group by c.id "
                + having + " order by c.id", Long.class).getResultList()));
        return ids;
    }

    // ---- AC-AGG-08

    @Test
    void ac_agg_08_a_selected_column_missing_from_the_group_by_throws_mq1401_naming_the_column() {
        var selected = TOTALS.columns(ColumnSet.of(CUSTOMER_ID, CUSTOMER_NAME, SUM_TOTAL));
        assertMq1401(() -> selected.groupBy(CUSTOMER_ID).build(), "Totals.name");
        // With no groupBy an aggregate makes one group, and a having filter makes the query grouped too.
        assertMq1401(() -> TOTALS.columns(ColumnSet.of(STATUS, COUNT)).build(), "Totals.status");
        assertMq1401(() -> TOTALS.columns(ColumnSet.of(STATUS)).having(h -> h.gt(COUNT, 1L)).build(),
                "Totals.status");
        // The same attribute on another join is another column.
        assertMq1401(() -> selected.groupBy(CUSTOMER_ID, ColumnField.of(Totals.class, CUSTOMER.as("other"), "name",
                String.class)).build(), "Totals.name");

        ColumnSet<Totals> keys = ColumnSet.of(CUSTOMER_ID, CUSTOMER_NAME);
        selected.groupBy(keys).build();
        selected.groupBy(CUSTOMER_ID, CUSTOMER_NAME).build();
        // A skipped having records nothing, so the query is not grouped and needs no group-by.
        TOTALS.columns(ColumnSet.of(STATUS)).having(h -> h.gt(COUNT, NO_COUNT)).build();
        // A group key must be a column: an aggregate in the group-by set is refused at once.
        assertThatThrownBy(() -> selected.groupBy(keys.with(SUM_TOTAL)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sum(total)");
    }

    private static void assertMq1401(Runnable call, String column) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1401))
                .hasMessage("MQ1401: " + column + ": selected but not in groupBy");
    }

    // ---- AC-AGG-09 (the export half is M2.6)

    @Test
    void ac_agg_09_keyset_or_primary_key_first_on_a_grouped_query_throws_mq1402_naming_the_model() {
        var grouped = TOTALS.columns(ColumnSet.of(STATUS, COUNT)).groupBy(STATUS);
        var withKey = grouped.primaryKey(PrimaryKey.of(STATUS));
        var singleGroup = TOTALS.columns(ColumnSet.of(COUNT));
        var byHaving = TOTALS.columns(ColumnSet.of(STATUS)).groupBy(STATUS).having(h -> h.gt(COUNT, 1L));
        List<Runnable> refused = List.of(
                () -> grouped.keyset().build(),
                () -> grouped.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100)).build(),
                () -> withKey.keyset().build(),
                () -> withKey.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100)).build(),
                () -> byHaving.keyset().build());
        for (Runnable call : refused) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1402))
                    .hasMessageStartingWith("MQ1402: Totals: ");
        }
        // With no column selected, the model's name falls back to the root entity's.
        assertThatThrownBy(() -> singleGroup.keyset().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1402))
                .hasMessageStartingWith("MQ1402: OrderEntity: keyset()");
    }

    @TckTest
    void ac_agg_09_a_grouped_query_needs_no_primary_key_and_ignores_one_that_is_set(TckDatabase db) {
        var grouped = TOTALS.columns(ColumnSet.of(STATUS, COUNT)).groupBy(STATUS).orderBy(STATUS.asc());
        List<String> debug = new ArrayList<>();
        Logger logger = Logger.getLogger(ModelQuery.class.getName());
        Level level = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.FINE) {
                    debug.add(new SimpleFormatter().formatMessage(r));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.FINE);
        logger.addHandler(handler);
        ModelQuery<OrderEntity, ?, Totals> keyed;
        try {
            keyed = grouped.primaryKey(PrimaryKey.of(ID)).build();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
        assertThat(debug).containsExactly("Totals: primaryKey(...) is ignored on a grouped query");
        assertThat(keyed.primaryKey()).isEmpty();
        assertThat(keyed.spec().primaryKey()).isEmpty();

        var keyless = grouped.build();
        List<List<Totals>> results = new ArrayList<>();
        inSession(db, em -> {
            // The key is not selected: selecting it would split every group into its rows.
            assertThat(keyed.buildQuery(em.getCriteriaBuilder(), Phase.MODEL).query().getSelection()
                    .getCompoundSelectionItems()).hasSize(2);
            assertThatThrownBy(() -> keyed.buildQuery(em.getCriteriaBuilder(), Phase.PRIMARY_KEY))
                    .isInstanceOf(IllegalStateException.class);
            results.add(run(em, keyless));
            results.add(run(em, keyed));
        });
        assertThat(results.get(0)).hasSize(4).isEqualTo(results.get(1));
    }

    // ---- AC-AGG-10

    @TckTest
    void ac_agg_10_an_aggregate_selection_with_no_group_by_returns_exactly_one_row(TckDatabase db) {
        var whole = TOTALS.columns(ColumnSet.of(SUM_TOTAL, COUNT));
        List<List<Totals>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "agg-10-single-group", ds -> inSession(ds, em -> {
            assertThat(whole.build().buildQuery(em.getCriteriaBuilder(), Phase.MODEL).query().getGroupList())
                    .isEmpty();
            results.add(run(em, whole.build()));
            results.add(run(em, whole.where(f -> f.eq(STATUS, "PAID")).build()));
            results.add(run(em, whole.where(f -> f.eq(STATUS, "NO SUCH STATUS")).build()));
        }));
        assertThat(results).allSatisfy(rows -> assertThat(rows).hasSize(1));
        assertThat(results.get(0).get(0).count()).isEqualTo((long) TckFixture.ORDERS);
        assertThat(results.get(1).get(0).count()).isEqualTo(TckFixture.ORDERS / 4L);
        assertThat(results.get(2).get(0).count()).isZero();
    }

    // ---- AC-AGG-11

    /** A mutable summary with a field derived from two aggregates, the {@code afterMap} case. */
    static final class StatusSummary {
        String status;
        Long count;
        BigDecimal total;
        BigDecimal average;
    }

    private static final ColumnField<StatusSummary, OrderEntity, String> S_STATUS =
            ColumnField.of(StatusSummary.class, ORDERS, "status", String.class);
    private static final AggregateField<StatusSummary, Long> S_COUNT = Agg.count(ORDERS);
    private static final AggregateField<StatusSummary, BigDecimal> S_TOTAL =
            Agg.sum(ColumnField.of(StatusSummary.class, ORDERS, "total", BigDecimal.class));

    @TckTest
    void ac_agg_11_after_map_on_a_grouped_query_runs_once_per_group_and_sees_every_selected_aggregate(
            TckDatabase db) {
        AtomicInteger calls = new AtomicInteger();
        var query = ModelQuery.builder(ORDERS, row -> {
                    var summary = new StatusSummary();
                    summary.status = row.get(S_STATUS);
                    return summary;
                })
                .columns(ColumnSet.of(S_STATUS, S_COUNT, S_TOTAL))
                .groupBy(S_STATUS)
                .orderBy(S_STATUS.asc())
                .afterMap((summary, row) -> {
                    calls.incrementAndGet();
                    assertThat(row.isSelected(S_COUNT)).isTrue();
                    assertThat(row.isSelected(S_TOTAL)).isTrue();
                    summary.count = row.get(S_COUNT);
                    summary.total = row.get(S_TOTAL);
                    summary.average = summary.total.divide(BigDecimal.valueOf(summary.count), 2, RoundingMode.HALF_UP);
                })
                .build();
        List<StatusSummary> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "agg-11-after-map", ds -> inSession(ds, em -> results.addAll(run(em, query))));
        List<Object[]> expected = new ArrayList<>();
        inSession(db, em -> expected.addAll(em.createQuery("select o.status, count(o), sum(o.total) from OrderEntity o"
                + " group by o.status order by o.status", Object[].class).getResultList()));
        assertThat(calls.get()).isEqualTo(results.size()).isEqualTo(expected.size()).isEqualTo(4);
        for (int i = 0; i < results.size(); i++) {
            StatusSummary summary = results.get(i);
            assertThat(summary.status).isEqualTo(expected.get(i)[0]);
            assertThat(summary.count).isEqualTo(expected.get(i)[1]);
            assertThat(summary.total).isEqualByComparingTo((BigDecimal) expected.get(i)[2]);
            assertThat(summary.average).isNotNull();
        }
    }

    // ---- helpers

    private static <V> List<V> run(EntityManager em, ModelQuery<?, ?, V> query) {
        BuiltQuery<V> built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
        return em.createQuery(built.query()).getResultList().stream().map(built::map).toList();
    }

    private static List<Row> rows(EntityManager em, ModelQuery<?, ?, ?> query) {
        BuiltQuery<?> built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
        return em.createQuery(built.query()).getResultList().stream().map(built.selection()::row).toList();
    }

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }

    private static void inSession(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(work::accept);
        }
    }
}
