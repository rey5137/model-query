package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.ExpectedNarrowing;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.core.TreatUse;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.TreatTestSupport.CountingRenderer;
import com.rey.modelquery.tck.col.TreatTestSupport.ServingProvider;
import com.rey.modelquery.tck.fch.FetchTestSupport;
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
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * The engine's treated statements on each inheritance strategy (R-COL-35, R-EXE-05, R-VND-26): the root-id count, the
 * hidden {@code mq_0id} anchor, the refusals before any statement and the statements each call preflights (AC-COL-52,
 * AC-VND-17, AC-VND-20, AC-VND-21).
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

    @TckTest
    void ac_vnd_21_each_statement_a_call_runs_and_each_later_keyset_page_shape_is_preflighted(TckDatabase db) {
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        var calls = new PreflightCalls(s);
        var preflights = new Preflights();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            statements(db, ds -> s.serving(ds, preflights, executor -> {
                for (PreflightCall call : calls.all(executor)) {
                    call.run().run();
                    assertThat(preflights.shapes).as(call.name()).isEqualTo(call.shapes());
                    if (call.parameters() != null) {
                        // Page 1 binds nothing; a later page binds a placeholder per non-NULL cursor value: the order
                        // keys' and the id's, or fewer after a NULL at a key, forwards and then before.
                        assertThat(preflights.parameters).as(call.name()).isEqualTo(call.parameters());
                    }
                    preflights.clear();
                }
                // Without a treated node nothing is built for the preflight, and the renderer is not called.
                executor.list(s.statuses().build(), Limit.of(10));
                executor.count(s.statuses().build());
                executor.page(s.statuses().primaryKey(PrimaryKey.of(s.id)).orderBy(s.status.asc()).keyset().build(),
                        KeysetSpec.first(2));
                assertThat(preflights.shapes).isEmpty();
            }));
        }
    }

    @TckTest
    void ac_vnd_21_a_refused_preflight_is_mq4016_before_any_statement_of_the_call(TckDatabase db) {
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        var calls = new PreflightCalls(s);
        var preflights = new Preflights();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            // The last statement preflighted is refused, so every statement the call runs was preflighted first.
            assertThat(statements(db, ds -> s.serving(ds, preflights, executor -> {
                for (PreflightCall call : calls.all(executor)) {
                    preflights.refused = call.shapes().size() - 1;
                    assertThatThrownBy(() -> call.run().run()).as(call.name())
                            .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                            .hasMessageContaining("refused by the test")
                            .hasMessageContaining("(" + call.shapes().get(call.shapes().size() - 1) + " statement)")
                            .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                            .isEqualTo(MqCode.MQ4016);
                    assertThat(preflights.shapes).as(call.name()).isEqualTo(call.shapes());
                    preflights.clear();
                }
            }))).isEmpty();
        }
    }

    @TckTest
    @SuppressWarnings("unchecked")
    void ac_vnd_17_an_exists_nested_under_a_treated_exists_path_is_mq4016_before_any_statement(TckDatabase db) {
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        TableField<StPayment, StCard> treated = s.root.treat(StCard.class);
        TableField<StCard, StItem> cardItems = TableField.join(treated, "items", JoinType.LEFT);
        TableField<StCard, StItem> nestedItems = TableField.join(treated, "items", JoinType.INNER);
        TableField<StItem, StPayment> itemPayment = TableField.join(cardItems, "payment", JoinType.INNER);
        // The nested sub-select would correlate on the outer exists' treated root (a treated root resolves to no join)
        // or on its treated join, which the renderer's table has no cell for.
        var onTreatedRoot = statusesWhere(s, f -> f.exists(treated, e -> e.exists(nestedItems)));
        var onTreatedJoin = statusesWhere(s, f -> f.exists(cardItems, e -> e.exists(itemPayment)));
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            assertThat(statements(db, ds -> {
                s.serving(ds, executor -> assertTreatRefused(() -> executor.list(onTreatedRoot, Limit.of(10))));
                // A renderer that admits every site, then the provider's own renderer: JoinContext refuses the root.
                Configuration cfg = s.configuration();
                cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
                cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
                try (SessionFactory factory = cfg.buildSessionFactory()) {
                    factory.inSession(em -> {
                        var executor = ModelQueryExecutor.create(em, s.payment, ModelQueryConfig.defaults());
                        assertTreatRefused(() -> executor.list(onTreatedRoot, Limit.of(10)));
                        assertTreatRefused(() -> executor.list(onTreatedJoin, Limit.of(10)));
                    });
                }
            })).isEmpty();
        }
    }

    private static void assertTreatRefused(Runnable action) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4016);
    }

    @TckTest
    @SuppressWarnings("unchecked")
    void ac_vnd_21_each_preflighted_statement_carries_the_narrowings_its_own_build_declares(TckDatabase db) {
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        TableField<StPayment, StItem> items = TableField.join(s.root, "items", JoinType.LEFT);
        TableField<StCard, StItem> cardItems = TableField.join(s.root.treat(StCard.class), "items", JoinType.LEFT);
        var card = new ExpectedNarrowing(ExpectedNarrowing.Kind.EXISTS_ROOT, StPayment.class, Set.of(StCard.class));
        // Two equal exists, each over a path from its correlated root read as a card: two entries, in build order.
        var twice = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                .primaryKey(PrimaryKey.of(s.id))
                .where(f -> f.exists(cardItems).exists(cardItems));
        var offset = twice.build();
        var keyset = twice.orderBy(s.number.asc().nullsLast()).keyset().build();
        // A treated read outside the exists, an in sub-select's own root and a correlated count's: none declared.
        var outside = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                .primaryKey(PrimaryKey.of(s.id)).where(f -> f.isNull(s.number).exists(items)).build();
        var sub = TableField.root(StPayment.class);
        var subId = ColumnField.of(Inner.class, sub, "id", Long.class);
        var treatedIds = SubSelect.of(ColumnField.of(Inner.class, sub.treat(StCard.class), "id", Long.class));
        var in = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                .primaryKey(PrimaryKey.of(s.id)).where(f -> f.in(s.id, treatedIds)).build();
        var counted = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status,
                Correlated.count(String.class, treatedIds, (x, outer) -> x.compare(subId, Op.EQ, outer.column(s.id)))))
                .primaryKey(PrimaryKey.of(s.id)).build();
        // An or reading the path's columns in some branches, and an or and a not reading the treated root in every
        // branch: declared, as Hibernate narrows them. An or reading the treated root in one branch only: not.
        TableField<StPayment, StCard> treated = s.root.treat(StCard.class);
        var label = ColumnField.of(String.class, cardItems, "label", String.class);
        var pathOr = statusesWhere(s, f -> f.exists(cardItems, e -> e.or(a -> a.eq(label, "a"), b -> b.isNull(label))));
        var everyOr = statusesWhere(s, f -> f.exists(treated, e -> e.or(a -> a.eq(s.number, "1111"),
                b -> b.eq(s.number, "5555"))));
        var not = statusesWhere(s, f -> f.exists(treated, e -> e.not(n -> n.eq(s.number, "1111"))));
        var someOr = statusesWhere(s, f -> f.exists(treated, e -> e.or(a -> a.eq(s.number, "1111"),
                b -> b.eq(label, "a"))));
        var preflights = new Preflights();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            statements(db, ds -> s.serving(ds, preflights, executor -> {
                executor.list(offset, Limit.of(10));
                assertThat(preflights.expected).containsExactly(List.of(card, card));
                preflights.clear();
                executor.page(offset, PageSpec.of(0, 2), CountMode.COUNT);
                assertThat(preflights.shapes).containsExactly(StatementShape.COUNT, StatementShape.CONTENT);
                assertThat(preflights.expected).containsExactly(List.of(card, card), List.of(card, card));
                preflights.clear();
                // Each later page shape is a build of its own, which declares the same two.
                executor.page(keyset, KeysetSpec.first(2));
                assertThat(preflights.expected).hasSize(5).containsOnly(List.of(card, card));
                preflights.clear();
                for (var declared : List.of(pathOr, everyOr, not)) {
                    executor.list(declared, Limit.of(10));
                    assertThat(preflights.expected).as(declared.toString()).containsExactly(List.of(card));
                    preflights.clear();
                }
                for (var none : List.of(outside, in, counted, someOr)) {
                    executor.list(none, Limit.of(10));
                    assertThat(preflights.shapes).as(none.toString()).containsExactly(StatementShape.CONTENT);
                    assertThat(preflights.expected).as(none.toString()).containsExactly(List.of());
                    preflights.clear();
                }
            }));
        }
    }

    /** {@code s}'s ids and statuses by primary key, filtered by {@code where}. */
    private static <P> ModelQuery<P, Long, String> statusesWhere(Strategy<P, ?> s,
            UnaryOperator<Filters<String>> where) {
        return ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                .primaryKey(PrimaryKey.of(s.id)).where(where).build();
    }

    @TckTest
    void ac_vnd_21_a_by_keys_call_preflights_with_no_keys_and_its_first_chunk_built_again(TckDatabase db) {
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        // A bind limit of 4 with a filter bind: the first chunk is built at 4 keys, then again smaller, at 2.
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(FetchTestSupport.limited(db, 1024, 4)));
        var filtered = statusesWhere(s, f -> f.isNull(s.number).ne(s.status, "x"));
        var preflights = new Preflights();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            List<String> sql = statements(db, ds -> s.inSession(ds, preflights, em -> {
                var executor = ModelQueryExecutor.create(em, s.payment, config);
                assertThat(executor.byKeys(filtered, List.of(1L, 2L, 3L, 4L, 5L)).values())
                        .containsExactly("s1", "s3", "s4");
                // The 4-key build, then the 2-key build that runs, a statement of its own.
                assertThat(preflights.shapes).containsExactly(StatementShape.CONTENT, StatementShape.CONTENT);
                assertThat(preflights.statements.get(1)).isNotSameAs(preflights.statements.get(0));
                preflights.clear();
                // The build that runs is refused: no statement ran.
                preflights.refused = 1;
                assertRefused(() -> executor.byKeys(filtered, List.of(1L, 2L, 3L, 4L, 5L)), "refused by the test");
                preflights.clear();
                // No keys: refused too, though no statement would run.
                preflights.refused = 0;
                assertRefused(() -> executor.byKeys(filtered, List.of()), "refused by the test");
                assertThat(preflights.expected).containsExactly(List.of());
            }));
            // Chunks of 2, 2 and 1 keys.
            assertThat(sql).hasSize(3);
        }
    }

    @TckTest
    void ac_vnd_21_hibernate_admits_an_exists_root_only_where_it_renders_the_declared_rows(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            hibernateExists(db, strategy);
        }
    }

    /**
     * On {@code s}, a {@code not exists} whose correlated root is treated as the card, through Hibernate's preflight
     * with that root declared (D-157): the card's own column admitted and run everywhere. On {@code TABLE_PER_CLASS},
     * whose card has a premium subtype, the union keeps the root's table: its rows read with a null id under the
     * card's own column, which the id correlation never matches, but with their id under the inherited
     * {@code status}, through a {@code CASE} guard that an {@code is null} matches, so that is refused there and
     * admitted where no row of the root's table is read. On {@code SINGLE_TABLE}, an engine exists through the treated
     * card whose or reads its items' label runs as declared.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <P, C extends P> void hibernateExists(TckDatabase db, Strategy<P, C> s) {
        var verdicts = new Verdicts();
        var declared = List.of(new ExpectedNarrowing(ExpectedNarrowing.Kind.EXISTS_ROOT, s.payment, Set.of(s.card)));
        boolean union = s.payment == TpPayment.class;
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            assertThat(statements(db, ds -> s.inSession(ds, verdicts::over, em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                for (String read : List.of("number", "status", "status is null")) {
                    CriteriaQuery<Long> query = cb.createQuery(Long.class);
                    Root root = query.from(s.payment);
                    Subquery<Integer> sub = query.subquery(Integer.class);
                    Path<String> column = cb.treat(sub.correlate(root), s.card).get(read.split(" ")[0]);
                    sub.select(cb.literal(1)).where(read.endsWith("null") ? cb.isNull(column)
                            : cb.equal(column, read.equals("number") ? "1111" : "s2"));
                    query.select(root.get("id")).where(cb.not(cb.exists(sub)));
                    Optional<String> verdict = verdicts.preflight(em, query, StatementShape.CONTENT, declared);
                    if (union && !read.equals("number")) {
                        assertThat(verdict).as(s.name + " " + read).hasValueSatisfying(reason ->
                                assertThat(reason).contains("no narrowing the engine declared renders it"));
                    } else {
                        assertThat(verdict).as(s.name + " " + read).isEmpty();
                    }
                }
            }))).as(s.name).isEmpty();
            // Admitted, each runs: every row but the card numbered 1111, or the one whose status is s2.
            statements(db, ds -> s.inSession(ds, verdicts::over, em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                for (String read : union ? List.of("number") : List.of("number", "status")) {
                    CriteriaQuery<Long> query = cb.createQuery(Long.class);
                    Root root = query.from(s.payment);
                    Subquery<Integer> sub = query.subquery(Integer.class);
                    sub.select(cb.literal(1)).where(cb.equal(cb.treat(sub.correlate(root), s.card).get(read),
                            read.equals("number") ? "1111" : "s2"));
                    query.select(root.get("id")).where(cb.not(cb.exists(sub))).orderBy(cb.asc(root.get("id")));
                    assertThat(em.createQuery(query).getResultList()).as(s.name + " " + read)
                            .containsExactly(1L, 3L, 4L, 5L);
                }
            }));
            if (s.payment == StPayment.class) {
                Strategy<StPayment, StCard> st = (Strategy<StPayment, StCard>) (Strategy) s;
                TableField<StPayment, StCard> card = st.root.treat(StCard.class);
                TableField<StCard, StItem> cardItems = TableField.join(card, "items", JoinType.LEFT);
                var label = ColumnField.of(String.class, cardItems, "label", String.class);
                var labelled = statusesWhere(st, f -> f.exists(cardItems, e -> e.or(a -> a.eq(label, "a"),
                        b -> b.eq(label, "b"))));
                schema.inTransaction(em -> {
                    item(em, 1, "a", 2);
                    item(em, 2, "b", 4);
                });
                statements(db, ds -> st.inSession(ds, verdicts::over, em -> {
                    verdicts.clear();
                    assertThat(ModelQueryExecutor.create(em, StPayment.class, ModelQueryConfig.defaults())
                            .list(labelled, Limit.of(10))).containsExactly("s2");
                    assertThat(verdicts.verdicts).containsExactly(Optional.empty());
                }));
            }
        }
    }

    @TckTest
    void ac_vnd_21_hibernate_preflights_each_strategy_s_statements_before_any_jdbc_statement(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            hibernatePreflights(db, strategy);
        }
    }

    /**
     * On {@code s}, Hibernate's own preflight: an unanchored {@code count(root)} over {@code isNull} refused and its
     * anchored form empty; a keyset page and a keyset export over a treated key preflighted with Hibernate's verdict
     * on every page shape, a non-NULL and a NULL cursor included, before any JDBC statement; then each call runs.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <P, C extends P> void hibernatePreflights(TckDatabase db, Strategy<P, C> s) {
        var keyset = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                .primaryKey(PrimaryKey.of(s.id)).orderBy(s.number.asc().nullsLast()).keyset().build();
        var nulls = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                .primaryKey(PrimaryKey.of(s.id)).where(f -> f.isNull(s.number)).build();
        var verdicts = new Verdicts();
        List<String> sink = new ArrayList<>();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            assertThat(statements(db, ds -> s.inSession(ds, verdicts::over, em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                for (boolean anchored : new boolean[] {false, true}) {
                    CriteriaQuery<Long> count = cb.createQuery(Long.class);
                    Root root = count.from(s.payment);
                    count.select(anchored ? cb.count(root.get("id")) : cb.count(root))
                            .where(cb.isNull(cb.treat(root, s.card).get("number")));
                    Optional<String> verdict = verdicts.preflight(em, count, StatementShape.COUNT, List.of());
                    if (anchored) {
                        assertThat(verdict).as(s.name).isEmpty();
                    } else {
                        assertThat(verdict).as(s.name)
                                .hasValueSatisfying(reason -> assertThat(reason).contains("lost"));
                    }
                }
                verdicts.clear();
                // Refused after Hibernate's verdict on the last page shape: every shape was preflighted first.
                var executor = ModelQueryExecutor.create(em, s.payment, ModelQueryConfig.defaults());
                verdicts.refused = 4;
                assertThatThrownBy(() -> executor.page(keyset, KeysetSpec.first(2))).as(s.name)
                        .hasMessageContaining("refused by the test");
                assertThat(verdicts.parameters).as(s.name).containsExactly(0, 2, 1, 2, 1);
                assertThat(verdicts.verdicts).as(s.name).containsOnly(Optional.empty());
                verdicts.clear();
                verdicts.refused = 2;
                assertThatThrownBy(() -> executor.export(keyset, ExportOptions.of(2), rows -> rows, sink::add))
                        .as(s.name).hasMessageContaining("refused by the test");
                assertThat(verdicts.parameters).as(s.name).containsExactly(0, 2, 1);
                assertThat(verdicts.verdicts).as(s.name).containsOnly(Optional.empty());
                verdicts.clear();
            }))).as(s.name).isEmpty();
            // Admitted, each call runs: numbers 1111 and 5555 first, then the rows without one by id.
            statements(db, ds -> s.inSession(ds, verdicts::over, em -> {
                var executor = ModelQueryExecutor.create(em, s.payment, ModelQueryConfig.defaults());
                assertThat(executor.list(nulls, Limit.of(10))).as(s.name).containsExactly("s1", "s3", "s4");
                assertThat(executor.count(nulls)).as(s.name).isEqualTo(3);
                assertThat(executor.page(keyset, KeysetSpec.first(2)).content()).as(s.name)
                        .containsExactly("s2", "s5");
                executor.export(keyset, ExportOptions.of(2), rows -> rows, sink::add);
                assertThat(sink).as(s.name).containsExactly("s2", "s5", "s1", "s3", "s4");
                assertThat(verdicts.verdicts).as(s.name).isNotEmpty().containsOnly(Optional.empty());
            }));
        }
    }

    /** One call of the AC-VND-21 tests, with the shapes it preflights in order and the parameters each binds. */
    private record PreflightCall(String name, Runnable run, List<StatementShape> shapes, List<Integer> parameters) {}

    /** The read calls over {@code s}'s treated queries, each with the statements it preflights (R-VND-26). */
    private static final class PreflightCalls {
        private static final StatementShape CONTENT = StatementShape.CONTENT;
        private static final StatementShape COUNT = StatementShape.COUNT;

        final ModelQuery<StPayment, Long, String> byId;
        final ModelQuery<StPayment, Long, String> one;
        final ModelQuery<StPayment, Long, String> keysFirst;
        final ModelQuery<StPayment, Long, String> keyset;
        final ModelQuery<StPayment, Long, String> keysetNulls;

        PreflightCalls(Strategy<StPayment, StCard> s) {
            byId = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                    .primaryKey(PrimaryKey.of(s.id)).where(f -> f.isNull(s.number)).build();
            one = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                    .primaryKey(PrimaryKey.of(s.id)).where(f -> f.isNull(s.number).eq(s.id, 1L)).build();
            keysFirst = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                    .primaryKey(PrimaryKey.of(s.id)).where(f -> f.isNull(s.number))
                    .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
            keyset = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                    .primaryKey(PrimaryKey.of(s.id)).orderBy(s.number.asc().nullsLast()).keyset().build();
            // Two keys that can be NULL: a later page's cursor holds a NULL at either, or at both.
            keysetNulls = ModelQuery.builder(s.root, row -> row.get(s.status)).select(SelectSet.of(s.id, s.status))
                    .primaryKey(PrimaryKey.of(s.id)).orderBy(s.number.asc().nullsLast(), s.status.asc().nullsLast())
                    .keyset().build();
        }

        List<PreflightCall> all(ModelQueryExecutor<StPayment> executor) {
            List<String> sink = new ArrayList<>();
            return List.of(
                    call("list", () -> executor.list(byId, Limit.of(10)), CONTENT),
                    call("list, zero limit", () -> executor.list(byId, Limit.of(0)), CONTENT),
                    // A zero limit still preflights, and opens no stream, which the serving provider can't.
                    call("stream", () -> executor.stream(byId, Limit.of(0), Stream::count), CONTENT),
                    call("one", () -> executor.one(one), CONTENT),
                    call("first", () -> executor.first(byId), CONTENT),
                    call("one by key", () -> executor.one(byId, 3L), CONTENT),
                    call("byKeys", () -> executor.byKeys(byId, List.of(1L, 3L)), CONTENT),
                    // No keys runs no statement, and still preflights, so a refusal fails the same way for any keys.
                    call("byKeys, no keys", () -> executor.byKeys(byId, List.of()), CONTENT),
                    call("count", () -> executor.count(byId), COUNT),
                    call("page, count", () -> executor.page(byId, PageSpec.of(0, 2), CountMode.COUNT),
                            COUNT, CONTENT),
                    call("page, only count", () -> executor.page(byId, PageSpec.of(0, 2), CountMode.ONLY_COUNT),
                            COUNT),
                    call("page, no count", () -> executor.page(byId, PageSpec.of(0, 2), CountMode.NO_COUNT),
                            CONTENT),
                    // Step 2's statement, then the count, then step 1's.
                    call("primary-key-first page", () -> executor.page(keysFirst, PageSpec.of(1, 1),
                            CountMode.COUNT), CONTENT, COUNT, CONTENT),
                    call("offset export", () -> executor.export(byId, ExportOptions.of(2), rows -> rows,
                            sink::add), CONTENT),
                    new PreflightCall("keyset page", () -> executor.page(keyset, KeysetSpec.first(2)),
                            List.of(CONTENT, CONTENT, CONTENT, CONTENT, CONTENT), List.of(0, 2, 1, 2, 1)),
                    new PreflightCall("keyset export", () -> executor.export(keyset, ExportOptions.of(2),
                            rows -> rows, sink::add), List.of(CONTENT, CONTENT, CONTENT), List.of(0, 2, 1)),
                    // Every combination of NULLs at the two nullable keys: none, the number's, the status's, both.
                    new PreflightCall("keyset page, two nullable keys", () -> executor.page(keysetNulls,
                            KeysetSpec.first(2)), Collections.nCopies(9, CONTENT), List.of(0, 3, 2, 2, 1, 3, 2, 2, 1)),
                    new PreflightCall("keyset export, two nullable keys", () -> executor.export(keysetNulls,
                            ExportOptions.of(2), rows -> rows, sink::add), Collections.nCopies(5, CONTENT),
                            List.of(0, 3, 2, 2, 1)));
        }

        private static PreflightCall call(String name, Runnable run, StatementShape... shapes) {
            return new PreflightCall(name, run, List.of(shapes), null);
        }
    }

    /**
     * Renders as {@link CountingRenderer} does, admits every site, and records each preflighted statement's shape and
     * parameters; it refuses the {@code refused}-th, counted from 0.
     */
    private static final class Preflights implements TreatRenderer {
        private final CountingRenderer rendering = new CountingRenderer();
        final List<StatementShape> shapes = new ArrayList<>();
        final List<Integer> parameters = new ArrayList<>();
        final List<List<ExpectedNarrowing>> expected = new ArrayList<>();
        final List<CommonAbstractCriteria> statements = new ArrayList<>();
        int refused = -1;

        @Override
        public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
            return rendering.treat(cb, from, subtype);
        }

        @Override
        public Optional<String> refusal(TreatSite site) {
            return Optional.empty();
        }

        @Override
        public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape,
                List<ExpectedNarrowing> expected) {
            assertThat(statement).isInstanceOf(CriteriaQuery.class);
            shapes.add(shape);
            this.expected.add(expected);
            statements.add(statement);
            parameters.add(((CriteriaQuery<?>) statement).getParameters().size());
            return shapes.size() - 1 == refused ? Optional.of("refused by the test") : Optional.empty();
        }

        void clear() {
            shapes.clear();
            parameters.clear();
            expected.clear();
            statements.clear();
            refused = -1;
        }
    }

    @TckTest
    void ac_exe_03_a_refused_count_of_type_groups_counts_them_client_side_and_a_refused_group_is_mq4016(
            TckDatabase db) {
        Strategy<StPayment, StCard> s = (Strategy<StPayment, StCard>) STRATEGIES.get(0);
        var type = Expr.typeOf(String.class, s.root);
        var counted = Agg.<String>count(s.id);
        var grouped = ModelQuery.builder(s.root, row -> row.get(type).getSimpleName() + ":" + row.get(counted))
                .select(SelectSet.of(type, counted)).groupBy(type).build();
        try (SessionFactory schema = s.schema(db)) {
            schema.inTransaction(s::insertRows);
            // Admitted, the provider counts the groups; a refused GROUPED_COUNT runs the grouped statement and
            // counts its rows instead (D-159).
            Predicate<TreatSite> none = site -> false;
            List<String> admitted = statements(db, ds -> s.serving(ds, refusing(none), executor ->
                    ServingProvider.countingGroups(() -> assertThat(executor.count(grouped)).isEqualTo(4))));
            assertThat(admitted).singleElement().asString().contains("count(*)");
            Predicate<TreatSite> groupCounts = site -> site.shape() == StatementShape.GROUPED_COUNT;
            List<String> sql = statements(db, ds -> s.serving(ds, refusing(groupCounts), executor ->
                    ServingProvider.countingGroups(() -> {
                        assertThat(executor.count(grouped)).isEqualTo(4);
                        var page = executor.page(grouped, PageSpec.of(0, 3), CountMode.COUNT);
                        assertThat(page.total()).hasValue(4);
                        assertThat(page.content()).hasSize(3);
                    })));
            assertThat(sql).hasSize(3).allSatisfy(statement -> assertThat(statement).doesNotContain("count(*)")
                    .contains("group by"));
            // The client-side count's sites are checked as GROUPED: refused there, the count is MQ4016.
            Predicate<TreatSite> groups = site -> site.shape() == StatementShape.GROUPED
                    || site.shape() == StatementShape.GROUPED_COUNT;
            assertThat(statements(db, ds -> s.serving(ds, refusing(groups), executor -> {
                assertRefused(() -> executor.count(grouped), "GROUPED [");
                assertRefused(() -> executor.page(grouped, PageSpec.of(0, 3), CountMode.ONLY_COUNT), "GROUPED [");
            }))).isEmpty();
        }
    }

    /**
     * Hibernate's renderer for the factory it is made {@link #over}, recording each preflighted statement's
     * parameters and Hibernate's verdict; it refuses the {@code refused}-th, counted from 0, after that verdict.
     */
    private static final class Verdicts implements TreatRenderer {
        private TreatRenderer hibernate;
        final List<Integer> parameters = new ArrayList<>();
        final List<Optional<String>> verdicts = new ArrayList<>();
        int refused = -1;

        TreatRenderer over(SessionFactory factory) {
            hibernate = new HibernateProviderSupport().treats(factory).orElseThrow();
            return this;
        }

        @Override
        public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
            return hibernate.treat(cb, from, subtype);
        }

        @Override
        public Optional<String> refusal(TreatSite site) {
            return hibernate.refusal(site);
        }

        @Override
        public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape,
                List<ExpectedNarrowing> expected) {
            Optional<String> verdict = hibernate.preflight(em, statement, shape, expected);
            parameters.add(((CriteriaQuery<?>) statement).getParameters().size());
            verdicts.add(verdict);
            return verdicts.size() - 1 == refused ? Optional.of("refused by the test") : verdict;
        }

        void clear() {
            parameters.clear();
            verdicts.clear();
            refused = -1;
        }
    }

    /** The sub-select vocabulary of the AC-VND-21 tests. */
    private static final class Inner {}

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
                    StatementShape shape, List<ExpectedNarrowing> expected) {
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
            inSession(ds, factory -> renderer, work);
        }

        /** {@code work} in a session of a factory over {@code ds} whose provider serves the renderer made for it. */
        void inSession(DataSource ds, Function<SessionFactory, TreatRenderer> renderer, Consumer<EntityManager> work) {
            Configuration cfg = configuration();
            cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
            cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
            try (SessionFactory factory = cfg.buildSessionFactory()) {
                ServingProvider.serving(factory, renderer.apply(factory), () -> factory.inSession(work::accept));
            }
        }
    }

    /** A source branch's model. */
    public static final class Branch {}
}
