package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Definition-time treated-node contracts (R-COL-34). */
class TreatDefinitionTest {

    static class Payment {}
    static class CardPayment extends Payment {}
    static class PremiumCardPayment extends CardPayment {}
    static class Model {}

    private static final TableField<Payment, Payment> ROOT = TableField.root(Payment.class);

    @Test
    void ac_col_48_count_names_treated_roots_and_joins_from_the_subtype() {
        assertThat(Agg.count(ROOT.treat(CardPayment.class)).name()).isEqualTo("count(cardPayment)");
        TableField<Payment, Payment> join = TableField.join(ROOT, "other", LEFT);
        assertThat(Agg.count(join.treat(CardPayment.class)).name()).isEqualTo("count(cardPayment)");
        assertThat(Agg.count(ROOT).name()).isEqualTo("count(Payment)");
        assertThat(Agg.count(join).name()).isEqualTo("count(other)");
    }

    @Test
    void ac_col_48_improper_root_subtype_treat_of_treat_and_source_root_are_mq1106() {
        assertMq(MqCode.MQ1106, () -> ROOT.treat(Payment.class));
        assertMq(MqCode.MQ1106, () -> ROOT.treat(CardPayment.class).treat(PremiumCardPayment.class));
        assertMq(MqCode.MQ1106, () -> TableField.source(Payment.class).treat(CardPayment.class));
        assertMq(MqCode.MQ1106, () -> TableField.source(Payment.class).named("payments").treat(CardPayment.class));
    }

    @Test
    void ac_col_48_all_treated_root_modifiers_are_mq1104() {
        var treated = ROOT.treat(CardPayment.class);
        var id = ColumnField.of(Model.class, TableField.root(CardPayment.class), "id", Long.class);
        assertMq(MqCode.MQ1104, () -> treated.as("card"));
        assertMq(MqCode.MQ1104, () -> treated.on((from, cb) -> cb.conjunction()));
        assertMq(MqCode.MQ1104, () -> treated.presentBy(PrimaryKey.of(id)));
    }

    @Test
    void ac_col_48_join_modifiers_precede_treat_and_presence_metadata_survives_copies() {
        TableField<Payment, Payment> join = TableField.join(ROOT, "other", LEFT);
        var id = ColumnField.of(Model.class, TableField.root(CardPayment.class), "id", Long.class);
        var key = PrimaryKey.of(id);
        var treated = join.as("card").on((from, cb) -> cb.conjunction()).treat(CardPayment.class);
        assertMq(MqCode.MQ1106, () -> treated.as("again"));
        assertMq(MqCode.MQ1106, () -> treated.on((from, cb) -> cb.conjunction()));
        var present = treated.presentBy(key);
        assertThat(present).isEqualTo(treated);
        assertMq(MqCode.MQ1106, () -> present.named("card"));
        assertThat(join.named("card").treat(CardPayment.class).presentBy(key).presenceKey()).isSameAs(key);
        assertThat(present.withParent(join).presenceKey()).isSameAs(key);
        assertThat(join.presentBy(key).treat(CardPayment.class).presenceKey()).isSameAs(key);
    }

    @Test
    void ac_col_48_a_treated_root_is_named_before_its_treat() {
        assertMq(MqCode.MQ1106, () -> ROOT.treat(CardPayment.class).named("card"));
        assertThat(ROOT.named("card").treat(CardPayment.class)).isNotNull();
    }

    private static void assertMq(MqCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
