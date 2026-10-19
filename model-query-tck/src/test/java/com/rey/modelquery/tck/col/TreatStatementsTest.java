package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.core.TreatUse;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.TreatTestSupport.CountingRenderer;
import com.rey.modelquery.tck.col.TreatTestSupport.ServingProvider;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.AnchorEntities.JdBank;
import com.rey.modelquery.tck.inheritance.AnchorEntities.JdCard;
import com.rey.modelquery.tck.inheritance.AnchorEntities.JdPayment;
import com.rey.modelquery.tck.inheritance.AnchorEntities.JdPremium;
import com.rey.modelquery.tck.inheritance.AnchorEntities.StBank;
import com.rey.modelquery.tck.inheritance.AnchorEntities.StCard;
import com.rey.modelquery.tck.inheritance.AnchorEntities.StCustomer;
import com.rey.modelquery.tck.inheritance.AnchorEntities.StItem;
import com.rey.modelquery.tck.inheritance.AnchorEntities.StPayment;
import com.rey.modelquery.tck.inheritance.AnchorEntities.StPremium;
import com.rey.modelquery.tck.inheritance.AnchorEntities.TpBank;
import com.rey.modelquery.tck.inheritance.AnchorEntities.TpCard;
import com.rey.modelquery.tck.inheritance.AnchorEntities.TpPayment;
import com.rey.modelquery.tck.inheritance.AnchorEntities.TpPremium;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * The engine's treated statements on each inheritance strategy (R-COL-35, R-EXE-05, R-VND-26): the root-id count, the
 * hidden {@code mq_0id} anchor and the refusals before any statement (AC-COL-52, AC-VND-17, AC-VND-20).
 */
class TreatStatementsTest {

    private static final List<Strategy<?, ?>> STRATEGIES = List.of(
            new Strategy<>("single-table", StPayment.class, StCard.class, StBank.class, StPremium.class,
                    StCustomer.class, StItem.class),
            new Strategy<>("joined", JdPayment.class, JdCard.class, JdBank.class, JdPremium.class),
            new Strategy<>("table-per-class", TpPayment.class, TpCard.class, TpBank.class, TpPremium.class));

