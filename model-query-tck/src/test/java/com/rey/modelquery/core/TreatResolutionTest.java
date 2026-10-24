package com.rey.modelquery.core;

import static com.rey.modelquery.tck.col.TreatTestSupport.BANK_JOIN;
import static com.rey.modelquery.tck.col.TreatTestSupport.CARD;
import static com.rey.modelquery.tck.col.TreatTestSupport.CARD_JOIN;
import static com.rey.modelquery.tck.col.TreatTestSupport.HOLDERS;
import static com.rey.modelquery.tck.col.TreatTestSupport.ID;
import static com.rey.modelquery.tck.col.TreatTestSupport.NUMBER;
import static com.rey.modelquery.tck.col.TreatTestSupport.PAYMENT;
import static com.rey.modelquery.tck.col.TreatTestSupport.ROOT;
import static com.rey.modelquery.tck.col.TreatTestSupport.factory;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.tck.col.TreatTestSupport.Bank;
import com.rey.modelquery.tck.col.TreatTestSupport.Card;
import com.rey.modelquery.tck.col.TreatTestSupport.CountingRenderer;
import com.rey.modelquery.tck.col.TreatTestSupport.Holder;
import com.rey.modelquery.tck.col.TreatTestSupport.Payment;
import com.rey.modelquery.tck.col.TreatTestSupport.Premium;
import com.rey.modelquery.tck.col.TreatTestSupport.Unmapped;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import org.hibernate.SessionFactory;

class TreatResolutionTest {

    @TckTest
    void ac_col_48_declared_join_targets_and_reached_types_are_checked_separately(TckDatabase db) {
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                CriteriaBuilder cb = factory.getCriteriaBuilder();
                var holderQuery = cb.createQuery();
                JoinContext holders = JoinContext.of(holderQuery.from(Holder.class), cb);
                assertThat(assertMq(() -> CARD.resolve(holders), MqCode.MQ1106))
                        .hasMessageContaining(Card.class.getSimpleName(), Holder.class.getSimpleName());
                String improper = assertMq(() -> ROOT.treat(Payment.class), MqCode.MQ1106).getMessage();
                assertThat(assertMq(() -> PAYMENT.treat(Payment.class).resolve(holders), MqCode.MQ1106))
                        .hasMessage(improper);
                assertMq(() -> CARD_JOIN.treat(Card.class).resolve(holders), MqCode.MQ1106);
                assertMq(() -> CARD_JOIN.treat(Card.class).under(PAYMENT).resolve(holders), MqCode.MQ1106);
                assertThat(assertMq(() -> CARD.under(BANK_JOIN).resolve(holders), MqCode.MQ1106))
                        .hasMessageContaining(Card.class.getSimpleName(), Bank.class.getSimpleName());
                var bankQuery = cb.createQuery();
                JoinContext bank = JoinContext.of(bankQuery.from(Bank.class), cb);
                assertThat(assertMq(() -> CARD.resolve(bank), MqCode.MQ1106))
                        .hasMessageContaining(Card.class.getSimpleName(), Bank.class.getSimpleName());
                var paymentQuery = cb.createQuery();
                JoinContext payments = JoinContext.of(paymentQuery.from(Payment.class), cb);
                assertMq(() -> ROOT.treat(Unmapped.class).resolve(payments), MqCode.MQ1106);
                assertThat(assertMq(() -> ColumnField.of(String.class, TableField.root(Card.class), "number",
                        String.class).expression(payments), MqCode.MQ1003))
                        .hasMessageContaining("ROOT.treat(Card.class)");
            }
        })).isEmpty();
    }

    @TckTest
    void ac_src_56_an_unregistered_treated_entity_join_is_mq1003(TckDatabase db) {
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                CriteriaBuilder cb = factory.getCriteriaBuilder();
                var query = cb.createQuery();
                JoinContext holders = JoinContext.of(query.from(Holder.class), cb);
                var joined = TableField.joinedEntity("unregistered", Payment.class);
                assertMq(() -> joined.resolve(holders), MqCode.MQ1003);
                assertMq(() -> joined.treat(Card.class).resolve(holders), MqCode.MQ1003);
                assertMq(() -> ColumnField.of(String.class, joined.treat(Card.class), "number", String.class)
                        .expression(holders), MqCode.MQ1003);
            }
        })).isEmpty();
    }

    @TckTest
    void ac_col_48_treats_resolve_over_the_reached_entity_and_cache_only_in_the_context(TckDatabase db) {
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                CriteriaBuilder cb = factory.getCriteriaBuilder();
                CountingRenderer renderer = new CountingRenderer();
                RenderOptions options = RenderOptions.portable().withTreatRenderer(renderer);
                var query = cb.createQuery();
                var root = query.from(Payment.class);
                JoinContext payments = JoinContext.of(root, cb, query, options);
                From<?, Card> treated = CARD.resolve(payments);
                assertThat(treated.getJavaType()).isEqualTo(Card.class);
                assertThat(ROOT.treat(Card.class).resolve(payments)).isSameAs(treated);
                TableField<Card, Holder> issuer = TableField.join(CARD, "issuer", JoinType.LEFT);
                assertThat(issuer.resolve(payments).getParentPath()).isSameAs(treated);
                assertThat(renderer.calls).isEqualTo(1);
                var second = cb.createQuery();
                CARD.resolve(JoinContext.of(second.from(Payment.class), cb, second, options));
                assertThat(renderer.calls).isEqualTo(2);
                var holderQuery = cb.createQuery();
                JoinContext holders = JoinContext.of(holderQuery.from(Holder.class), cb, holderQuery, options);
                assertThat(PAYMENT.treat(Card.class).resolve(holders).getJavaType()).isEqualTo(Card.class);
                TableField<Holder, Payment> many = TableField.join(HOLDERS, "payments", JoinType.LEFT);
                assertThat(many.treat(Card.class).resolve(holders).getJavaType()).isEqualTo(Card.class);
                int calls = renderer.calls;
                assertThat(CARD.under(CARD_JOIN).resolve(holders)).isSameAs(CARD_JOIN.resolve(holders));
                var premiumQuery = cb.createQuery();
                var premium = premiumQuery.from(Premium.class);
                assertThat(CARD.resolve(JoinContext.of(premium, cb, premiumQuery, options))).isSameAs(premium);
                assertThat(renderer.calls).isEqualTo(calls);
                assertThat(TableField.root(Card.class).treat(Premium.class).resolve(payments).getJavaType())
                        .isEqualTo(Premium.class);
                ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(ID, NUMBER))
                        .primaryKey(PrimaryKey.of(ID)).customize((spec, joins, statement, builder, phase) -> {})
                        .build().buildQuery(cb, Phase.MODEL, options);
            }
        })).isEmpty();
    }

    @TckTest
    void ac_vnd_16_no_renderer_refuses_even_an_untreated_resolution(TckDatabase db) {
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                factory.inSession(em -> {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    var cardQuery = cb.createQuery();
                    assertMq(() -> CARD.resolve(JoinContext.of(cardQuery.from(Card.class), cb)), MqCode.MQ4014);
                    var holderQuery = cb.createQuery();
                    JoinContext holders = JoinContext.of(holderQuery.from(Holder.class), cb);
                    assertMq(() -> PAYMENT.treat(Card.class).resolve(holders),
                            MqCode.MQ4014);
                });
            }
        })).isEmpty();
    }

    private static ModelQueryException assertMq(Runnable action, MqCode code) {
        ModelQueryException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ModelQueryException.class, action::run);
        assertThat(failure.code()).isEqualTo(code);
        return failure;
    }

}
