package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.hibernate.SessionFactory;

/**
 * Request-bound values on a real database: each statement a call runs binds the params it renders, once, with the
 * caller's value, and no others; a source's body binds its own; a correlated sub-select reads the outer query's
 * (R-COL-40, D-198, AC-COL-60, AC-COL-62).
 */
class QueryParamStatementsTest {

    record Row(Long id, String status, String label) {}

    record Same(Long id, Long same) {}

    /** A source body's model: the orders from a bound id on. */
    static final class Floor {}

    /** A correlated sub-select's model. */
    static final class Inner {}

    /** The fixture's order statuses, by order id modulo 4 (TckFixture). */
    private static final String[] STATUSES = {"NEW", "PAID", "SHIPPED", "CANCELLED"};
    private static final long LAST = 30;

    static final QueryParam<Long> MIN_ID = QueryParam.of("minId", Long.class);
    static final QueryParam<String> TAG = QueryParam.of("tag", String.class);
    static final QueryParam<Long> UP_TO = QueryParam.of("upTo", Long.class);

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final ColumnField<Row, OrderEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, OrderEntity, String> STATUS =
            ColumnField.of(Row.class, ROOT, "status", String.class);
    /** Select-only: the count statements render it not, so they bind no tag. */
    private static final ExpressionField<Row, String> LABEL =
            Expr.<Row>concat(STATUS, Expr.param(TAG)).named("label");

    /** Orders from the bound id to {@link #LAST}, labelled with the bound tag: one shared definition (INV-9). */
    private static final ModelQuery.Builder<OrderEntity, Long, Row> ROWS = ModelQuery
            .builder(ROOT, row -> new Row(row.get(ID), row.get(STATUS), row.get(LABEL)))
            .select(SelectSet.of(ID, STATUS, LABEL)).primaryKey(PrimaryKey.of(ID))
            .where(f -> f.compare(ID, Op.GTE, Expr.param(MIN_ID)).lte(ID, LAST)).orderBy(ID.asc());

    private static final ColumnField<Floor, OrderEntity, Long> FLOOR_ID =
            ColumnField.of(Floor.class, ROOT, "id", Long.class).named("floorId");
    private static final SourceJoin<Row, Floor> FLOOR =
            SourceJoin.of("floor", Floor.class, JoinKind.INNER, List.of(SourceJoin.on(ID, FLOOR_ID)));

    private static final ColumnField<Same, OrderEntity, Long> SAME_ID =
            ColumnField.of(Same.class, ROOT, "id", Long.class);
    private static final ColumnField<Same, OrderEntity, String> SAME_STATUS =
            ColumnField.of(Same.class, ROOT, "status", String.class);
    private static final ColumnField<Inner, OrderEntity, Long> INNER_ID =
            ColumnField.of(Inner.class, ROOT, "id", Long.class);
    private static final ColumnField<Inner, OrderEntity, String> INNER_STATUS =
            ColumnField.of(Inner.class, ROOT, "status", String.class);
    /** The orders up to the bound id with the outer order's status: the param is in the correlation. */
    private static final ExpressionField<Same, Long> SAME = Correlated.count(Same.class, SubSelect.of(INNER_ID),
            (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(SAME_STATUS))
                    .compare(INNER_ID, Op.LTE, Expr.param(UP_TO))).named("same");

    // ---- AC-COL-60