    @TckTest
    void ac_col_52_totals_count_the_root_id_and_content_selects_the_hidden_anchor(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            countsAndAnchor(db, strategy);
        }
    }

    private static <P, C extends P> void countsAndAnchor(TckDatabase db, Strategy<P, C> s) {
        var keyless = ModelQuery.builder(s.root, row -> row.get(s.status) + ":" + row.get(s.number))
                .select(SelectSet.of(s.status, s.number)).build();
        var ordered = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                .primaryKey(PrimaryKey.of(s.id)).orderBy(s.number.asc()).build();
        List<ModelQuery<P, ?, String>> filtered = List.of(
                s.statuses().where(f -> f.isNull(s.number)).build(),
                s.statuses().where(f -> f.ne(s.number, "1111")).build(),
                s.statuses().where(f -> f.notIn(s.number, List.of("1111"))).build(),
                s.statuses().where(f -> f.not(n -> n.isNotNull(s.number))).build());
        List<List<String>> matching = List.of(List.of("s1", "s3", "s4"), List.of("s1", "s3", "s4", "s5"),
                List.of("s1", "s3", "s4", "s5"), List.of("s1", "s3", "s4"));
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            List<String> sql = SqlSnapshots.assertMatches(db, "col-52-" + s.name, statements(db,
                    ds -> s.serving(ds, executor -> {
                assertThat(executor.list(keyless, Limit.of(10))).containsExactlyInAnyOrder(
                        "s1:null", "s2:1111", "s3:null", "s4:null", "s5:5555");
                assertThat(executor.count(keyless)).isEqualTo(5);
                var page = executor.page(ordered, PageSpec.of(0, 10), CountMode.COUNT);
                assertThat(page.total()).hasValue(5);
                assertThat(page.content()).containsExactlyInAnyOrder("s1", "s2", "s3", "s4", "s5");
                for (int i = 0; i < filtered.size(); i++) {
                    assertThat(executor.count(filtered.get(i))).as(s.name + " filter " + i)
                            .isEqualTo(matching.get(i).size());
                    assertThat(executor.list(filtered.get(i), Limit.of(10))).as(s.name + " filter " + i)
                            .containsExactlyInAnyOrderElementsOf(matching.get(i));
                }
            })));
            // Statements: the keyless list and count, the page's count and content, then a count and a list per
            // filter. Every count is the root id's, with no selection or order; a select-only or order-only treat
            // is absent from it.
            assertThat(sql).hasSize(12);
            for (int count : List.of(1, 2, 4, 6, 8, 10)) {
                assertThat(sql.get(count)).as(s.name + " statement " + count).matches("select count\\(\\w+\\.id\\) .*")
                        .doesNotContain("order by");
            }
            // No read of the number (a TABLE_PER_CLASS union lists its columns unqualified, as it always does).
            assertThat(sql.get(1)).doesNotContainPattern("\\w+\\.number");
            assertThat(sql.get(2)).doesNotContainPattern("\\w+\\.number");
            // Content that selects no root id selects it last, the hidden mq_0id (the provider renders no alias);
            // the page's model selects the id itself, first.
            for (int content : List.of(0, 5, 7, 9, 11)) {
                assertThat(sql.get(content)).as(s.name + " statement " + content).containsPattern(",\\w+\\.id from ");
            }
            assertThat(sql.get(3)).matches("select \\w+\\.id,.*").doesNotContainPattern(",\\w+\\.id from ")
                    .contains("order by");
            schema.inSession(em -> {
                var built = keyless.buildQuery(em.getCriteriaBuilder(), Phase.MODEL,
                        RenderOptions.portable().withTreatRenderer(new CountingRenderer()));
                var selected = built.query().getSelection().getCompoundSelectionItems();
                assertThat(selected).hasSize(3);
                assertThat(selected.get(2).getAlias()).isEqualTo("mq_0id");
                assertThat(built.selection().fields()).containsExactly(s.status, s.number);
                assertThat(keyless.select().fields()).containsExactly(s.status, s.number);
            });
        }
    }

    /** The statements {@code work} runs, without the temporary tables a factory over a hierarchy creates. */
    private static List<String> statements(TckDatabase db, SqlSnapshots.SqlWork work) {
        return SqlSnapshots.capture(db, work).stream()
                .filter(sql -> !sql.startsWith("create ") && !sql.startsWith("drop ")).toList();
    }

    @TckTest
    void ac_col_52_the_count_keeps_the_row_changing_joins_of_expressions(TckDatabase db) {
        @SuppressWarnings("unchecked")
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        TableField<StPayment, StCustomer> customer = TableField.join(s.root, "customer", JoinType.INNER);
        TableField<StPayment, StItem> items = TableField.join(s.root, "items", JoinType.LEFT);
        var name = ColumnField.of(String.class, customer, "name", String.class);
        var label = ColumnField.of(String.class, items, "label", String.class);
        // An INNER join inside an expression drops the customer-less rows 4 and 5: rows 1 and 3 have no number.
        var innerExpression = ModelQuery.builder(s.root, row -> row.get(s.status))
                .select(SelectSet.of(s.status, Expr.coalesce(name, "?"))).where(f -> f.isNull(s.number)).build();
        // A LEFT to-many join inside an expression returns a row per item: row 1 twice, rows 3 and 4 once.
        var toManyExpression = ModelQuery.builder(s.root, row -> row.get(s.status))
                .select(SelectSet.of(s.status, Expr.coalesce(label, "none"))).where(f -> f.isNull(s.number)).build();
        // An or(...) reuses the expression's INNER join: rows 1 and 2 by name, row 3 by its missing number.
        var orReusesInner = ModelQuery.builder(s.root, row -> row.get(s.status))
                .select(SelectSet.of(s.status, Expr.coalesce(name, "?")))
                .where(f -> f.or(a -> a.eq(name, "ann"), a -> a.isNull(s.number))).build();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(em -> {
                s.insertRows(em);
                StCustomer ann = customer(em, 1, "ann");
                StCustomer bob = customer(em, 2, "bob");
                em.find(StPayment.class, 1L).customer = ann;
                em.find(StPayment.class, 2L).customer = ann;
                em.find(StPayment.class, 3L).customer = bob;
                item(em, 1, "a", 1L);
                item(em, 2, "b", 1L);
                item(em, 3, "c", 2L);
            });
            assertThat(statements(db, ds -> s.serving(ds, executor -> {
                assertThat(executor.list(innerExpression, Limit.of(20)))
                        .containsExactlyInAnyOrder("s1", "s3");
                assertThat(executor.count(innerExpression)).isEqualTo(2);
                assertThat(executor.list(toManyExpression, Limit.of(20)))
                        .containsExactlyInAnyOrder("s1", "s1", "s3", "s4");
                assertThat(executor.count(toManyExpression)).isEqualTo(4);
                assertThat(executor.list(orReusesInner, Limit.of(20)))
                        .containsExactlyInAnyOrder("s1", "s2", "s3");
                assertThat(executor.count(orReusesInner)).isEqualTo(3);
            }))).hasSize(6);
        }
    }

    private static StCustomer customer(EntityManager em, long id, String name) {
        var customer = new StCustomer();
        customer.id = id;
        customer.name = name;
        em.persist(customer);
        return customer;
    }

    private static void item(EntityManager em, long id, String label, long payment) {
        var item = new StItem();
        item.id = id;
        item.label = label;
        item.payment = em.find(StPayment.class, payment);
        em.persist(item);
    }

    @TckTest
    void ac_col_52_distinct_and_set_operations_needing_the_anchor_are_refused_before_any_statement(TckDatabase db) {
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        var distinct = ModelQuery.builder(s.root, row -> row.get(s.number)).select(SelectSet.of(s.number))
                .customize((spec, joins, statement, cb, phase) -> statement.distinct(true)).build();
        var distinctWithId = ModelQuery.builder(s.root, row -> row.get(s.number))
                .select(SelectSet.of(s.id, s.number)).primaryKey(PrimaryKey.of(s.id))
                .customize((spec, joins, statement, cb, phase) -> statement.distinct(true)).build();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            assertThat(statements(db,ds -> s.serving(ds, executor -> {
                assertRefused(() -> executor.list(distinct, Limit.of(10)), "distinct");
                assertRefused(() -> executor.count(distinct), "distinct");
            }))).isEmpty();
            assertThat(statements(db,ds -> s.inSession(ds, new CountingRenderer(), em -> {
                var branches = ModelQueryExecutor.create(em, Branch.class, ModelQueryConfig.defaults());
                assertRefused(() -> branches.list(setOperation(s, false), Limit.of(10)), "unionAll");
                // Selecting the root id in the branches passes the anchor check; this provider renders no source.
                assertThatThrownBy(() -> branches.list(setOperation(s, true), Limit.of(10)))
                        .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                        .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                        .isEqualTo(MqCode.MQ4014);
            }))).isEmpty();
            assertThat(statements(db,ds -> s.serving(ds,
                    executor -> assertThat(executor.list(distinctWithId, Limit.of(10))).hasSize(5)))).hasSize(1);
        }
    }

    /** A {@code union} of two branches reading the card's number, keyed by the id or by the status. */
    private static ModelQuery<Branch, ?, String> setOperation(Strategy<StPayment, StCard> s, boolean withId) {
        var number = ColumnField.of(Branch.class, s.root.treat(StCard.class), "number", String.class)
                .named("number");
        ColumnField<Branch, StPayment, ?> key = withId
                ? ColumnField.of(Branch.class, s.root, "id", Long.class).named("key")
                : ColumnField.of(Branch.class, s.root, "status", String.class).named("key");
        var left = SourceQuery.builder(Branch.class, s.root, SelectSet.of(key, number), PrimaryKey.of(key)).build();
        var right = SourceQuery.builder(Branch.class, s.root, SelectSet.of(key, number), PrimaryKey.of(key)).build();
        var root = TableField.source(Branch.class);
        return ModelQuery.builder(root, row -> "branch", left.union(right))
                .select(SelectSet.of(ColumnField.over(String.class, root, key))).build();
    }

    @TckTest
    void ac_vnd_17_later_page_shapes_counts_and_child_statements_are_refused_before_any_statement(TckDatabase db) {
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        var keyset = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                .primaryKey(PrimaryKey.of(s.id)).orderBy(s.number.asc().nullsLast()).keyset().build();
        var filtered = s.statuses().where(f -> f.isNull(s.number)).build();
        var exported = new ArrayList<String>();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            // Page 1 renders no keyset predicate; the later pages' OR-expansion is checked before it.
            Predicate<TreatSite> laterPages = site -> site.uses().contains(TreatUse.DISJUNCTION);
            assertThat(statements(db,ds -> s.serving(ds, refusing(laterPages), executor -> {
                assertRefused(() -> executor.export(keyset, ExportOptions.of(2), rows -> rows, exported::add),
                        "PREDICATE, DISJUNCTION");
                assertThat(executor.list(keyset, Limit.of(10))).hasSize(5);
            }))).hasSize(1);
            assertThat(exported).isEmpty();
            Predicate<TreatSite> counts = site -> site.shape() == StatementShape.COUNT;
            assertThat(statements(db,ds -> s.serving(ds, refusing(counts), executor -> {
                assertRefused(() -> executor.page(filtered, PageSpec.of(0, 2), CountMode.COUNT), "COUNT");
                assertRefused(() -> executor.count(filtered), "COUNT");
                assertThat(executor.page(filtered, PageSpec.of(0, 2), CountMode.NO_COUNT).content()).hasSize(2);
            }))).hasSize(1);
        }
    }

    @TckTest
    void ac_vnd_17_a_treated_node_in_a_child_statement_is_refused_with_an_accepting_renderer(TckDatabase db) {
        try (SessionFactory schema = TreatTestSupport.schema(db)) {
            assertThat(statements(db,ds -> {
                try (SessionFactory factory = TreatTestSupport.factory(ds)) {
                    ServingProvider.serving(factory, new CountingRenderer(), () -> factory.inSession(em -> {
                        var executor = ModelQueryExecutor.create(em, TreatTestSupport.Payment.class,
                                ModelQueryConfig.defaults());
                        for (boolean treatedKey : List.of(true, false)) {
                            assertRefused(() -> executor.list(TreatExecutionTest.fetchQuery(treatedKey),
                                    Limit.of(0)), "child statement");
                        }
                    }));
                }
            })).isEmpty();
        }
    }

    @TckTest
    void ac_vnd_20_treated_write_filters_and_returning_models_are_refused_on_every_path(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            writes(db, strategy);
        }
    }

    private static <P, C extends P> void writes(TckDatabase db, Strategy<P, C> s) {
        var update = ModelUpdate.builder(s.root).primaryKey(PrimaryKey.of(s.id)).set(s.status, "paid")
                .where(f -> f.eq(s.number, "1111")).build();
        var keyFirstUpdate = ModelUpdate.builder(s.root).primaryKey(PrimaryKey.of(s.id)).set(s.status, "paid")
                .where(f -> f.eq(s.number, "1111")).chunked(ChunkOptions.size(2)).build();
        var delete = ModelDelete.builder(s.root).primaryKey(PrimaryKey.of(s.id))
                .where(f -> f.isNull(s.number)).build();
        var keyFirstDelete = ModelDelete.builder(s.root).primaryKey(PrimaryKey.of(s.id))
                .where(f -> f.isNull(s.number)).chunked(ChunkOptions.size(2)).build();
        var plainUpdate = ModelUpdate.builder(s.root).primaryKey(PrimaryKey.of(s.id)).set(s.status, "paid")
                .where(f -> f.eq(s.id, 1L)).build();
        var plainDelete = ModelDelete.builder(s.root).primaryKey(PrimaryKey.of(s.id))
                .where(f -> f.eq(s.id, 1L)).build();
        var returning = ModelQuery.builder(s.root, row -> row.get(s.number)).select(SelectSet.of(s.number)).build();
        var persist = ModelPersist.of(InsertColumns.<String, P>of(s.root).add(s.status, value -> value),
                Long.class, "paid");
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            assertThat(statements(db,ds -> s.inSession(ds, new CountingRenderer(), em -> {
                var executor = ModelQueryExecutor.create(em, s.payment, ModelQueryConfig.defaults());
                em.getTransaction().begin();
                try {
                    assertRefused(() -> executor.update(update), "bulk write");
                    assertRefused(() -> executor.update(keyFirstUpdate), "bulk write");
                    assertRefused(() -> executor.delete(delete), "bulk write");
                    assertRefused(() -> executor.delete(keyFirstDelete), "bulk write");
                    assertRefused(() -> executor.updateReturning(plainUpdate, returning), "returning model");
                    assertRefused(() -> executor.deleteReturning(plainDelete, returning), "returning model");
                    assertRefused(() -> executor.persist(persist, returning), "returning model");
                } finally {
                    em.getTransaction().rollback();
                }
            }))).as(s.name).isEmpty();
        }
    }

    private static void assertRefused(Runnable action, String reason) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining(reason)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4016);
    }

    /** Renders as {@link CountingRenderer} does and refuses the sites {@code refused} matches. */
    private static TreatRenderer refusing(Predicate<TreatSite> refused) {
        var rendering = new CountingRenderer();
        return new TreatRenderer() {
            @Override
            public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
                return rendering.treat(cb, from, subtype);
            }

            @Override
            public Optional<String> refusal(TreatSite site) {
                return refused.test(site) ? Optional.of("not in the test table") : Optional.empty();
            }

            @Override
            public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement,
                    StatementShape shape) {
                return Optional.empty();
            }
        };
    }

    /** One mapped hierarchy of a strategy: a root with {@code status}, a card subtype with {@code number}. */
    private static final class Strategy<P, C extends P> {
        final String name;
        final Class<P> payment;
        final Class<C> card;
        final Class<? extends P> bank;
        final Class<? extends C> premium;
        final TableField<P, P> root;
        final ColumnField<String, P, Long> id;
        final ColumnField<String, P, String> status;
        final ColumnField<String, C, String> number;

        final List<Class<?>> others;

        Strategy(String name, Class<P> payment, Class<C> card, Class<? extends P> bank, Class<? extends C> premium,
                Class<?>... others) {
            this.name = name;
            this.others = List.of(others);
            this.payment = payment;
            this.card = card;
            this.bank = bank;
            this.premium = premium;
            this.root = TableField.root(payment);
            this.id = ColumnField.of(String.class, root, "id", Long.class);
            this.status = ColumnField.of(String.class, root, "status", String.class);
            this.number = ColumnField.of(String.class, root.treat(card), "number", String.class);
        }

        ModelQuery.Builder<P, ?, String> statuses() {
            return ModelQuery.builder(root, row -> row.get(status)).select(SelectSet.of(status));
        }

        private Configuration configuration() {
            Configuration cfg = new Configuration().addAnnotatedClass(payment).addAnnotatedClass(card)
                    .addAnnotatedClass(bank).addAnnotatedClass(premium);
            others.forEach(cfg::addAnnotatedClass);
            return cfg;
        }

        SessionFactory schema(TckDatabase db) {
            return configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                    .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop").buildSessionFactory();
        }

        /** Rows 1 to 5: a base payment, a card with a number and one without, a bank, a premium card. */
        void insertRows(EntityManager em) {
            em.persist(row(payment, 1, null));
            em.persist(row(card, 2, "1111"));
            em.persist(row(card, 3, null));
            em.persist(row(bank, 4, null));
            em.persist(row(premium, 5, "5555"));
        }

        private static Object row(Class<?> type, long id, String number) {
            try {
                Object row = type.getDeclaredConstructor().newInstance();
                type.getField("id").set(row, id);
                type.getField("status").set(row, "s" + id);
                if (number != null) {
                    type.getField("number").set(row, number);
                }
                return row;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        void serving(DataSource ds, Consumer<ModelQueryExecutor<P>> work) {
            serving(ds, new CountingRenderer(), work);
        }

        void serving(DataSource ds, TreatRenderer renderer, Consumer<ModelQueryExecutor<P>> work) {
            inSession(ds, renderer, em -> work.accept(ModelQueryExecutor.create(em, payment,
                    ModelQueryConfig.defaults())));
        }

        /** {@code work} in a session of a factory over {@code ds} whose provider serves {@code renderer}. */
        void inSession(DataSource ds, TreatRenderer renderer, Consumer<EntityManager> work) {
            Configuration cfg = configuration();
            cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
            cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
            try (SessionFactory factory = cfg.buildSessionFactory()) {
                ServingProvider.serving(factory, renderer, () -> factory.inSession(work::accept));
            }
        }
    }

    /** A source branch's model. */
    public static final class Branch {}
}
