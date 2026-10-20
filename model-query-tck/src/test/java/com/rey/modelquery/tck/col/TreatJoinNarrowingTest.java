package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.CellEntities.TpBank;
import com.rey.modelquery.tck.inheritance.CellEntities.TpCard;
import com.rey.modelquery.tck.inheritance.CellEntities.TpHolder;
import com.rey.modelquery.tck.inheritance.CellEntities.TpPayment;
import com.rey.modelquery.tck.inheritance.CellEntities.TpPremium;
import com.rey.modelquery.tck.inheritance.NarrowingEntities.Currency;
import com.rey.modelquery.tck.inheritance.NarrowingEntities.NestedBank;
import com.rey.modelquery.tck.inheritance.NarrowingEntities.NestedCard;
import com.rey.modelquery.tck.inheritance.NarrowingEntities.NestedHolder;
import com.rey.modelquery.tck.inheritance.NarrowingEntities.NestedPayment;
import com.rey.modelquery.tck.inheritance.NarrowingEntities.NestedPremium;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** Hand-written M28.1 joins: a concrete TPC base must never masquerade as a downcast match (AC-VND-21, D-161). */
class TreatJoinNarrowingTest {

    private static final TableField<TpPayment, TpPayment> PAYMENT = TableField.root(TpPayment.class);
    private static final ColumnField<CardView, TpPayment, Long> PAYMENT_ID =
            ColumnField.of(CardView.class, PAYMENT, "id", Long.class);

