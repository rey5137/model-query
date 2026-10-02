package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.grouped;
import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * {@code byKey} and {@code of} enrichers read columns the plan selects for them, and run once per page after the
 * plan's children, a join plan's before the outer plan's; {@code of} must return a page of the same size (spec api/15
 * R-FCH-08, AC-FCH-06).
 */
class EnricherTest {

    /** What the enrichers of one plan saw: the byKey lookups' keys, the nested and the outer pages' sizes. */
    private record Calls(List<Set<String>> lookups, List<Integer> nested, List<Integer> outer) {
        Calls() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Orders by id: each patron, read by key alone, tagged by name with its order count, and each order noted with
     * its status and its customer's tag.
     */
    private static ModelQuery<OrderEntity, Long, OrderPatrons> orders(List<Long> ids, Calls calls) {
        FetchPlan<Patron> patron = FetchPlan.of(SelectSet.of(QPatron.ID))
                .child(QPatron.ORDERS, FetchPlan.of(QOrderRef.ALL))
                .enrich(Enricher.byKey(Patron::name, names -> {
                    calls.lookups().add(Set.copyOf(names));
                    return names.stream().collect(Collectors.toMap(Function.identity(), name -> name + "/"));
                }, (p, tag) -> p.withTag(tag + p.orders().size()), QPatron.NAME))
                .enrich(Enricher.of(page -> {
                    calls.nested().add(page.size());
                    return page;
                }));
        FetchPlan<OrderPatrons> order = FetchPlan.of(SelectSet.of(QOrderPatrons.ID))
                .join(QOrderPatrons.CUSTOMER_JOIN, patron)
                .join(QOrderPatrons.REFERRER_JOIN, patron)
                .enrich(Enricher.of(page -> {
                    calls.outer().add(page.size());
                    return page.stream().map(o -> o.withNote(o.status() + " "
                            + o.customer().map(Patron::tag).orElseThrow())).toList();
                }, QOrderPatrons.STATUS));
        return QOrderPatrons.query().fetch(order).where(f -> f.in(QOrderPatrons.ID, ids))
                .orderBy(QOrderPatrons.ID.asc()).build();
    }

    @TckTest
    void ac_fch_06_enrichers_see_their_columns_and_run_once_per_page_after_children_nested_first(TckDatabase db) {
        Map<Long, List<Long>> ordersOf = grouped(db, "SELECT customer_id, id FROM orders ORDER BY id");
        var calls = new Calls();
        // Orders 1 and 1001 share customer 38; of orders 1 to 3, only 3 has a referrer.
        List<Long> ids = List.of(1L, 2L, 3L, 1001L);

        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor -> {
            ModelQuery<OrderEntity, Long, OrderPatrons> q = orders(ids, calls);
            assertThat(q.select().fields()).containsExactly(QOrderPatrons.ID, QOrderPatrons.CUSTOMER_ID,
                    QOrderPatrons.REFERRER_ID);
            // The probe row, order 1001, is dropped before the plan runs.
            List<OrderPatrons> page = executor.page(q, PageSpec.of(0, 3), CountMode.NO_COUNT).content();

            assertThat(page).extracting(OrderPatrons::id).containsExactly(1L, 2L, 3L);
            for (OrderPatrons order : page) {
                long customer = order.id() * 37 % 1_000 + 1;
                String tag = "Customer " + String.format("%04d", customer) + "/" + ordersOf.get(customer).size();
                assertThat(order.customer()).get().extracting(Patron::tag).isEqualTo(tag);
                assertThat(order.status()).isNotNull();
                assertThat(order.note()).isEqualTo(order.status() + " " + tag);
            }
            assertThat(page.get(2).referrer()).get().extracting(Patron::tag).asString().startsWith("Customer ");
            // Once per page, per join plan: three customers, then order 3's referrer.
            assertThat(calls.lookups()).extracting(Set::size).containsExactly(3, 1);
            assertThat(calls.nested()).containsExactly(3, 1);
            assertThat(calls.outer()).containsExactly(3);

            // A join plan's enricher gets one entry per present nested model, duplicates included.
            calls.lookups().clear();
            calls.nested().clear();
            calls.outer().clear();
            List<OrderPatrons> listed = executor.list(orders(List.of(1L, 1001L), calls), Limit.unlimited());
            assertThat(listed).extracting(o -> o.customer().orElseThrow().tag()).containsExactly(
                    "Customer 0038/" + ordersOf.get(38L).size(), "Customer 0038/" + ordersOf.get(38L).size());
            assertThat(calls.lookups()).containsExactly(Set.of("Customer 0038"));
            assertThat(calls.nested()).containsExactly(2);

            // Once per export page, on that page's models.
            calls.outer().clear();
            List<Long> thirty = LongStream.rangeClosed(1, 30).boxed().toList();
            long exported = executor.export(orders(thirty, calls), ExportOptions.of(7), p -> p, o -> {
                assertThat(o.note()).startsWith(o.status() + " Customer ");
            });
            assertThat(exported).isEqualTo(30);
            assertThat(calls.outer()).containsExactly(7, 7, 7, 7, 2);
        });
    }

    @TckTest
    void ac_fch_06_an_of_enricher_returning_another_size_or_null_throws_mq2602(TckDatabase db) {
        FetchPlan<OrderPatrons> shorter = FetchPlan.of(QOrderPatrons.ALL)
                .enrich(Enricher.of(page -> page.subList(1, page.size())));
        FetchPlan<OrderPatrons> nested = FetchPlan.of(QOrderPatrons.ALL).join(QOrderPatrons.CUSTOMER_JOIN,
                FetchPlan.of(QPatron.ALL).enrich(Enricher.of(page -> null)));

        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor -> {
            assertThatThrownBy(() -> executor.list(byId(shorter), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2602))
                    .hasMessage("MQ2602: OrderPatrons: an Enricher.of returned a page of 2 for a page of 3 models; "
                            + "return one model per model of the page, filled");
            assertThatThrownBy(() -> executor.list(byId(nested), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2602))
                    .hasMessage("MQ2602: OrderPatrons.customer: an Enricher.of returned null for a page of 3 "
                            + "models; return one model per model of the page, filled");
        });
    }

    /** Orders 1 to 3 by id, with {@code plan}. */
    private static ModelQuery<OrderEntity, Long, OrderPatrons> byId(FetchPlan<OrderPatrons> plan) {
        return QOrderPatrons.query().fetch(plan).where(f -> f.lte(QOrderPatrons.ID, 3L))
                .orderBy(QOrderPatrons.ID.asc()).build();
    }
}
