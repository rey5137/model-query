package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExpectedNarrowing;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.TreatChecks;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.TreatTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.AnchorEntities;
import com.rey.modelquery.tck.inheritance.CellEntities;
import com.rey.modelquery.tck.inheritance.GateEntities;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * Treated and {@code typeOf} fields in a source body over a polymorphic root, through Hibernate's own renderer on each
 * strategy (R-SRC-20): the body reads every root row, selects its root id, hidden as {@code mq_0id} when no field reads
 * it, and refuses a treated group key or aggregate argument before any statement (AC-SRC-39, AC-COL-46, AC-COL-52).
 *
 * <p>A union of subtype models keeps R-SRC-06: card and bank branches are unique by a discriminator and not without
 * one, and card and premium branches read a premium row under two kinds (AC-SRC-40, AC-SRC-41). A treated branch that
 * needs the hidden anchor doesn't combine with a grouped one, which can't select it.
 *
 * <p>Rows 1 to 5: a base payment, a card with number {@code 1111} and one without, a bank with iban {@code DE04} where
 * its hierarchy maps one, a premium card {@code 5555}; each with status {@code s<id>}.
 */
class TreatSourceTest {

    private static final List<Strategy<?, ?>> STRATEGIES = List.of(
            new Strategy<>("single-table", AnchorEntities.StPayment.class, AnchorEntities.StCard.class,
                    AnchorEntities.StBank.class, AnchorEntities.StPremium.class, AnchorEntities.StCustomer.class,
                    AnchorEntities.StItem.class, AnchorEntities.StVoucher.class),
            new Strategy<>("joined", AnchorEntities.JdPayment.class, AnchorEntities.JdCard.class,
                    AnchorEntities.JdBank.class, AnchorEntities.JdPremium.class),
            new Strategy<>("table-per-class", AnchorEntities.TpPayment.class, AnchorEntities.TpCard.class,
                    AnchorEntities.TpBank.class, AnchorEntities.TpPremium.class));
    /** The hierarchies whose bank maps an {@code iban}, for the union of subtype models (AC-SRC-40, AC-SRC-41). */
    private static final List<Strategy<?, ?>> CELLS = List.of(
            new Strategy<>("single-table", CellEntities.StPayment.class, CellEntities.StCard.class,
                    CellEntities.StBank.class, CellEntities.StPremium.class, CellEntities.StHolder.class),
            new Strategy<>("joined", CellEntities.JdPayment.class, CellEntities.JdCard.class,
                    CellEntities.JdBank.class, CellEntities.JdPremium.class, CellEntities.JdHolder.class),
            new Strategy<>("table-per-class", CellEntities.TpPayment.class, CellEntities.TpCard.class,
                    CellEntities.TpBank.class, CellEntities.TpPremium.class, CellEntities.TpHolder.class));