    @TckTest
    void ac_col_60_each_statement_binds_the_params_it_renders_once_and_no_other(TckDatabase db) {
        try (var recorder = new Recorder(db)) {
            List<List<String>> sql = new ArrayList<>();
            for (long min : List.of(7L, 9L)) {
                var ran = new ArrayList<Stmt>();
                recorder.serving(OrderEntity.class, executor -> ran.addAll(phases(recorder, executor, min)));
                sql.add(ran.stream().map(Stmt::sql).toList());
            }
            // One text across values: a param is a bind parameter, never a per-call constant.
            assertThat(sql.get(0)).hasSize(15).isEqualTo(sql.get(1));
        }
        // The shared definition holds no value: unbound, it is still refused (INV-9).
        assertThatThrownBy(ROWS::build).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1512));
        assertThat(MIN_ID).hasToString("minId: Long");
        assertThat(LABEL.name()).isEqualTo("label");
    }

    /** Every C.4 statement of {@link #ROWS} bound to {@code min}, with each statement's binds asserted. */
    private static List<Stmt> phases(Recorder recorder, ModelQueryExecutor<OrderEntity> executor, long min) {
        String tag = "#" + min;
        List<Row> rows = rows(min, LAST, tag);
        ModelQuery<OrderEntity, Long, Row> query = ROWS.bind(MIN_ID, min).bind(TAG, tag).build();
        ModelQuery<OrderEntity, Long, Row> keyed = ROWS.keyset().bind(MIN_ID, min).bind(TAG, tag).build();
        ModelQuery<OrderEntity, Long, Row> keysFirst = ROWS.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                .bind(MIN_ID, min).bind(TAG, tag).build();
        var all = new ArrayList<Stmt>();

        List<Stmt> content = recorder.run(() ->
                assertThat(executor.list(query, Limit.of(5))).containsExactlyElementsOf(rows.subList(0, 5)));
        assertThat(content).singleElement().satisfies(s -> assertReads(s, min, tag));
        all.addAll(content);

        List<Stmt> counted = recorder.run(() -> {
            Slice<Row> page = executor.page(query, PageSpec.of(1, 5), CountMode.COUNT);
            assertThat(page.content()).containsExactlyElementsOf(rows.subList(5, 10));
            assertThat(page.total()).hasValue(rows.size());
        });
        assertThat(counted).hasSize(2);
        counted.forEach(s -> assertReads(s, min, s.sql().startsWith("select count(") ? null : tag));
        assertThat(counted).filteredOn(s -> s.sql().startsWith("select count(")).hasSize(1);
        all.addAll(counted);

        List<Stmt> inline = recorder.run(() -> {
            Slice<Row> page = executor.page(query, PageSpec.of(0, 5), CountMode.INLINE_COUNT);
            assertThat(page.content()).containsExactlyElementsOf(rows.subList(0, 5));
            assertThat(page.total()).hasValue(rows.size());
        });
        assertThat(inline).singleElement().satisfies(s -> assertReads(s, min, tag));
        all.addAll(inline);

        // The key step selects the keys only, so it binds no tag; the content step reads the label by those keys.
        List<Stmt> twoSteps = recorder.run(() -> assertThat(executor.page(keysFirst, PageSpec.of(1, 5),
                CountMode.NO_COUNT).content()).containsExactlyElementsOf(rows.subList(5, 10)));
        assertThat(twoSteps).hasSize(2);
        assertReads(twoSteps.get(0), min, null);
        assertThat(twoSteps.get(1).times(tag)).as(twoSteps.get(1).sql()).isEqualTo(1);
        all.addAll(twoSteps);

        List<Stmt> pages = recorder.run(() -> {
            KeysetSlice<Row> first = executor.page(keyed, KeysetSpec.first(5));
            assertThat(first.content()).containsExactlyElementsOf(rows.subList(0, 5));
            assertThat(executor.page(keyed, KeysetSpec.after(first.nextCursor().orElseThrow(), 5)).content())
                    .containsExactlyElementsOf(rows.subList(5, 10));
        });
        assertThat(pages).hasSize(2).allSatisfy(s -> assertReads(s, min, tag));
        all.addAll(pages);

        var exported = new ArrayList<Row>();
        List<Stmt> export = recorder.run(() -> executor.export(keyed, ExportOptions.of(10), Function.identity(),
                exported::add));
        assertThat(exported).containsExactlyElementsOf(rows);
        assertThat(export).hasSize(3).allSatisfy(s -> assertReads(s, min, tag));
        all.addAll(export);

        List<Stmt> stream = recorder.run(() -> {
            List<Row> streamed = executor.stream(query, Limit.unlimited(), rowStream -> rowStream.toList());
            assertThat(streamed).containsExactlyElementsOf(rows);
        });
        assertThat(stream).singleElement().satisfies(s -> assertReads(s, min, tag));
        all.addAll(stream);

        List<Stmt> byKeys = recorder.run(() -> assertThat(executor.byKeys(query, List.of(8L, 10L, 25L)))
                .containsOnlyKeys(min <= 8 ? List.of(8L, 10L, 25L) : List.of(10L, 25L)));
        assertThat(byKeys).singleElement().satisfies(s -> assertThat(s.times(tag)).as(s.sql()).isEqualTo(1));
        all.addAll(byKeys);

        List<Stmt> one = recorder.run(() -> assertThat(executor.one(query, 25L)).contains(rows.get((int) (25 - min))));
        assertThat(one).singleElement().satisfies(s -> assertThat(s.times(tag)).as(s.sql()).isEqualTo(1));
        all.addAll(one);

        // A fetch plan's parent read is a copy of the caller's query, with its bindings.
        List<Stmt> fetched = recorder.run(() -> assertThat(executor.list(query.withFetch(
                FetchPlan.of(SelectSet.of(ID, STATUS, LABEL))), Limit.of(3))).containsExactlyElementsOf(
                        rows.subList(0, 3)));
        assertThat(fetched).singleElement().satisfies(s -> assertReads(s, min, tag));
        all.addAll(fetched);
        return all;
    }

    // ---- AC-COL-62

    @TckTest
    void ac_col_62_a_joined_source_body_binds_its_own_value_in_each_statement(TckDatabase db) {
        try (var recorder = new Recorder(db)) {
            for (long min : List.of(7L, 9L)) {
                String tag = "#" + min;
                // The outer query and the body read one param; each binds its own value, the body one more.
                ModelQuery<OrderEntity, Long, Row> query = ROWS.joinSource(FLOOR, floor(min + 1, LAST))
                        .bind(MIN_ID, min).bind(TAG, tag).build();
                List<Row> rows = rows(min + 1, LAST, tag);
                recorder.serving(OrderEntity.class, executor -> {
                    List<Stmt> content = recorder.run(() -> assertThat(executor.list(query, Limit.unlimited()))
                            .containsExactlyElementsOf(rows));
                    List<Stmt> counted = recorder.run(() -> assertThat(executor.page(query, PageSpec.of(0, 5),
                            CountMode.COUNT).total()).hasValue(rows.size()));
                    assertThat(counted).hasSize(2);
                    for (Stmt s : concat(content, counted)) {
                        // The value bound in the body statement part is the body's own, on both provider lines.
                        assertThat(s.times(min + 1)).as("the body's value in " + s).isEqualTo(1);
                        assertThat(s.times(min)).as("the outer value in " + s).isEqualTo(1);
                    }
                });
            }
        }
    }

    @TckTest
    void ac_col_62_each_branch_of_a_rooted_set_operation_binds_its_own_value(TckDatabase db) {
        try (var recorder = new Recorder(db)) {
            for (long min : List.of(7L, 9L)) {
                TableField<Floor, Floor> source = TableField.source(Floor.class);
                var id = ColumnField.over(Long.class, source, FLOOR_ID);
                ModelQuery<Floor, ?, Long> query = ModelQuery.builder(source, row -> row.get(id),
                        floor(min + 1, 12).union(floor(25, 26))).select(SelectSet.of(id)).build();
                recorder.serving(Floor.class, executor -> {
                    List<Stmt> ran = recorder.run(() -> assertThat(executor.list(query, Limit.unlimited()))
                            .containsExactlyInAnyOrderElementsOf(concat(LongStream.rangeClosed(min + 1, 12)
                                    .boxed().toList(), List.of(25L, 26L))));
                    assertThat(ran).singleElement().satisfies(s -> {
                        assertThat(s.times(min + 1)).as(s.toString()).isEqualTo(1);
                        assertThat(s.times(25L)).as(s.toString()).isEqualTo(1);
                    });
                });
            }
        }
    }

    @TckTest
    void ac_col_62_a_correlated_correlation_reads_the_outer_querys_binding(TckDatabase db) {
        try (var recorder = new Recorder(db)) {
            for (long upTo : List.of(6L, 10L)) {
                ModelQuery<OrderEntity, Long, Same> query = ModelQuery
                        .builder(ROOT, row -> new Same(row.get(SAME_ID), row.get(SAME)))
                        .select(SelectSet.of(SAME_ID, SAME)).primaryKey(PrimaryKey.of(SAME_ID))
                        .where(f -> f.lte(SAME_ID, 8L)).orderBy(SAME_ID.asc()).bind(UP_TO, upTo).build();
                List<Same> expected = LongStream.rangeClosed(1, 8).mapToObj(i -> new Same(i,
                        LongStream.rangeClosed(1, upTo).filter(j -> j % 4 == i % 4).count())).toList();
                recorder.serving(OrderEntity.class, executor -> {
                    List<Stmt> counted = recorder.run(() -> {
                        Slice<Same> page = executor.page(query, PageSpec.of(0, 8), CountMode.COUNT);
                        assertThat(page.content()).containsExactlyElementsOf(expected);
                        assertThat(page.total()).hasValue(8);
                    });
                    assertThat(counted).hasSize(2);
                    for (Stmt s : counted) {
                        // Select-only, so the count renders no sub-select and binds no upTo.
                        assertThat(s.times(upTo)).as(s.toString())
                                .isEqualTo(s.sql().startsWith("select count(") ? 0 : 1);
                    }
                });
            }
        }
    }

    @TckTest
    void ac_col_63_a_definition_bound_per_request_is_checked_and_warned_of_once(TckDatabase db) {
        QueryCustomizer onlyModel = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL) {
                Predicate own = query.getRestriction();
                Predicate named = cb.isNotNull(query.getRoots().iterator().next().get("status"));
                query.where(own == null ? named : cb.and(own, named));
            }
        };
        ModelQuery.Builder<OrderEntity, Long, Row> shared = ROWS.customize(onlyModel);
        FetchPlan<Row> plan = FetchPlan.of(SelectSet.of(ID, STATUS, LABEL));
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(new SimpleFormatter().formatMessage(r));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        List<Logger> loggers = List.of(Logger.getLogger(ModelQuery.class.getName()),
                Logger.getLogger("com.rey.modelquery.jpa.DefaultModelQueryExecutor"));
        loggers.forEach(logger -> logger.addHandler(handler));
        try (var recorder = new Recorder(db)) {
            for (long min : List.of(7L, 9L)) {
                String tag = "#" + min;
                ModelQuery<OrderEntity, Long, Row> query = shared.bind(MIN_ID, min).bind(TAG, tag).build();
                recorder.serving(OrderEntity.class, executor -> {
                    assertThat(executor.list(query, Limit.unlimited())).isEqualTo(rows(min, LAST, tag));
                    assertThat(executor.count(query)).isEqualTo(LAST - min + 1);
                    // The lock and fetch copies of each binding read with that binding's values.
                    assertThat(executor.list(query.withFetch(plan), Limit.unlimited()))
                            .isEqualTo(rows(min, LAST, tag));
                    assertThat(executor.list(query.withLock(RowLock.exclusive()), Limit.unlimited()))
                            .isEqualTo(rows(min, LAST, tag));
                });
            }
        } finally {
            loggers.forEach(logger -> logger.removeHandler(handler));
        }
        // The phase check ran once for the definition and once for each copy, and count's ignored order warned once.
        assertThat(warnings).filteredOn(w -> w.contains("adds a predicate or an INNER join only in phase(s) [MODEL]"))
                .hasSize(3);
        assertThat(warnings).filteredOn(w -> w.contains("count(query) ignores the query's orderBy")).hasSize(1);
    }

    /** The orders {@code from} to {@code to}, read by a body that binds {@code from} itself. */
    private static SourceQuery<Floor> floor(long from, long to) {
        return SourceQuery.builder(Floor.class, ROOT, SelectSet.of(FLOOR_ID), PrimaryKey.of(FLOOR_ID))
                .where(f -> f.compare(FLOOR_ID, Op.GTE, Expr.param(MIN_ID)).lte(FLOOR_ID, to))
                .bind(MIN_ID, from).build();
    }

    private static List<Row> rows(long from, long to, String tag) {
        return LongStream.rangeClosed(from, to).mapToObj(i -> {
            String status = STATUSES[(int) (i % STATUSES.length)];
            return new Row(i, status, status + tag);
        }).toList();
    }

    /** {@code s} binds {@code min} once and {@code tag} once, or no tag when it is {@code null}. */
    private static void assertReads(Stmt s, long min, String tag) {
        assertThat(s.times(min)).as("minId in " + s).isEqualTo(1);
        if (tag == null) {
            assertThat(s.binds()).as("no tag in " + s).noneMatch(value -> value instanceof String text
                    && text.startsWith("#"));
        } else {
            assertThat(s.times(tag)).as("tag in " + s).isEqualTo(1);
        }
    }

    private static <T> List<T> concat(List<? extends T> a, List<? extends T> b) {
        var both = new ArrayList<T>(a);
        both.addAll(b);
        return both;
    }

    /** A statement's SQL and the value each of its parameters holds when it runs, by position. */
    record Stmt(String sql, List<Object> binds) {

        static Stmt of(QueryInfo query) {
            var byIndex = new TreeMap<Integer, Object>(); // a later set of one index replaces the earlier, as in JDBC
            for (List<ParameterSetOperation> set : query.getParametersList()) {
                for (ParameterSetOperation op : set) {
                    Object[] args = op.getArgs();
                    byIndex.put(((Number) args[0]).intValue(), args.length > 1 ? args[1] : null);
                }
            }
            return new Stmt(query.getQuery().strip().toLowerCase(Locale.ROOT),
                    Collections.unmodifiableList(new ArrayList<>(byIndex.values())));
        }

        int times(Object value) {
            return Collections.frequency(binds, value);
        }

        @Override
        public String toString() {
            return sql + " || " + binds;
        }
    }

    /** One session factory over {@code db} whose statements are recorded with the values bound to them. */
    static final class Recorder implements AutoCloseable {

        private final List<Stmt> statements = new CopyOnWriteArrayList<>();
        private final SessionFactory factory;

        Recorder(TckDatabase db) {
            this(db, Map.of());
        }

        /** A recorder whose factory applies the extra Hibernate {@code settings}. */
        Recorder(TckDatabase db, Map<String, Object> settings) {
            DataSource recording = ProxyDataSourceBuilder.create(JoinTestSupport.dataSource(db))
                    .afterQuery((exec, queries) -> queries.forEach(q -> statements.add(Stmt.of(q)))).build();
            factory = JoinTestSupport.sessionFactory(recording, settings);
        }

        /** {@code work} in a transaction, which PostgreSQL's streaming cursor needs (R-PRF-03). */
        <E> void serving(Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
            factory.inTransaction(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }

        /** The statements {@code call} runs, in order. */
        List<Stmt> run(Runnable call) {
            statements.clear();
            call.run();
            return List.copyOf(statements);
        }

        @Override
        public void close() {
            factory.close();
        }
    }
}
