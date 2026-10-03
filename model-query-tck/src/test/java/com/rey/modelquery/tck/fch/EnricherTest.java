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
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * {@code byKey} and {@code of} enrichers read columns the plan selects for them, and run once per page after the
 * plan's children, a join plan's before the outer plan's; {@code of} must return a page of the same size (spec api/15
 * R-FCH-08, AC-FCH-06, AC-FCH-12).
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

        // AC-FCH-12: the nested plan's enricher fills each joined Patron (a @Join model), once per page and join.
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

    // ---- AC-FCH-13

    /** A user profile's key: a user id and a user type, the adopter's composite key of D-111 item 8. */
    private record UserRef(long userId, int userTypeId) {}

    /** A profile service on another datasource, in memory: a Map, counting the pages it is asked for. */
    private static final class ProfileService {

        private final Map<UserRef, String> profiles = Map.of(new UserRef(1, 2), "gold", new UserRef(3, 4), "gold");
        private final List<Set<UserRef>> lookups = new ArrayList<>();

        List<Set<UserRef>> lookups() {
            return lookups;
        }

        Map<UserRef, String> find(Set<UserRef> refs) {
            lookups.add(Set.copyOf(refs));
            var found = new LinkedHashMap<UserRef, String>();
            for (UserRef ref : refs) {
                if (profiles.containsKey(ref)) {
                    found.put(ref, profiles.get(ref));
                }
            }
            return found;
        }
    }

    private static final ProfileService PROFILES = new ProfileService();

    /** One enricher, declared once and reused below on a root query and on a nested plan (D-111 item 8). */
    private static final Enricher<Line> PROFILE_ENRICHER = Enricher.byKey(
            line -> new UserRef(line.id(), line.quantity()),
            PROFILES::find,
            (line, profile) -> line.withProfile(profile),
            QLine.ID, QLine.QUANTITY);

    /** Order items 1 to 4, whose quantities are 2, 3, 4 and 5. */
    private static ModelQuery<OrderItemEntity, Long, Line> items() {
        return QLine.query()
                .fetch(FetchPlan.of(SelectSet.of(QLine.ID, QLine.QUANTITY)).enrich(PROFILE_ENRICHER))
                .where(f -> f.in(QLine.ID, List.of(1L, 2L, 3L, 4L)))
                .orderBy(QLine.ID.asc())
                .build();
    }

    /** Order 1 and its items 1, 5001, 10001 and 15001, the enricher on the nested plan. */
    private static ModelQuery<OrderEntity, Long, OrderLines> orderWithItems() {
        return QOrderLines.query()
                .fetch(FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS,
                        FetchPlan.of(SelectSet.of(QLine.ID, QLine.QUANTITY)).enrich(PROFILE_ENRICHER)))
                .where(f -> f.eq(QOrderLines.ID, 1L))
                .build();
    }

    @TckTest
    void ac_fch_13_a_composite_key_enricher_looks_up_once_per_page_and_is_reused_on_a_nested_plan(TckDatabase db) {
        PROFILES.lookups().clear();
        // Only the keys (1, 2) and (3, 4) have a profile; the other models are left as they are.
        withExecutor(JoinTestSupport.dataSource(db), OrderItemEntity.class, executor -> {
            List<Line> lines = executor.list(items(), Limit.unlimited());

            assertThat(lines).extracting(Line::id).containsExactly(1L, 2L, 3L, 4L);
            assertThat(lines).extracting(Line::profile).containsExactly("gold", null, "gold", null);
            assertThat(PROFILES.lookups()).containsExactly(Set.of(
                    new UserRef(1, 2), new UserRef(2, 3), new UserRef(3, 4), new UserRef(4, 5)));
        });

        PROFILES.lookups().clear();
        // The same enricher constant on a nested plan: one lookup for the page's distinct composite keys.
        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor -> {
            List<Line> lines = executor.list(orderWithItems(), Limit.unlimited()).get(0).items();

            assertThat(lines).extracting(Line::id).containsExactly(1L, 5001L, 10001L, 15001L);
            assertThat(lines).extracting(Line::profile).containsExactly("gold", null, null, null);
            assertThat(PROFILES.lookups()).containsExactly(Set.of(
                    new UserRef(1, 2), new UserRef(5001, 7), new UserRef(10001, 3), new UserRef(15001, 8)));
        });
    }

    /** Orders 1 to 3 by id, with {@code plan}. */
    private static ModelQuery<OrderEntity, Long, OrderPatrons> byId(FetchPlan<OrderPatrons> plan) {
        return QOrderPatrons.query().fetch(plan).where(f -> f.lte(QOrderPatrons.ID, 3L))
                .orderBy(QOrderPatrons.ID.asc()).build();
    }
}
