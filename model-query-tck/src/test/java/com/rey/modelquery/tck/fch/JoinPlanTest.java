package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.grouped;
import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * One plan fills a model on its own and through its {@code @Join}s: two aliased joins to one entity and a LEFT join
 * that found nothing for two orders of three; its selection re-roots to the generated join constants (spec api/15
 * R-FCH-07, AC-FCH-05).
 */
class JoinPlanTest {

    private static final long LAST_ORDER = 12;

    /** A patron's key and orders, without its name: on its own, and under each join. */
    private static final FetchPlan<Patron> PATRON =
            FetchPlan.of(SelectSet.of(QPatron.ID)).child(QPatron.ORDERS, FetchPlan.of(QOrderRef.ALL));
    private static final FetchPlan<OrderPatrons> ORDER = FetchPlan.of(QOrderPatrons.ALL)
            .join(QOrderPatrons.CUSTOMER_JOIN, PATRON)
            .join(QOrderPatrons.BUYER_JOIN, PATRON)
            .join(QOrderPatrons.REFERRER_JOIN, PATRON);

    /** Orders 1 to {@link #LAST_ORDER} by id, with {@link #ORDER}. */
    private static ModelQuery<OrderEntity, Long, OrderPatrons> orders() {
        return QOrderPatrons.query().fetch(ORDER).where(f -> f.lte(QOrderPatrons.ID, LAST_ORDER))
                .orderBy(QOrderPatrons.ID.asc()).build();
    }

    @Test
    void ac_fch_05_the_re_rooted_columns_equal_the_generated_join_constants() {
        assertThat(orders().select().fields()).containsExactly(QOrderPatrons.ID, QOrderPatrons.STATUS,
                QOrderPatrons.CUSTOMER_ID, QOrderPatrons.BUYER_ID, QOrderPatrons.REFERRER_ID);
    }

    @TckTest
    void ac_fch_05_one_plan_fills_a_model_on_its_own_and_through_aliased_and_left_joins(TckDatabase db) {
        Map<Long, List<Long>> ordersOf = grouped(db, "SELECT customer_id, id FROM orders ORDER BY id");
        Map<Long, List<Long>> referrerOf = grouped(db, "SELECT id, referrer_id FROM orders WHERE referrer_id IS NOT "
                + "NULL AND id <= " + LAST_ORDER);

        List<String> sql = SqlSnapshots.assertMatches(db, "fch-05-join-plans", ds -> withExecutor(ds,
                OrderEntity.class, executor -> {
                    List<OrderPatrons> listed = executor.list(orders(), Limit.unlimited());
                    assertThat(listed).extracting(OrderPatrons::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L,
                            9L, 10L, 11L, 12L);
                    for (OrderPatrons order : listed) {
                        long customer = order.id() * 37 % 1_000 + 1;
                        assertFilled(order.customer(), customer, ordersOf);
                        assertFilled(order.buyer(), customer, ordersOf);
                        List<Long> referrer = referrerOf.get(order.id());
                        if (referrer == null) {
                            assertThat(order.referrer()).as("referrer of order %s", order.id()).isEmpty();
                        } else {
                            assertFilled(order.referrer(), referrer.get(0), ordersOf);
                        }
                    }
                    // The page after the probe row is dropped fills the same.
                    assertThat(executor.page(orders(), PageSpec.of(0, 6), CountMode.NO_COUNT).content())
                            .containsExactlyElementsOf(listed.subList(0, 6));
                }));
        // Per call: the page, then one child statement per join plan, each over its own join's patrons.
        assertThat(sql).hasSize(8);
        assertThat(referrerOf).hasSize(4);

        // On its own, the same plan fills the same patrons.
        withExecutor(JoinTestSupport.dataSource(db), CustomerEntity.class, executor -> {
            List<Patron> patrons = executor.list(QPatron.query().fetch(PATRON)
                    .where(f -> f.in(QPatron.ID, List.of(38L, 75L, 112L))).orderBy(QPatron.ID.asc()).build(),
                    Limit.unlimited());
            assertThat(patrons).extracting(Patron::id).containsExactly(38L, 75L, 112L);
            patrons.forEach(patron -> assertFilled(Optional.of(patron), patron.id(), ordersOf));
        });
    }

    @Test
    void ac_fch_05_a_join_plan_with_nothing_selected_throws_mq1701_and_one_selecting_an_aggregate_mq1705() {
        var nothing = FetchPlan.of(QOrderPatrons.ALL)
                .join(QOrderPatrons.REFERRER_JOIN, FetchPlan.of(SelectSet.<Patron>of()));
        var counted = FetchPlan.of(QOrderPatrons.ALL).join(QOrderPatrons.REFERRER_JOIN,
                FetchPlan.of(SelectSet.<Patron>of(QPatron.ID, Agg.count(QPatron.ROOT))));

        assertDefinitionCode(() -> QOrderPatrons.query().fetch(nothing).build(), MqCode.MQ1701,
                "MQ1701: OrderPatrons.referrer: the join plan selects no column under its join, which then reads no "
                        + "nested model; select a column of the nested model in its plan, or drop the join(...)");
        assertDefinitionCode(() -> QOrderPatrons.query().fetch(counted).build(), MqCode.MQ1705,
                "MQ1705: OrderPatrons.referrer: the join plan selects the aggregate count(CustomerEntity), which "
                        + "cannot be re-rooted under the join; select the aggregate in the outer plan");
    }

    @Test
    void ac_fch_18_a_join_plan_selecting_an_expression_throws_mq1705() {
        // The nested expression cannot be re-rooted under the generated join, so it is refused at build (R-FCH-07).
        var plusOne = Expr.plus(QPatron.ID, 1L);
        var plan = FetchPlan.of(QOrderPatrons.ALL)
                .join(QOrderPatrons.REFERRER_JOIN, FetchPlan.of(SelectSet.<Patron>of(plusOne)));
        String message = "MQ1705: OrderPatrons.referrer: the join plan selects the expression " + plusOne.name()
                + ", which cannot be re-rooted under the join; select the expression in the outer plan";

        assertDefinitionCode(() -> QOrderPatrons.query().fetch(plan).build(), MqCode.MQ1705, message);
        var query = QOrderPatrons.query().select(QOrderPatrons.ALL).build();
        assertDefinitionCode(() -> query.withFetch(plan), MqCode.MQ1705, message);
    }

    /** {@code patron} is present, is customer {@code id}, without its name, and holds that customer's orders. */
    private static void assertFilled(Optional<Patron> patron, long id, Map<Long, List<Long>> ordersOf) {
        assertThat(patron).as("patron %s", id).hasValueSatisfying(p -> {
            assertThat(p.id()).isEqualTo(id);
            assertThat(p.name()).isNull();
            assertThat(p.orders()).extracting(OrderRef::id).as("orders of patron %s", id)
                    .isNotEmpty().containsExactlyElementsOf(ordersOf.get(id));
        });
    }

    private static void assertDefinitionCode(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code)).hasMessage(message);
    }
}
