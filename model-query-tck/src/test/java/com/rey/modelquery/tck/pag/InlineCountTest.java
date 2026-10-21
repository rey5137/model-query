package com.rey.modelquery.tck.pag;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExpectedNarrowing;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.TreatTestSupport;
import com.rey.modelquery.tck.col.TreatTestSupport.CountingRenderer;
import com.rey.modelquery.tck.col.TreatTestSupport.Payment;
import com.rey.modelquery.tck.col.TreatTestSupport.ServingProvider;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * {@code CountMode.INLINE_COUNT}: the page statement carries {@code count(1) over ()} where it returns exactly the rows
 * {@code COUNT} counts, and every other shape runs {@code COUNT}'s statements (R-EXE-15, AC-EXE-18 to AC-EXE-20).
 */
class InlineCountTest {

    record OrderRow(Long id, String status, BigDecimal total) {}

    record Group(Long customerId, BigDecimal total) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);

    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<OrderRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(OrderRow.class, ITEMS, "productCode", String.class);

    private static final ColumnField<Group, CustomerEntity, Long> GROUP_CUSTOMER =
            ColumnField.of(Group.class, CUSTOMER, "id", Long.class);
    private static final AggregateField<Group, BigDecimal> GROUP_TOTAL =
            Agg.sum(ColumnField.of(Group.class, ORDERS, "total", BigDecimal.class));

    /** Every order, in id order. */
    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ORDER_ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
            .select(SelectSet.of(ID, STATUS, TOTAL))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());

    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> NEW = ORDER_ROWS
            .where(f -> f.eq(STATUS, Optional.of("NEW")));

    /** Each customer's order total, one group per customer. */
    private static final ModelQuery.Builder<OrderEntity, Object, Group> PER_CUSTOMER = ModelQuery
            .builder(ORDERS, (Row row) -> new Group(row.get(GROUP_CUSTOMER), row.get(GROUP_TOTAL)))
            .select(SelectSet.of(GROUP_CUSTOMER, GROUP_TOTAL))
            .groupBy(GROUP_CUSTOMER)
            .orderBy(GROUP_CUSTOMER.asc());

    private static final String FALLBACK = "INLINE_COUNT runs COUNT's statements for the total";

    /** One page of one query, under any count mode. */
    private record Case<E, M>(ModelQuery<E, ?, M> query, PageSpec page) {
        Slice<M> run(ModelQueryExecutor<E> executor, CountMode mode) {
            return executor.page(query, page, mode);
        }
    }

    /** Runs {@code work} with an executor over a factory on {@code ds}, as one provider setting serves it. */
    @FunctionalInterface
    private interface Executing<E> {
        void run(DataSource ds, Consumer<ModelQueryExecutor<E>> work);
    }

    // ---- AC-EXE-18

    @TckTest
    void ac_exe_18_flat_grouped_having_and_key_first_pages_read_counts_total_in_their_own_statement(TckDatabase db) {
        List<Case<OrderEntity, ?>> cases = List.of(
                new Case<>(NEW.build(), PageSpec.of(3, 50)),
                // The last page: offset and rows reach the total, so no next page.
                new Case<>(NEW.build(), PageSpec.of(24, 50)),
                new Case<>(PER_CUSTOMER.build(), PageSpec.of(2, 100)),
                new Case<>(PER_CUSTOMER.having(h -> h.gt(GROUP_TOTAL, new BigDecimal("2500"))).build(),
                        PageSpec.of(0, 50)),
                new Case<>(NEW.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10)).build(), PageSpec.of(3, 50)));
        List<Slice<?>> inline = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-18-inline-count", ds -> withOrders(ds,
                executor -> cases.forEach(c -> inline.add(c.run(executor, CountMode.INLINE_COUNT)))));
        // One statement per page, and the key-first page's step 2 reads the keys back unchanged, without the window.
        assertThat(sql).hasSize(6);
        assertThat(sql.subList(0, 5)).allSatisfy(statement -> assertThat(statement).contains("count(1) over()"));
        assertThat(sql.get(5)).doesNotContain("over()");
        List<Slice<?>> counted = new ArrayList<>();
        withOrders(db, executor -> cases.forEach(c -> counted.add(c.run(executor, CountMode.COUNT))));
        assertThat(inline).isEqualTo(counted);
        assertThat(counted).allSatisfy(slice -> assertThat(slice.content()).isNotEmpty());
        assertThat(counted.get(0).hasNext()).isTrue();
        assertThat(counted.get(1).hasNext()).isFalse();
    }

    // ---- AC-EXE-19

    @TckTest
    void ac_exe_19_an_empty_first_page_is_0_in_one_statement_and_past_the_end_runs_counts_statement(TckDatabase db) {
        var none = ORDER_ROWS.where(f -> f.eq(STATUS, Optional.of("NONE"))).build();
        List<Slice<OrderRow>> empty = new ArrayList<>();
        List<String> emptySql = SqlSnapshots.capture(db, ds -> withOrders(ds,
                executor -> empty.add(executor.page(none, PageSpec.of(0, 50), CountMode.INLINE_COUNT))));
        assertThat(emptySql).hasSize(1);
        assertThat(empty).singleElement().satisfies(slice -> {
            assertThat(slice.content()).isEmpty();
            assertThat(slice.total()).hasValue(0);
            assertThat(slice.hasNext()).isFalse();
        });

        // A page past the end can't tell an empty result from an offset beyond it, so COUNT's statement runs.
        for (var q : List.of(NEW.build(), NEW.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10)).build())) {
            PageSpec past = PageSpec.of(100, 50);
            List<Slice<OrderRow>> inline = new ArrayList<>();
            List<String> inlineSql = SqlSnapshots.capture(db, ds -> withOrders(ds,
                    executor -> inline.add(executor.page(q, past, CountMode.INLINE_COUNT))));
            List<Slice<OrderRow>> counted = new ArrayList<>();
            long[] total = new long[1];
            List<String> countSql = SqlSnapshots.capture(db, ds -> withOrders(ds, executor -> {
                counted.add(executor.page(q, past, CountMode.COUNT));
                total[0] = executor.count(q);
            }));
            // COUNT past its total runs no content statement; its count statement is the one INLINE_COUNT adds.
            assertThat(countSql).hasSize(2);
            assertThat(inlineSql).hasSize(2).last().isEqualTo(countSql.get(0));
            assertThat(inline).isEqualTo(counted);
            assertThat(inline.get(0).total()).hasValue(total[0]);
            assertThat(total[0]).isPositive().isLessThan(100 * 50);
        }
    }

    @TckTest
    void ac_exe_19_a_refused_treat_site_is_mq4016_before_any_statement(TckDatabase db) {
        var treated = treatedPayments();
        var type = Expr.typeOf(String.class, TreatTestSupport.ROOT);
        var counted = Agg.<String>count(TreatTestSupport.ID);
        var byType = ModelQuery.builder(TreatTestSupport.ROOT,
                row -> row.get(type).getSimpleName() + ":" + row.get(counted))
                .select(SelectSet.of(type, counted)).groupBy(type).build();
        // Cards with a number, per status: the treat narrows the grouped statement's rows (R-COL-35).
        var numbered = ModelQuery.builder(TreatTestSupport.ROOT,
                row -> row.get(TreatTestSupport.STATUS) + ":" + row.get(counted))
                .select(SelectSet.of(TreatTestSupport.STATUS, counted)).groupBy(TreatTestSupport.STATUS)
                .where(f -> f.isNotNull(TreatTestSupport.NUMBER)).build();
        try (SessionFactory schema = TreatTestSupport.schema(db)) {
            schema.inTransaction(InlineCountTest::insertPayments);
            // The page would read COUNT's statement for the treated root's total, and the window for the groups':
            // either way COUNT's statements are checked and preflighted before the first statement runs.
            List<String> sql = statements(db, ds -> {
                serving(refusing(StatementShape.COUNT, true), false).run(ds, executor -> assertMq4016(
                        () -> executor.page(treated, PageSpec.of(0, 2), CountMode.INLINE_COUNT),
                        "the preflight refuses the count (COUNT statement): refused by the test"));
                // A grouped statement and the count of its groups are both preflighted as GROUPED (D-159).
                serving(refusing(StatementShape.GROUPED, true), true).run(ds, executor -> assertMq4016(
                        () -> executor.page(numbered, PageSpec.of(0, 1), CountMode.INLINE_COUNT),
                        "the preflight refuses the count (GROUPED statement): refused by the test"));
                serving(refusing(StatementShape.GROUPED, false), true).run(ds, executor -> assertMq4016(
                        () -> executor.page(byType, PageSpec.of(0, 2), CountMode.INLINE_COUNT),
                        "refused by the test"));
            });
            assertThat(sql).isEmpty();
            // Admitted, the grouped page reads its total, the two numbered cards' statuses, from the window alone.
            List<Slice<String>> admitted = new ArrayList<>();
            List<String> admittedSql = statements(db, ds -> serving(new CountingRenderer(), true).run(ds,
                    executor -> admitted.add(executor.page(numbered, PageSpec.of(0, 1), CountMode.INLINE_COUNT))));
            assertThat(admitted).singleElement().satisfies(slice -> {
                assertThat(slice.total()).hasValue(2);
                assertThat(slice.hasNext()).isTrue();
            });
            assertThat(admittedSql).singleElement().asString().contains("count(1) over()");
        }
    }

    // ---- AC-EXE-20

    @TckTest
    void ac_exe_20_a_shape_the_window_would_miscount_runs_counts_statements_and_logs_why_once(TckDatabase db) {
        var page = PageSpec.of(1, 20);
        assertFallsBack(db, InlineCountTest::withOrders,
                new Case<>(NEW.customize((spec, joins, query, cb, phase) -> query.distinct(true)).build(), page),
                "the statement is DISTINCT");
        assertFallsBack(db, InlineCountTest::withOrders,
                new Case<>(ORDER_ROWS.where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001"))).build(), page),
                "COUNT counts distinct roots over a to-many join no selected column reads");
        var customizerJoin = ORDER_ROWS.customize((spec, joins, query, cb, phase) -> query.where(
                cb.equal(query.getRoots().iterator().next().join("items").get("productCode"), "P001"))).build();
        assertFallsBack(db, InlineCountTest::withOrders, new Case<>(customizerJoin, page),
                "COUNT counts distinct roots over a to-many join no selected column reads");
        // A LEFT to-many join in step 1's key query alone narrows no phase, so R-PAG-15 admits it, but it repeats
        // the keys of an order with several items, which the MODEL statement COUNT counts holds once.
        var keyQueryJoin = ORDER_ROWS.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10))
                .customize((spec, joins, query, cb, phase) -> {
                    if (phase == Phase.PRIMARY_KEY) {
                        query.getRoots().iterator().next().join("items", LEFT);
                    }
                }).build();
        assertFallsBack(db, InlineCountTest::withOrders, new Case<>(keyQueryJoin, PageSpec.of(1, 50)),
                "step 1's key query carries a to-many join, which repeats a key COUNT counts once");
        assertFallsBack(db, (ds, work) -> withOrders(ds, JoinTestSupport::withoutServices, work),
                new Case<>(NEW.build(), page), "the provider renders no window over the page statement");
    }

    @TckTest
    void ac_exe_20_a_treated_root_and_a_client_side_count_run_counts_statements_and_log_why_once(TckDatabase db) {
        var type = Expr.typeOf(String.class, TreatTestSupport.ROOT);
        var counted = Agg.<String>count(TreatTestSupport.ID);
        var byType = ModelQuery.builder(TreatTestSupport.ROOT,
                row -> row.get(type).getSimpleName() + ":" + row.get(counted))
                .select(SelectSet.of(type, counted)).groupBy(type).build();
        var page = PageSpec.of(0, 3);
        try (SessionFactory schema = TreatTestSupport.schema(db)) {
            schema.inTransaction(InlineCountTest::insertPayments);
            assertFallsBack(db, InlineCountTest::withPayments, new Case<>(treatedPayments(), page),
                    "COUNT counts a treated, ungrouped root on a freshly built statement");
            // With the window served, but no count of groups, COUNT counts the grouped statement's rows.
            assertFallsBack(db, serving(new CountingRenderer(), false), new Case<>(byType, page),
                    "COUNT counts the groups client-side: no ProviderSupport counts groups");
            // A refused GROUPED_COUNT site counts them client-side too (D-159).
            assertFallsBack(db, serving(refusing(StatementShape.GROUPED_COUNT, false), true),
                    new Case<>(byType, page),
                    "COUNT counts the groups client-side: the treat renderer refuses their count");
            // Admitted, the provider counts the groups in SQL, and the window counts them on the page statement.
            List<Slice<String>> inline = new ArrayList<>();
            List<String> sql = statements(db, ds -> serving(new CountingRenderer(), true).run(ds,
                    executor -> inline.add(executor.page(byType, page, CountMode.INLINE_COUNT))));
            assertThat(sql).singleElement().asString().contains("count(1) over()");
            assertThat(inline).singleElement().satisfies(slice -> {
                assertThat(slice.content()).hasSize(3);
                assertThat(slice.total()).hasValue(4);
                assertThat(slice.hasNext()).isTrue();
            });
        }
    }

    /**
     * Asserts that {@code c} under {@code INLINE_COUNT} runs exactly {@code COUNT}'s statements and returns its
     * {@code Slice}, twice on one executor and once on another, and logs {@code reason} once per executor.
     */
    private static <E, M> void assertFallsBack(TckDatabase db, Executing<E> executing, Case<E, M> c, String reason) {
        List<Slice<M>> counted = new ArrayList<>();
        List<String> countSql = statements(db, ds -> executing.run(ds,
                executor -> counted.add(c.run(executor, CountMode.COUNT))));
        List<Slice<M>> inline = new ArrayList<>();
        List<String> logged = new ArrayList<>();
        List<String> inlineSql = statements(db, ds -> logging(logged, () -> {
            executing.run(ds, executor -> {
                inline.add(c.run(executor, CountMode.INLINE_COUNT));
                inline.add(c.run(executor, CountMode.INLINE_COUNT));
            });
            executing.run(ds, executor -> inline.add(c.run(executor, CountMode.INLINE_COUNT)));
        }));
        assertThat(countSql).as(reason).hasSizeGreaterThan(1);
        assertThat(inlineSql).as(reason).isEqualTo(Stream.of(countSql, countSql, countSql).flatMap(List::stream)
                .toList());
        assertThat(inline).as(reason).hasSize(3).allSatisfy(slice -> assertThat(slice).isEqualTo(counted.get(0)));
        assertThat(counted.get(0).content()).as(reason).isNotEmpty();
        assertThat(logged).as(reason).hasSize(2).allSatisfy(line -> assertThat(line).contains(reason));
    }

    /** Treated payments: a card's number beside each id, every payment kept. */
    private static ModelQuery<Payment, ?, String> treatedPayments() {
        return ModelQuery.builder(TreatTestSupport.ROOT,
                        row -> row.get(TreatTestSupport.ID) + ":" + row.get(TreatTestSupport.NUMBER))
                .select(SelectSet.of(TreatTestSupport.ID, TreatTestSupport.NUMBER))
                .primaryKey(PrimaryKey.of(TreatTestSupport.ID))
                .orderBy(TreatTestSupport.ID.asc())
                .build();
    }

    /** A base payment, two cards, a bank and a premium card: four types over five rows. */
    private static void insertPayments(EntityManager em) {
        em.persist(payment(new Payment(), 1, null));
        em.persist(payment(new TreatTestSupport.Card(), 2, "1111"));
        em.persist(payment(new TreatTestSupport.Card(), 3, null));
        em.persist(payment(new TreatTestSupport.Bank(), 4, null));
        em.persist(payment(new TreatTestSupport.Premium(), 5, "5555"));
    }

    private static Payment payment(Payment payment, long id, String number) {
        payment.id = id;
        payment.status = "s" + id;
        if (payment instanceof TreatTestSupport.Card card) {
            card.number = number;
        }
        return payment;
    }

    /** Hibernate's own provider over the treat schema. */
    private static void withPayments(DataSource ds, Consumer<ModelQueryExecutor<Payment>> work) {
        try (SessionFactory factory = TreatTestSupport.factory(ds)) {
            factory.inSession(em -> work.accept(ModelQueryExecutor.create(em, Payment.class,
                    ModelQueryConfig.defaults())));
        }
    }

    /**
     * A provider serving {@code renderer}, Hibernate's sources and its window, and its count of groups where
     * {@code countsGroups}; the executor is made inside, as it asks for the window once.
     */
    private static Executing<Payment> serving(TreatRenderer renderer, boolean countsGroups) {
        return (ds, work) -> {
            try (SessionFactory factory = TreatTestSupport.factory(ds)) {
                Runnable session = () -> ServingProvider.renderingSources(() -> factory.inSession(em -> work.accept(
                        ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults()))));
                ServingProvider.serving(factory, renderer,
                        countsGroups ? () -> ServingProvider.countingGroups(session) : session);
            }
        };
    }

    /**
     * Renders as {@link CountingRenderer} does and refuses {@code shape}: each statement of it at its preflight where
     * {@code atPreflight}, else each of its sites.
     */
    private static TreatRenderer refusing(StatementShape shape, boolean atPreflight) {
        var rendering = new CountingRenderer();
        return new TreatRenderer() {
            @Override
            public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
                return rendering.treat(cb, from, subtype);
            }

            @Override
            public Optional<String> refusal(TreatSite site) {
                return !atPreflight && site.shape() == shape ? Optional.of("refused by the test") : Optional.empty();
            }

            @Override
            public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement,
                    StatementShape statementShape, List<ExpectedNarrowing> expected) {
                return atPreflight && statementShape == shape ? Optional.of("refused by the test") : Optional.empty();
            }
        };
    }

    private static void assertMq4016(Runnable action, String reason) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining(reason)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4016);
    }

    /** The statements {@code work} runs, without the schema's own. */
    private static List<String> statements(TckDatabase db, SqlSnapshots.SqlWork work) {
        return SqlSnapshots.capture(db, work).stream()
                .filter(sql -> !sql.startsWith("create ") && !sql.startsWith("drop ")).toList();
    }

    /** Collects into {@code logged} the executor's {@code DEBUG} lines that report an {@code INLINE_COUNT} fallback. */
    private static void logging(List<String> logged, Runnable work) {
        Logger logger = Logger.getLogger("com.rey.modelquery.jpa.DefaultModelQueryExecutor");
        Level previous = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = new SimpleFormatter().formatMessage(record);
                if (message.contains(FALLBACK)) {
                    // FINE is System.Logger's DEBUG on java.util.logging.
                    assertThat(record.getLevel()).isEqualTo(Level.FINE);
                    logged.add(message);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.FINE);
        logger.addHandler(handler);
        try {
            work.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    private static void withOrders(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }

    private static void withOrders(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        withOrders(ds, Runnable::run, work);
    }

    /** An executor over {@code ds}, made and used inside {@code around} (no ProviderSupport, say). */
    private static void withOrders(DataSource ds, Consumer<Runnable> around,
            Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            around.accept(() -> sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults()))));
        }
    }
}