    @TckTest
    void ac_src_39_treated_and_subtype_fields_in_a_source_body_read_every_row_with_the_root_id(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            readsEveryRow(db, strategy);
        }
    }

    private static <P, C extends P> void readsEveryRow(TckDatabase db, Strategy<P, C> s) {
        var id = ColumnField.of(Body.class, s.root, "id", Long.class).named("id");
        var number = ColumnField.of(Body.class, s.root.treat(s.card), "number", String.class).named("number");
        var kind = Expr.typeOf(Body.class, s.root).named("kind");
        var root = TableField.source(Body.class);
        var outerId = ColumnField.over(String.class, root, id);
        var outerNumber = ColumnField.over(String.class, root, number);
        var outerKind = ColumnField.over(String.class, root, kind);
        var source = SourceQuery.builder(Body.class, s.root, SelectSet.of(id, number), PrimaryKey.of(id)).build();
        var query = ModelQuery.builder(root, row -> row.get(outerId) + ":" + row.get(outerNumber), source)
                .select(SelectSet.of(outerId, outerNumber)).primaryKey(PrimaryKey.of(outerId))
                .orderBy(outerId.asc()).build();
        var typedSource = SourceQuery.builder(Body.class, s.root, SelectSet.of(id, number, kind), PrimaryKey.of(id))
                .build();
        var typed = ModelQuery.builder(root, row -> row.get(outerId) + ":" + row.get(outerNumber) + ":"
                        + row.get(outerKind).getSimpleName(), typedSource)
                .select(SelectSet.of(outerId, outerNumber, outerKind)).primaryKey(PrimaryKey.of(outerId))
                .orderBy(outerId.asc()).build();
        var filtered = SourceQuery.builder(Body.class, s.root, SelectSet.of(id), PrimaryKey.of(id))
                .where(f -> f.isNull(number)).build();
        var nulls = ModelQuery.builder(root, row -> String.valueOf(row.get(outerId)), filtered)
                .select(SelectSet.of(outerId)).primaryKey(PrimaryKey.of(outerId)).orderBy(outerId.asc()).build();
        s.seeded(db, executor -> {
            assertThat(executor.list(query, Limit.of(10))).as(s.name)
                    .containsExactly("1:null", "2:1111", "3:null", "4:null", "5:5555");
            assertThat(executor.count(query)).as(s.name).isEqualTo(5);
            assertThat(executor.page(query, PageSpec.of(1, 2), CountMode.COUNT).total()).as(s.name).hasValue(5);
            // A treated null test keeps the base, the card without a number and the bank, as SQL does.
            assertThat(executor.list(nulls, Limit.of(10))).as(s.name).containsExactly("1", "3", "4");
            assertThat(executor.count(nulls)).as(s.name).isEqualTo(3);
            if (s.typeOrdered) {
                assertThat(executor.list(typed, Limit.of(10))).as(s.name).containsExactly(
                        "1:null:" + s.payment.getSimpleName(), "2:1111:" + s.card.getSimpleName(),
                        "3:null:" + s.card.getSimpleName(), "4:null:" + s.bank.getSimpleName(),
                        "5:5555:" + s.premium.getSimpleName());
                assertThat(executor.count(typed)).as(s.name).isEqualTo(5);
            } else {
                // JOINED without a discriminator: a body's typeOf would read the root's class on every row (D-162).
                assertRefused(() -> executor.list(typed, Limit.of(10)), "without a discriminator");
            }
        });
    }

    @TckTest
    void ac_vnd_21_an_untreated_query_over_a_treated_source_body_reaches_the_renderers_preflight(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            refusedAtPreflight(db, strategy);
        }
    }

    private static <P, C extends P> void refusedAtPreflight(TckDatabase db, Strategy<P, C> s) {
        var id = ColumnField.of(Body.class, s.root, "id", Long.class).named("id");
        var number = ColumnField.of(Body.class, s.root.treat(s.card), "number", String.class).named("number");
        var root = TableField.source(Body.class);
        var outerId = ColumnField.over(String.class, root, id);
        var source = SourceQuery.builder(Body.class, s.root, SelectSet.of(id, number), PrimaryKey.of(id)).build();
        var query = ModelQuery.builder(root, row -> String.valueOf(row.get(outerId)), source)
                .select(SelectSet.of(outerId)).primaryKey(PrimaryKey.of(outerId)).build();
        // The outer query has no treated node of its own, so it needs no anchor, but its source's statement is
        // preflighted all the same (R-VND-26).
        assertThat(TreatChecks.treated(query)).isFalse();
        assertThat(TreatChecks.reachesTreat(query)).isTrue();
        var preflighted = new ArrayList<StatementShape>();
        try (SessionFactory schema = s.schema(db)) {
            var rendering = new TreatTestSupport.CountingRenderer();
            assertThat(statements(db, ds -> TreatTestSupport.ServingProvider.renderingSources(() -> s.serving(ds,
                    new TreatRenderer() {
                @Override
                public <T, X extends T> From<?, X> treat(CriteriaBuilder cb, From<?, T> from, Class<X> subtype) {
                    return rendering.treat(cb, from, subtype);
                }

                @Override
                public Optional<String> refusal(TreatSite site) {
                    return Optional.empty();
                }

                @Override
                public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement,
                        StatementShape shape, List<ExpectedNarrowing> expected) {
                    preflighted.add(shape);
                    return Optional.of("refused by the test");
                }
            }, executor -> {
                assertRefused(() -> executor.list(query, Limit.of(10)), "refused by the test");
                assertRefused(() -> executor.count(query), "refused by the test");
            })))).as(s.name).isEmpty();
        }
        assertThat(preflighted).as(s.name).hasSize(2);
    }

    @TckTest
    void ac_vnd_18_a_combined_source_with_a_composite_id_branch_needing_the_anchor_is_mq4016_before_any_statement(
            TckDatabase db) {
        TableField<GateEntities.IdClassPayment, GateEntities.IdClassPayment> payments =
                TableField.root(GateEntities.IdClassPayment.class);
        var status = ColumnField.of(Body.class, payments, "status", String.class).named("status");
        var number = ColumnField.of(Body.class, payments.treat(GateEntities.IdClassCard.class), "number",
                String.class).named("reference");
        // Keyed by its status, the treated branch needs the hidden anchor, a single root id attribute (D-162).
        var treated = SourceQuery.builder(Body.class, payments, SelectSet.of(status, number), PrimaryKey.of(status))
                .build();
        TableField<GateEntities.IdClassCard, GateEntities.IdClassCard> cards =
                TableField.root(GateEntities.IdClassCard.class);
        var cardStatus = ColumnField.of(Body.class, cards, "status", String.class).named("status");
        var cardNumber = ColumnField.of(Body.class, cards, "number", String.class).named("reference");
        var plain = SourceQuery.builder(Body.class, cards, SelectSet.of(cardStatus, cardNumber),
                PrimaryKey.of(cardStatus)).build();
        var root = TableField.source(Body.class);
        var outerStatus = ColumnField.over(String.class, root, status);
        var combined = ModelQuery.builder(root, row -> row.get(outerStatus), treated.unionAll(plain))
                .select(SelectSet.of(outerStatus)).build();
        Configuration cfg = new Configuration().addAnnotatedClass(GateEntities.IdClassPayment.class)
                .addAnnotatedClass(GateEntities.IdClassCard.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        try (SessionFactory factory = cfg.buildSessionFactory()) {
            factory.getStatistics().setStatisticsEnabled(true);
            long prepared = factory.getStatistics().getPrepareStatementCount();
            factory.inSession(em -> {
                var executor = Strategy.executor(em);
                assertRefused(() -> executor.list(combined, Limit.of(10)), "composite id");
                assertRefused(() -> executor.count(combined), "composite id");
            });
            assertThat(factory.getStatistics().getPrepareStatementCount()).as("JDBC statements prepared")
                    .isEqualTo(prepared);
        }
    }

    @TckTest
    void ac_col_52_a_source_body_keyed_by_another_column_selects_the_root_id_last_as_mq_0id(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            selectsTheAnchor(db, strategy);
        }
    }

    private static <P, C extends P> void selectsTheAnchor(TckDatabase db, Strategy<P, C> s) {
        var status = ColumnField.of(Body.class, s.root, "status", String.class).named("status");
        var number = ColumnField.of(Body.class, s.root.treat(s.card), "number", String.class).named("number");
        var root = TableField.source(Body.class);
        var outerStatus = ColumnField.over(String.class, root, status);
        var outerNumber = ColumnField.over(String.class, root, number);
        var source = SourceQuery.builder(Body.class, s.root, SelectSet.of(status, number), PrimaryKey.of(status))
                .build();
        var query = ModelQuery.builder(root, row -> row.get(outerStatus) + ":" + row.get(outerNumber), source)
                .select(SelectSet.of(outerStatus, outerNumber)).primaryKey(PrimaryKey.of(outerStatus))
                .orderBy(outerStatus.asc()).build();
        // A unionAll of the treated branch and one rooted at the card, which reads its number untreated: both select
        // the anchor, so they keep one arity.
        TableField<C, C> cards = TableField.root(s.card);
        var plain = SourceQuery.builder(Body.class, cards, SelectSet.of(
                ColumnField.of(Body.class, cards, "status", String.class).named("status"),
                ColumnField.of(Body.class, cards, "number", String.class).named("number")),
                PrimaryKey.of(ColumnField.of(Body.class, cards, "status", String.class).named("status"))).build();
        var all = ModelQuery.builder(root, row -> row.get(outerStatus) + ":" + row.get(outerNumber),
                source.unionAll(plain)).select(SelectSet.of(outerStatus, outerNumber)).build();
        List<String> sql;
        try (SessionFactory schema = s.schema(db)) {
            sql = statements(db, ds -> s.serving(ds, executor -> {
                assertThat(executor.list(query, Limit.of(10))).as(s.name)
                        .containsExactly("s1:null", "s2:1111", "s3:null", "s4:null", "s5:5555");
                assertThat(executor.count(query)).as(s.name).isEqualTo(5);
                assertThat(executor.list(all, Limit.of(20))).as(s.name).containsExactlyInAnyOrder(
                        "s1:null", "s2:1111", "s3:null", "s4:null", "s5:5555", "s2:1111", "s3:null", "s5:5555");
            }));
        }
        // The body selects the root id after its fields, in each unionAll branch too; no outer column reads it.
        assertThat(sql).as(s.name).hasSize(3);
        for (String statement : sql) {
            assertThat(statement).as(s.name).containsIgnoringCase("mq_0id")
                    .doesNotContainPattern("(?i)\\w+\\.mq_0id");
        }
        assertThat(query.select().fields()).containsExactly(outerStatus, outerNumber);
    }

    @TckTest
    void ac_col_46_a_treated_group_key_or_aggregate_argument_in_a_source_is_refused_before_any_statement(
            TckDatabase db) {
        Strategy<?, ?> s = STRATEGIES.get(0);
        refusesGrouping(db, s);
    }

    private static <P, C extends P> void refusesGrouping(TckDatabase db, Strategy<P, C> s) {
        var status = ColumnField.of(Body.class, s.root, "status", String.class).named("status");
        var number = ColumnField.of(Body.class, s.root.treat(s.card), "number", String.class).named("number");
        var counted = Agg.<Body>count(number).named("counted");
        var root = TableField.source(Body.class);
        var byNumber = SourceQuery.builder(Body.class, s.root, SelectSet.of(number), PrimaryKey.of(number),
                SelectSet.of(number)).build();
        var keyed = ModelQuery.builder(root, row -> "group", byNumber)
                .select(SelectSet.of(ColumnField.over(String.class, root, number))).build();
        var byStatus = SourceQuery.builder(Body.class, s.root, SelectSet.of(status, counted), PrimaryKey.of(status),
                SelectSet.of(status)).build();
        var aggregated = ModelQuery.builder(root, row -> "group", byStatus)
                .select(SelectSet.of(ColumnField.over(String.class, root, status))).build();
        try (SessionFactory schema = s.schema(db)) {
            assertThat(statements(db, ds -> s.serving(ds, executor -> {
                // A grouped body's sites are GROUPED, which no cell admits for a treated read (R-SRC-20).
                for (var query : List.of(keyed, aggregated)) {
                    assertRefused(() -> executor.list(query, Limit.of(10)), "GROUPED");
                    assertRefused(() -> executor.count(query), "GROUPED");
                }
            }))).isEmpty();
        }
    }

    @TckTest
    void ac_src_40_a_union_all_of_card_and_bank_branches_by_discriminator_is_unique_by_kind_and_id(TckDatabase db) {
        for (Strategy<?, ?> strategy : CELLS) {
            unionOfSiblings(db, strategy);
        }
    }

    private static <P, C extends P> void unionOfSiblings(TckDatabase db, Strategy<P, C> s) {
        var root = TableField.source(Body.class);
        var outerKind = ColumnField.over(String.class, root, KIND);
        var outerId = ColumnField.over(String.class, root, ID);
        var outerReference = ColumnField.over(String.class, root, REFERENCE);
        var keyed = ModelQuery.builder(root, row -> row.get(outerKind) + ":" + row.get(outerId) + ":"
                        + row.get(outerReference),
                        branch(s.card, "card", "number").unionAll(branch(s.bank, "bank", "iban")))
                .select(SelectSet.of(outerKind, outerId, outerReference))
                .primaryKey(PrimaryKey.composite(outerKind, outerId)).orderBy(outerKind.asc(), outerId.asc())
                .keyset().build();
        var plain = ModelQuery.builder(root, row -> row.get(outerId) + ":" + row.get(outerReference),
                        plainBranch(s.card, "number").unionAll(plainBranch(s.bank, "iban")))
                .select(SelectSet.of(outerId, outerReference)).orderBy(outerId.asc()).build();
        try (SessionFactory schema = s.schema(db)) {
            schema.inSession(em -> {
                var executor = Strategy.executor(em);
                List<String> exported = new ArrayList<>();
                assertThat(executor.export(keyed, ExportOptions.of(2), page -> page, exported::add)).as(s.name)
                        .isEqualTo(4);
                assertThat(exported).as(s.name)
                        .containsExactly("bank:4:DE04", "card:2:1111", "card:3:null", "card:5:5555");
                // Without the discriminator the branches are not disjoint by key, whatever ids they hold (R-SRC-09).
                assertThat(executor.list(plain, Limit.of(10))).as(s.name)
                        .containsExactly("2:1111", "3:null", "4:DE04", "5:5555");
            });
            assertThat(statements(db, ds -> s.serving(ds, executor -> assertThatThrownBy(
                    () -> executor.export(plain, ExportOptions.of(2), page -> page, row -> { }))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2212))))).as(s.name).isEmpty();
        }
    }

    @TckTest
    void ac_src_41_card_and_premium_branches_read_a_premium_row_twice_with_two_kinds_and_unique_keys(
            TckDatabase db) {
        for (Strategy<?, ?> strategy : CELLS) {
            unionOfSubtypes(db, strategy);
        }
    }

    private static <P, C extends P> void unionOfSubtypes(TckDatabase db, Strategy<P, C> s) {
        var root = TableField.source(Body.class);
        var outerKind = ColumnField.over(String.class, root, KIND);
        var outerId = ColumnField.over(String.class, root, ID);
        var outerReference = ColumnField.over(String.class, root, REFERENCE);
        var query = ModelQuery.builder(root, row -> row.get(outerKind) + ":" + row.get(outerId) + ":"
                        + row.get(outerReference),
                        branch(s.card, "card", "number").unionAll(branch(s.premium, "premium", "number")))
                .select(SelectSet.of(outerKind, outerId, outerReference))
                .primaryKey(PrimaryKey.composite(outerKind, outerId)).orderBy(outerKind.asc(), outerId.asc())
                .keyset().build();
        s.seeded(db, executor -> {
            List<String> exported = new ArrayList<>();
            assertThat(executor.export(query, ExportOptions.of(2), page -> page, exported::add)).as(s.name)
                    .isEqualTo(4);
            assertThat(exported).as(s.name)
                    .containsExactly("card:2:1111", "card:3:null", "card:5:5555", "premium:5:5555");
        });
    }

    @TckTest
    void r_src_20_a_union_all_of_a_treated_branch_needing_the_anchor_and_a_grouped_one_is_mq4016(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            refusesUnanchoredBranch(db, strategy);
        }
    }

    private static <P, C extends P> void refusesUnanchoredBranch(TckDatabase db, Strategy<P, C> s) {
        TableField<C, C> cards = TableField.root(s.card);
        var cardId = ColumnField.of(Body.class, cards, "id", Long.class).named("id");
        var cardStatus = ColumnField.of(Body.class, cards, "status", String.class).named("status");
        var cardNumber = ColumnField.of(Body.class, cards, "number", String.class).named("reference");
        var id = ColumnField.of(Body.class, s.root, "id", Long.class).named("id");
        var status = ColumnField.of(Body.class, s.root, "status", String.class).named("status");
        var number = ColumnField.of(Body.class, s.root.treat(s.card), "number", String.class).named("reference");
        var root = TableField.source(Body.class);
        var outerStatus = ColumnField.over(String.class, root, STATUS);
        var outerReference = ColumnField.over(String.class, root, REFERENCE);
        // Keyed by its status, the treated branch reads no untreated root id, so it needs the hidden anchor, which
        // the grouped branch can't select.
        var needsAnchor = SourceQuery.builder(Body.class, s.root, SelectSet.of(status, number), PrimaryKey.of(status))
                .build();
        var grouped = SourceQuery.builder(Body.class, cards, SelectSet.of(cardStatus, cardNumber),
                PrimaryKey.composite(cardStatus, cardNumber), SelectSet.of(cardStatus, cardNumber)).build();
        var refused = ModelQuery.builder(root, row -> "refused", needsAnchor.unionAll(grouped))
                .select(SelectSet.of(outerStatus, outerReference)).build();
        // A treated branch that selects its root id needs no anchor, so it combines with a grouped one.
        var selectsId = SourceQuery.builder(Body.class, s.root, SelectSet.of(id, status, number), PrimaryKey.of(id))
                .build();
        var groupedById = SourceQuery.builder(Body.class, cards, SelectSet.of(cardId, cardStatus, cardNumber),
                PrimaryKey.of(cardId), SelectSet.of(cardId, cardStatus, cardNumber)).build();
        var admitted = ModelQuery.builder(root, row -> row.get(outerStatus) + ":" + row.get(outerReference),
                        selectsId.unionAll(groupedById)).select(SelectSet.of(outerStatus, outerReference))
                .orderBy(outerStatus.asc()).build();
        try (SessionFactory schema = s.schema(db)) {
            assertThat(statements(db, ds -> s.serving(ds, executor -> {
                assertRefused(() -> executor.list(refused, Limit.of(10)), "grouped branch");
                assertRefused(() -> executor.count(refused), "grouped branch");
            }))).as(s.name).isEmpty();
            schema.inSession(em -> assertThat(Strategy.executor(em).list(admitted, Limit.of(20))).as(s.name)
                    .containsExactly("s1:null", "s2:1111", "s2:1111", "s3:null", "s3:null", "s4:null", "s5:5555",
                            "s5:5555"));
        }
    }

    @TckTest
    void r_src_20_a_nested_union_or_except_under_a_branch_needing_the_anchor_is_mq4016(TckDatabase db) {
        for (Strategy<?, ?> strategy : STRATEGIES) {
            refusesNestedDistinctOperation(db, strategy);
        }
    }

    private static <P, C extends P> void refusesNestedDistinctOperation(TckDatabase db, Strategy<P, C> s) {
        TableField<C, C> cards = TableField.root(s.card);
        var root = TableField.source(Body.class);
        var outerStatus = ColumnField.over(String.class, root, STATUS);
        var outerReference = ColumnField.over(String.class, root, REFERENCE);
        // The anchor every branch would select stops the union collapsing its duplicates and the except removing
        // any row.
        var union = ModelQuery.builder(root, row -> "refused",
                        needingAnchor(s).unionAll(untreated(cards).union(untreated(cards))))
                .select(SelectSet.of(outerStatus, outerReference)).build();
        var except = ModelQuery.builder(root, row -> "refused",
                        needingAnchor(s).unionAll(untreated(cards).except(untreated(cards))))
                .select(SelectSet.of(outerStatus, outerReference)).build();
        try (SessionFactory schema = s.schema(db)) {
            assertThat(statements(db, ds -> s.serving(ds, executor -> {
                assertRefused(() -> executor.list(union, Limit.of(10)), "UNION compares");
                assertRefused(() -> executor.list(except, Limit.of(10)), "EXCEPT compares");
            }))).as(s.name).isEmpty();
        }
    }

    @TckTest
    void r_src_20_branches_whose_root_ids_differ_in_type_under_a_branch_needing_the_anchor_are_mq4016(
            TckDatabase db) {
        Strategy<?, ?> singleTable = STRATEGIES.get(0);
        refusesMixedIdTypes(db, singleTable);
    }

    private static <P, C extends P> void refusesMixedIdTypes(TckDatabase db, Strategy<P, C> s) {
        TableField<AnchorEntities.StVoucher, AnchorEntities.StVoucher> vouchers =
                TableField.root(AnchorEntities.StVoucher.class);
        var voucherStatus = ColumnField.of(Body.class, vouchers, "status", String.class).named("status");
        var voucherCode = ColumnField.of(Body.class, vouchers, "code", String.class).named("reference");
        var voucher = SourceQuery.builder(Body.class, vouchers, SelectSet.of(voucherStatus, voucherCode),
                PrimaryKey.of(voucherStatus)).build();
        var root = TableField.source(Body.class);
        var outerStatus = ColumnField.over(String.class, root, STATUS);
        var outerReference = ColumnField.over(String.class, root, REFERENCE);
        // A Long payment id and a String voucher id leave the anchor column with no one type.
        var refused = ModelQuery.builder(root, row -> "refused", needingAnchor(s).unionAll(voucher))
                .select(SelectSet.of(outerStatus, outerReference)).build();
        try (SessionFactory schema = s.schema(db)) {
            assertThat(statements(db, ds -> s.serving(ds, executor -> {
                assertRefused(() -> executor.list(refused, Limit.of(10)), "differ in type (Long, String)");
                assertRefused(() -> executor.count(refused), "differ in type (Long, String)");
            }))).as(s.name).isEmpty();
        }
    }

    /** A treated branch keyed by its status, which reads no untreated root id and so needs the hidden anchor. */
    private static <P, C extends P> SourceQuery<Body> needingAnchor(Strategy<P, C> s) {
        var status = ColumnField.of(Body.class, s.root, "status", String.class).named("status");
        var number = ColumnField.of(Body.class, s.root.treat(s.card), "number", String.class).named("reference");
        return SourceQuery.builder(Body.class, s.root, SelectSet.of(status, number), PrimaryKey.of(status)).build();
    }

    /** An untreated branch over the card table, keyed by its status. */
    private static <C> SourceQuery<Body> untreated(TableField<C, C> cards) {
        var status = ColumnField.of(Body.class, cards, "status", String.class).named("status");
        var number = ColumnField.of(Body.class, cards, "number", String.class).named("reference");
        return SourceQuery.builder(Body.class, cards, SelectSet.of(status, number), PrimaryKey.of(status)).build();
    }

    /** A branch rooted at {@code type} fixing {@code kind}, its {@code reference} read from {@code attribute}. */
    private static <E> SourceQuery<Body> branch(Class<E> type, String kind, String attribute) {
        TableField<E, E> table = TableField.root(type);
        var kindField = ColumnField.of(Body.class, table, "kind", String.class).named("kind");
        var id = ColumnField.of(Body.class, table, "id", Long.class).named("id");
        return SourceQuery.builder(Body.class, table, SelectSet.of(kindField, id,
                        ColumnField.of(Body.class, table, attribute, String.class).named("reference")),
                PrimaryKey.composite(kindField, id)).discriminator(kindField, kind).build();
    }

    /** {@link #branch} without the discriminator, keyed by its id alone. */
    private static <E> SourceQuery<Body> plainBranch(Class<E> type, String attribute) {
        TableField<E, E> table = TableField.root(type);
        var id = ColumnField.of(Body.class, table, "id", Long.class).named("id");
        return SourceQuery.builder(Body.class, table, SelectSet.of(id,
                ColumnField.of(Body.class, table, attribute, String.class).named("reference")), PrimaryKey.of(id))
                .build();
    }

    private static void assertRefused(Runnable action, String reason) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining(reason)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4016);
    }

    /** The statements {@code work} runs, without the temporary tables a factory over a hierarchy creates. */
    private static List<String> statements(TckDatabase db, SqlSnapshots.SqlWork work) {
        return SqlSnapshots.capture(db, work).stream()
                .filter(sql -> !sql.startsWith("create ") && !sql.startsWith("drop ")
                        && !sql.startsWith("alter ") && !sql.startsWith("insert "))
                .toList();
    }

    /** A source body's model over the payment root. */
    public static final class Body {}

    /** The columns {@link #branch} and {@link #plainBranch} select, read by the outer queries. */
    private static final TableField<AnchorEntities.StPayment, AnchorEntities.StPayment> ANY =
            TableField.root(AnchorEntities.StPayment.class);
    private static final OrderedColumnField<Body, ?, String> KIND =
            ColumnField.of(Body.class, ANY, "kind", String.class).named("kind");
    private static final OrderedColumnField<Body, ?, Long> ID =
            ColumnField.of(Body.class, ANY, "id", Long.class).named("id");
    private static final OrderedColumnField<Body, ?, String> STATUS =
            ColumnField.of(Body.class, ANY, "status", String.class).named("status");
    private static final OrderedColumnField<Body, ?, String> REFERENCE =
            ColumnField.of(Body.class, ANY, "reference", String.class).named("reference");

    /** One mapped hierarchy of a strategy, its rows and an executor over its source with Hibernate's renderer. */
    private static final class Strategy<P, C extends P> {
        final String name;
        final Class<P> payment;
        final Class<C> card;
        final Class<? extends P> bank;
        final Class<? extends C> premium;
        final TableField<P, P> root;
        final List<Class<?>> others;
        /** Whether a {@code typeOf} reads each row's class: not on {@code JOINED} without a discriminator column. */
        final boolean typeOrdered;

        Strategy(String name, Class<P> payment, Class<C> card, Class<? extends P> bank, Class<? extends C> premium,
                Class<?>... others) {
            this.name = name;
            this.payment = payment;
            this.card = card;
            this.bank = bank;
            this.premium = premium;
            this.root = TableField.root(payment);
            this.others = List.of(others);
            this.typeOrdered = !name.equals("joined");
        }

        private Configuration configuration() {
            Configuration cfg = new Configuration().addAnnotatedClass(payment).addAnnotatedClass(card)
                    .addAnnotatedClass(bank).addAnnotatedClass(premium);
            others.forEach(cfg::addAnnotatedClass);
            return cfg;
        }

        /** Creates the schema with rows 1 to 5 and runs {@code work} on a source executor, then drops it. */
        void seeded(TckDatabase db, Consumer<ModelQueryExecutor<Body>> work) {
            try (SessionFactory schema = schema(db)) {
                schema.inSession(em -> work.accept(executor(em)));
            }
        }

        /** {@code work} on a factory over {@code ds}, so the statements are captured; {@link #schema} owns the rows. */
        void serving(DataSource ds, Consumer<ModelQueryExecutor<Body>> work) {
            Configuration cfg = configuration();
            cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
            cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
            try (SessionFactory factory = cfg.buildSessionFactory()) {
                factory.inSession(em -> work.accept(executor(em)));
            }
        }

        /** {@link #serving} with a provider that serves {@code renderer}. */
        void serving(DataSource ds, TreatRenderer renderer, Consumer<ModelQueryExecutor<Body>> work) {
            Configuration cfg = configuration();
            cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
            cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
            try (SessionFactory factory = cfg.buildSessionFactory()) {
                TreatTestSupport.ServingProvider.serving(factory, renderer, () -> factory.inSession(em ->
                        work.accept(executor(em))));
            }
        }

        SessionFactory schema(TckDatabase db) {
            SessionFactory factory = configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                    .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop").buildSessionFactory();
            factory.inTransaction(this::insertRows);
            return factory;
        }

        static ModelQueryExecutor<Body> executor(EntityManager em) {
            return ModelQueryExecutor.create(em, Body.class, ModelQueryConfig.defaults());
        }

        private void insertRows(EntityManager em) {
            em.persist(row(payment, 1, "number", null));
            em.persist(row(card, 2, "number", "1111"));
            em.persist(row(card, 3, "number", null));
            // Only the cell hierarchies' bank maps an iban.
            boolean iban = Arrays.stream(bank.getFields()).anyMatch(f -> f.getName().equals("iban"));
            em.persist(row(bank, 4, "iban", iban ? "DE04" : null));
            em.persist(row(premium, 5, "number", "5555"));
        }

        private static Object row(Class<?> type, long id, String attribute, String value) {
            try {
                Object row = type.getDeclaredConstructor().newInstance();
                type.getField("id").set(row, id);
                type.getField("status").set(row, "s" + id);
                if (value != null) {
                    type.getField(attribute).set(row, value);
                }
                return row;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