    @TckTest
    void ac_vnd_21_inner_tpc_join_reading_inherited_columns_is_refused_before_any_statement(TckDatabase db) {
        Fields fields = new Fields(JoinType.INNER, false, TypeRestriction.NONE);
        refusedBeforeAnyStatement(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            assertRefused(() -> executor.list(fields.query(true), Limit.of(20)));
            assertRefused(() -> executor.page(fields.query(true), PageSpec.of(0, 2), CountMode.COUNT));
        });
    }

    @TckTest
    void ac_vnd_21_inner_tpc_join_reading_only_number_excludes_base_sibling_and_absent(TckDatabase db) {
        Fields fields = new Fields(JoinType.INNER, false, TypeRestriction.NONE);
        seeded(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            assertThat(allPages(executor, fields.builder(false).keyset().build()))
                    .extracting(HolderView::id).containsExactly(11L, 12L, 15L);
        });
    }

    @TckTest
    void ac_vnd_21_inner_tpc_join_with_an_effective_subtype_restriction_keeps_inherited_columns(TckDatabase db) {
        Fields fields = new Fields(JoinType.INNER, false, TypeRestriction.LITERAL);
        seeded(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            assertThat(allPages(executor, fields.builder(true).keyset().build())).containsExactly(
                    view(11, 1L, "1111", "paid"), view(12, 2L, null, null), view(15, 4L, "4444", "premium"));
        });
    }

    @TckTest
    void ac_vnd_21_left_tpc_join_uses_a_null_presence_key_for_base_sibling_and_absent(TckDatabase db) {
        Fields fields = new Fields(JoinType.LEFT, true, TypeRestriction.NONE);
        seeded(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            assertThat(allPages(executor, fields.builder(false).keyset().build())).containsExactly(
                    view(11, 1L, "1111", null), view(12, 2L, null, null), view(13, null, null, null),
                    view(14, null, null, null), view(15, 4L, "4444", null), view(16, null, null, null));
            assertThat(allPages(executor, fields.builder(true).keyset().build())).containsExactly(
                    view(11, 1L, "1111", "paid"), view(12, 2L, null, null), view(13, null, null, null),
                    view(14, null, null, null), view(15, 4L, "4444", "premium"), view(16, null, null, null));
        });
    }

    @TckTest
    void ac_vnd_21_left_tpc_join_with_an_unguarded_presence_key_is_refused_before_any_statement(TckDatabase db) {
        Fields fields = new Fields(JoinType.LEFT, false, TypeRestriction.NONE);
        refusedBeforeAnyStatement(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            assertRefused(() -> executor.list(fields.unguardedQuery(), Limit.of(20)));
        });
    }

    @TckTest
    void ac_vnd_21_tpc_join_with_an_unrecognised_parameter_restriction_fails_closed(TckDatabase db) {
        Fields fields = new Fields(JoinType.INNER, false, TypeRestriction.PARAMETER);
        refusedBeforeAnyStatement(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            assertRefused(() -> executor.list(fields.query(true), Limit.of(20)));
        });
    }

    @TckTest
    void ac_vnd_21_grouped_only_count_walks_the_derived_treated_join(TckDatabase db) {
        Fields fields = new Fields(JoinType.INNER, false, TypeRestriction.NONE);
        var counted = Agg.<HolderView>count(fields.holderId);
        var query = ModelQuery.builder(fields.root, row -> view(row.get(counted), null, null, null))
                .select(SelectSet.of(counted))
                .groupBy(fields.status).build();
        refusedBeforeAnyStatement(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            assertRefused(() -> executor.page(query, PageSpec.of(0, 2), CountMode.ONLY_COUNT));
        });
    }

    @TckTest
    void ac_vnd_21_left_target_reads_in_where_group_by_and_having_are_checked(TckDatabase db) {
        Fields fields = new Fields(JoinType.LEFT, true, TypeRestriction.NONE);
        var counted = Agg.<HolderView>count(fields.holderId);
        var grouped = ModelQuery.builder(fields.root, row -> view(row.get(counted), null, null, null))
                .select(SelectSet.of(counted, fields.number))
                .groupBy(fields.number, fields.id, fields.untreatedStatus);
        refusedBeforeAnyStatement(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            var where = fields.builder(false).customize((spec, joins, query, cb, phase) ->
                    query.where(cb.equal(fields.untreatedStatus.path(joins), "bank"))).build();
            assertRefused(() -> executor.list(where, Limit.unlimited()));
            assertRefused(() -> executor.list(grouped.build(), Limit.unlimited()));
            var having = ModelQuery.builder(fields.root, row -> view(row.get(counted), null, null, null))
                    .select(SelectSet.of(counted, fields.number)).groupBy(fields.holderId, fields.number, fields.id)
                    .customize((spec, joins, query, cb, phase) ->
                            query.having(cb.equal(fields.untreatedStatus.path(joins), "bank"))).build();
            assertRefused(() -> executor.list(having, Limit.unlimited()));
        });
    }

    @TckTest
    void ac_vnd_21_left_target_order_and_later_keyset_predicate_are_checked(TckDatabase db) {
        Fields fields = new Fields(JoinType.INNER, false, TypeRestriction.NONE);
        seeded(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            String cursor = executor.page(fields.builder(false).keyset().build(), KeysetSpec.first(1))
                    .nextCursor().orElseThrow();
            Fields left = new Fields(JoinType.LEFT, true, TypeRestriction.NONE);
            var config = ModelQueryConfig.defaults().keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
            var leftExecutor = ModelQueryExecutor.create(em, TpHolder.class, config);
            var restricted = left.builder(false).orderBy(left.untreatedStatus.asc().nullsLast(), left.holderId.asc())
                    .keyset().customize((spec, joins, query, cb, phase) -> query.where(
                            left.untreatedStatus.path(joins).getParentPath().type()
                                    .in(cb.literal(TpCard.class), cb.literal(TpPremium.class)))).build();
            String leftCursor = leftExecutor.page(restricted, KeysetSpec.first(1)).nextCursor().orElseThrow();
            refusedBeforeAnyStatement(em, () -> {
                var unsafe = fields.builder(true).keyset().build();
                assertRefused(() -> executor.page(unsafe, KeysetSpec.first(1)));
                assertRefused(() -> executor.page(unsafe, KeysetSpec.after(cursor, 1)));
                assertRefused(() -> leftExecutor.page(left.ordered(), KeysetSpec.first(1)));
                assertRefused(() -> leftExecutor.page(left.ordered(), KeysetSpec.after(leftCursor, 1)));
            });
        });
    }

    @TckTest
    void ac_vnd_21_stream_checks_the_treated_join_before_opening_its_result(TckDatabase db) {
        Fields fields = new Fields(JoinType.INNER, false, TypeRestriction.NONE);
        refusedBeforeAnyStatement(db, em -> {
            var executor = ModelQueryExecutor.create(em, TpHolder.class, ModelQueryConfig.defaults());
            assertRefused(() -> executor.stream(fields.query(true), Limit.unlimited(), rows -> rows.toList()));
        });
    }

    @TckTest
    void ac_vnd_21_left_target_nested_join_reads_are_checked(TckDatabase db) {
        var root = TableField.root(NestedHolder.class);
        var payment = TableField.root(NestedPayment.class);
        var paymentId = ColumnField.of(HolderView.class, payment, "id", Long.class);
        var joined = TableField.<NestedHolder, NestedPayment>join(root, "payment", JoinType.LEFT);
        var target = joined.treat(NestedCard.class).presentBy(PrimaryKey.of(paymentId));
        var currency = TableField.<NestedPayment, Currency>join(joined, "currency", JoinType.LEFT);
        var id = ColumnField.of(HolderView.class, root, "id", Long.class);
        var number = ColumnField.of(HolderView.class, target, "number", String.class);
        var code = ColumnField.of(HolderView.class, currency, "code", String.class);
        var query = ModelQuery.builder(root, row -> view(row.get(id), null, null, null))
                .select(SelectSet.of(id, number, code)).build();
        refusedBeforeAnyStatement(db, em -> {
            var executor = ModelQueryExecutor.create(em, NestedHolder.class, ModelQueryConfig.defaults());
            assertRefused(() -> executor.list(query, Limit.unlimited()));
        });
    }

    private static List<HolderView> allPages(ModelQueryExecutor<TpHolder> executor,
            ModelQuery<TpHolder, Long, HolderView> query) {
        List<HolderView> rows = new ArrayList<>();
        KeysetSpec spec = KeysetSpec.first(1);
        KeysetSlice<HolderView> page;
        do {
            page = executor.page(query, spec);
            rows.addAll(page.content());
            assertThat(rows).hasSizeLessThanOrEqualTo(6);
            if (page.hasNext()) {
                spec = KeysetSpec.after(page.nextCursor().orElseThrow(), 1);
            }
        } while (page.hasNext());
        assertThat(rows).extracting(HolderView::id).doesNotHaveDuplicates();
        return rows;
    }

    private static void refusedBeforeAnyStatement(EntityManager em, Runnable action) {
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        long prepared = statistics.getPrepareStatementCount();
        action.run();
        assertThat(statistics.getPrepareStatementCount()).as("JDBC statements prepared").isEqualTo(prepared);
    }

    private static HolderView view(long id, Long key, String number, String status) {
        return new HolderView(id, number, key == null ? Optional.empty()
                : Optional.of(new CardView(key, number, status)));
    }

    private static void assertRefused(Runnable action) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4016);
    }

    private static void refusedBeforeAnyStatement(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory factory = schema(db)) {
            factory.getStatistics().setStatisticsEnabled(true);
            factory.inSession(em -> {
                long prepared = factory.getStatistics().getPrepareStatementCount();
                work.accept(em);
                assertThat(factory.getStatistics().getPrepareStatementCount()).as("JDBC statements prepared")
                        .isEqualTo(prepared);
            });
        }
    }

    private static void seeded(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory factory = schema(db)) {
            factory.inTransaction(em -> {
                TpCard card = new TpCard();
                card.id = 1L;
                card.status = "paid";
                card.number = "1111";
                TpCard empty = new TpCard();
                empty.id = 2L;
                TpBank sibling = new TpBank();
                sibling.id = 3L;
                sibling.status = "bank";
                TpPremium premium = new TpPremium();
                premium.id = 4L;
                premium.status = "premium";
                premium.number = "4444";
                TpPayment base = new TpPayment();
                base.id = 5L;
                base.status = "base";
                for (TpPayment payment : List.of(card, empty, sibling, premium, base)) {
                    em.persist(payment);
                }
                em.persist(holder(11, card));
                em.persist(holder(12, empty));
                em.persist(holder(13, sibling));
                em.persist(holder(14, null));
                em.persist(holder(15, premium));
                em.persist(holder(16, base));
            });
            factory.inSession(work::accept);
        }
    }

    private static TpHolder holder(long id, TpPayment payment) {
        TpHolder holder = new TpHolder();
        holder.id = id;
        holder.payment = payment;
        return holder;
    }

    private static SessionFactory schema(TckDatabase db) {
        return configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop").buildSessionFactory();
    }

    private static Configuration configuration() {
        return new Configuration().addAnnotatedClass(TpPayment.class).addAnnotatedClass(TpCard.class)
                .addAnnotatedClass(TpBank.class).addAnnotatedClass(TpPremium.class).addAnnotatedClass(TpHolder.class)
                .addAnnotatedClass(NestedPayment.class).addAnnotatedClass(NestedCard.class)
                .addAnnotatedClass(NestedPremium.class).addAnnotatedClass(NestedBank.class)
                .addAnnotatedClass(NestedHolder.class)
                .addAnnotatedClass(Currency.class);
    }

    private record CardView(Long id, String number, String status) {
    }

    private record HolderView(Long id, String number, Optional<CardView> card) {
    }

    private enum TypeRestriction { NONE, LITERAL, PARAMETER }

    private static final class Fields {
        private final TableField<TpHolder, TpHolder> root = TableField.root(TpHolder.class);
        private final ColumnField<HolderView, TpHolder, Long> holderId =
                ColumnField.of(HolderView.class, root, "id", Long.class);
        private final TableField<TpPayment, TpCard> target;
        private final ColumnField<HolderView, TpCard, Long> id;
        private final ColumnField<HolderView, TpCard, String> status;
        private final ColumnField<HolderView, TpCard, String> number;
        private final ColumnField<HolderView, TpPayment, Long> untreatedId;
        private final ColumnField<HolderView, TpPayment, String> untreatedStatus;
        private final boolean presence;

        private Fields(JoinType kind, boolean presence, TypeRestriction restriction) {
            TableField<TpHolder, TpPayment> join = TableField.join(root, "payment", kind);
            if (restriction != TypeRestriction.NONE) {
                join = join.as("card").on((from, cb) -> restriction == TypeRestriction.PARAMETER
                        ? from.type().in(TpCard.class, TpPremium.class)
                        : from.type().in(cb.literal(TpCard.class), cb.literal(TpPremium.class)));
            }
            this.untreatedId = ColumnField.of(HolderView.class, join, "id", Long.class);
            this.untreatedStatus = ColumnField.of(HolderView.class, join, "status", String.class);
            var treated = join.treat(TpCard.class);
            this.target = presence ? treated.presentBy(PrimaryKey.of(PAYMENT_ID)) : treated;
            this.id = ColumnField.of(HolderView.class, target, "id", Long.class);
            this.status = ColumnField.of(HolderView.class, target, "status", String.class);
            this.number = ColumnField.of(HolderView.class, target, "number", String.class);
            this.presence = presence;
        }

        private ModelQuery<TpHolder, Long, HolderView> query(boolean inherited) {
            return builder(inherited).build();
        }

        private ModelQuery<TpHolder, Long, HolderView> ordered() {
            return builder(false).orderBy(untreatedStatus.asc().nullsLast(), holderId.asc()).keyset().build();
        }

        private ModelQuery.Builder<TpHolder, Long, HolderView> builder(boolean inherited) {
            return ModelQuery.builder(root, row -> view(row.get(holderId),
                    presence ? row.scoped(target).get(PAYMENT_ID) : row.get(id), row.get(number), row.get(status)))
                    .select(inherited ? SelectSet.of(holderId, number, id, status) : SelectSet.of(holderId, number))
                    .primaryKey(PrimaryKey.of(holderId)).orderBy(holderId.asc());
        }

        private ModelQuery<TpHolder, Long, HolderView> unguardedQuery() {
            return ModelQuery.builder(root, row -> view(row.get(holderId), row.get(untreatedId), row.get(number), null))
                    .select(SelectSet.of(holderId, number, untreatedId))
                    .primaryKey(PrimaryKey.of(holderId)).orderBy(holderId.asc()).build();
        }
    }
}
