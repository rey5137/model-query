package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.PaymentOrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A {@code byKeys} enricher routes each key's value to its own field: four actors of a payment order, one lookup per
 * page for the distinct non-null keys of every model and role, chunked by {@code batchSize}, each chunk handed to the
 * lookup once, and a child's load reused by the outer plan of a plan built per call (spec api/15 R-FCH-15 to R-FCH-17,
 * AC-FCH-14 to AC-FCH-17).
 */
class ByKeysEnricherTest {

    /** The six distinct actor keys of the fixture's payment orders, in first-seen order (TCK AC-FCH-14). */
    private static final List<ActorKey> ALL_KEYS = List.of(new ActorKey(1, 2), new ActorKey(2, 1),
            new ActorKey(3, 2), new ActorKey(1, 1), new ActorKey(1, 3), new ActorKey(3, 1));

    /** The four profiles of each order, row by row: the payee of rows 2 and 5 is the key no source holds. */
    private static final List<String> PAYERS = List.of("P-1-2", "P-1-1", "P-1-2", "P-1-1", "P-1-2", "P-1-1");
    private static final List<String> PAYEES = Arrays.asList("P-1-2", null, "P-1-1", "P-1-2", null, "P-1-1");
    private static final List<String> REQUESTORS = Arrays.asList("P-3-2", "P-3-1", "P-3-2", null, "P-3-2", "P-3-1");

    /** A profile store with one source per user type, as a lookup splitting its own keys would have (R-FCH-16). */
    private static final class SplitProfiles {

        private final Map<Integer, Map<Long, String>> byType = Map.of(
                1, Map.of(1L, "P-1-1", 2L, "P-1-2"),
                2, Map.of(1L, "P-2-1"),
                3, Map.of(1L, "P-3-1", 2L, "P-3-2"));
        private final List<List<ActorKey>> lookups = new ArrayList<>();
        private final Map<Integer, List<Set<Long>>> callsByType = new LinkedHashMap<>();

        Map<ActorKey, String> find(Set<ActorKey> keys) {
            lookups.add(List.copyOf(keys));
            var grouped = new LinkedHashMap<Integer, Set<Long>>();
            for (ActorKey key : keys) {
                grouped.computeIfAbsent(key.userType(), type -> new LinkedHashSet<>()).add(key.userId());
            }
            var found = new LinkedHashMap<ActorKey, String>();
            grouped.forEach((type, ids) -> {
                callsByType.computeIfAbsent(type, t -> new ArrayList<>()).add(Set.copyOf(ids));
                Map<Long, String> source = byType.getOrDefault(type, Map.of());
                for (long id : ids) {
                    String profile = source.get(id);
                    if (profile != null) {
                        found.put(new ActorKey(type, id), profile);
                    }
                }
            });
            return found;
        }

        List<List<ActorKey>> lookups() {
            return lookups;
        }

        Map<Integer, List<Set<Long>>> callsByType() {
            return callsByType;
        }
    }

    /** One binding per actor role, all routing a profile of the same store. */
    private static Enricher.Keys<PaymentOrderView, ActorKey, String> actorKeys(SplitProfiles profiles) {
        return Enricher.<PaymentOrderView, ActorKey, String>byKeys(profiles::find)
                .key(PaymentOrderView::payerKey, PaymentOrderView::withPayer)
                .key(PaymentOrderView::payeeKey, PaymentOrderView::withPayee)
                .key(PaymentOrderView::initiatorKey, PaymentOrderView::withInitiator)
                .key(PaymentOrderView::requestorKey, PaymentOrderView::withRequestor);
    }

    private static Enricher<PaymentOrderView> actors(SplitProfiles profiles) {
        return keysTo(actorKeys(profiles));
    }

    /** The eight actor key columns the enricher declares, and the finished enricher. */
    private static Enricher<PaymentOrderView> keysTo(Enricher.Keys<PaymentOrderView, ActorKey, String> keys) {
        return keys.reading(keyColumns());
    }

    private static ColumnField<PaymentOrderView, ?, ?>[] keyColumns() {
        return new ColumnField[] {QPaymentOrderView.PAYER_USER_TYPE, QPaymentOrderView.PAYER_USER_ID,
                QPaymentOrderView.PAYEE_USER_TYPE, QPaymentOrderView.PAYEE_USER_ID,
                QPaymentOrderView.INITIATOR_USER_TYPE, QPaymentOrderView.INITIATOR_USER_ID,
                QPaymentOrderView.REQUESTOR_USER_TYPE, QPaymentOrderView.REQUESTOR_USER_ID};
    }

    /** Every payment order, by id, with the enricher's eight columns asked of the plan, not of the query. */
    private static ModelQuery.Builder<PaymentOrderEntity, Long, PaymentOrderView> paymentOrders(
            Enricher<PaymentOrderView> actors) {
        return QPaymentOrderView.query()
                .fetch(FetchPlan.of(SelectSet.of(QPaymentOrderView.ID)).enrich(actors))
                .orderBy(QPaymentOrderView.ID.asc());
    }

    // ---- AC-FCH-14

    @TckTest
    void ac_fch_14_by_keys_fills_the_four_actors_from_one_lookup_over_the_eight_key_columns(TckDatabase db) {
        var profiles = new SplitProfiles();
        var query = paymentOrders(actors(profiles));

        withExecutor(JoinTestSupport.dataSource(db), PaymentOrderEntity.class, executor -> {
            // The model's own selection omits the key columns; the enricher's eight are selected and mapped (R-FCH-02).
            assertThat(query.build().select().fields()).containsExactly(QPaymentOrderView.ID);

            List<PaymentOrderView> orders = executor.list(query.build(), Limit.unlimited());

            assertThat(orders).extracting(PaymentOrderView::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
            assertThat(orders).extracting(PaymentOrderView::payerUserType).containsOnly(1);
            assertThat(orders).extracting(PaymentOrderView::payerUserId).containsExactly(2L, 1L, 2L, 1L, 2L, 1L);
            assertThat(orders).extracting(PaymentOrderView::payeeUserId).containsExactly(2L, 3L, 1L, 2L, 3L, 1L);
            assertThat(orders).extracting(PaymentOrderView::initiatorUserType).containsOnly(2);
            assertThat(orders).extracting(PaymentOrderView::initiatorUserId).containsOnly(1L);
            assertThat(orders).extracting(PaymentOrderView::requestorUserType).containsExactly(3, 3, 3, null, 3, 3);
            assertThat(orders).extracting(PaymentOrderView::requestorUserId).containsExactly(2L, 1L, 2L, null, 2L, 1L);
            // One lookup for the six distinct non-null keys, across models and roles, in first-seen order.
            assertThat(profiles.lookups()).hasSize(1);
            assertThat(profiles.lookups().get(0)).containsExactlyElementsOf(ALL_KEYS);
            // Every value lands in its role's field: (1, 2) fills the payer and payee of row 1 and the payee of row 4.
            assertThat(orders).extracting(PaymentOrderView::payer).containsExactlyElementsOf(PAYERS);
            assertThat(orders).extracting(PaymentOrderView::payee).containsExactlyElementsOf(PAYEES);
            assertThat(orders).extracting(PaymentOrderView::initiator).containsOnly("P-2-1");
            // The absent requestors are never in the set and keep the field they were mapped with.
            assertThat(orders).extracting(PaymentOrderView::requestor).containsExactlyElementsOf(REQUESTORS);
        });
    }

    // ---- AC-FCH-15

    @TckTest
    void ac_fch_15_by_keys_runs_on_every_page_shape_and_once_per_export_batch(TckDatabase db) {
        var profiles = new SplitProfiles();
        var query = paymentOrders(actors(profiles));

        withExecutor(JoinTestSupport.dataSource(db), PaymentOrderEntity.class, executor -> {
            var all = executor.list(query.build(), Limit.unlimited());
            assertThat(all).extracting(PaymentOrderView::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
            assertThat(all).extracting(PaymentOrderView::payer).containsExactlyElementsOf(PAYERS);
            assertThat(profiles.lookups()).hasSize(1);

            profiles.lookups().clear();
            var offset = executor.page(query.build(), PageSpec.ofOffset(1, 2), CountMode.NO_COUNT).content();
            assertThat(offset).extracting(PaymentOrderView::id).containsExactly(2L, 3L);
            assertThat(offset).extracting(PaymentOrderView::payer).containsExactly("P-1-1", "P-1-2");
            assertThat(offset).extracting(PaymentOrderView::payee).containsExactly(null, "P-1-1");
            assertThat(offset).extracting(PaymentOrderView::requestor).containsExactly("P-3-1", "P-3-2");
            assertThat(profiles.lookups()).hasSize(1);

            profiles.lookups().clear();
            var keysetOffset = executor.page(query.keyset().build(), PageSpec.ofOffset(1, 2), CountMode.NO_COUNT)
                    .content();
            assertThat(keysetOffset).extracting(PaymentOrderView::id).containsExactly(2L, 3L);
            assertThat(keysetOffset).extracting(PaymentOrderView::payer).containsExactly("P-1-1", "P-1-2");
            assertThat(profiles.lookups()).hasSize(1);

            profiles.lookups().clear();
            var primaryKeyFirst = executor.page(query.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build(),
                    PageSpec.ofOffset(1, 2), CountMode.NO_COUNT).content();
            assertThat(primaryKeyFirst).extracting(PaymentOrderView::id).containsExactly(2L, 3L);
            assertThat(primaryKeyFirst).extracting(PaymentOrderView::payer).containsExactly("P-1-1", "P-1-2");
            assertThat(profiles.lookups()).hasSize(1);

            // A cursor: the first page and the page after it, each enriched and each one lookup.
            profiles.lookups().clear();
            KeysetSlice<PaymentOrderView> first = executor.page(query.keyset().build(), KeysetSpec.first(2));
            assertThat(first.content()).extracting(PaymentOrderView::id).containsExactly(1L, 2L);
            assertThat(first.content()).extracting(PaymentOrderView::payer).containsExactly("P-1-2", "P-1-1");
            assertThat(first.content()).extracting(PaymentOrderView::payee).containsExactly("P-1-2", null);
            assertThat(profiles.lookups()).hasSize(1);

            profiles.lookups().clear();
            KeysetSlice<PaymentOrderView> after = executor.page(query.keyset().build(),
                    KeysetSpec.after(first.nextCursor().orElseThrow(), 2));
            assertThat(after.content()).extracting(PaymentOrderView::id).containsExactly(3L, 4L);
            assertThat(after.content()).extracting(PaymentOrderView::payer).containsExactly("P-1-2", "P-1-1");
            assertThat(after.content()).extracting(PaymentOrderView::requestor).containsExactly("P-3-2", null);
            assertThat(profiles.lookups()).hasSize(1);

            profiles.lookups().clear();
            var exported = new ArrayList<PaymentOrderView>();
            assertThat(executor.export(query.build(), ExportOptions.of(2), page -> page, exported::add)).isEqualTo(6);
            assertThat(exported).extracting(PaymentOrderView::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
            assertThat(exported).extracting(PaymentOrderView::payer).containsExactlyElementsOf(PAYERS);
            // Once per export batch of two: three batches, each a page of its own.
            assertThat(profiles.lookups()).hasSize(3);
            assertThat(profiles.lookups())
                    .allSatisfy(chunk -> assertThat(chunk).doesNotHaveDuplicates().doesNotContainNull());
        });
    }

    @TckTest
    void ac_fch_15_by_keys_runs_through_a_join_plan_and_a_child_plan(TckDatabase db) {
        var joinedNames = new ArrayList<List<String>>();
        FetchPlan<Patron> patron = FetchPlan.of(SelectSet.of(QPatron.ID))
                .enrich(Enricher.<Patron, String, String>byKeys(keys -> {
                    joinedNames.add(List.copyOf(keys));
                    return keys.stream().collect(Collectors.toMap(Function.identity(), name -> name + "/"));
                }).key(Patron::name, Patron::withTag).reading(QPatron.NAME));
        ModelQuery<OrderEntity, Long, OrderPatrons> withJoin = QOrderPatrons.query()
                .fetch(FetchPlan.of(SelectSet.of(QOrderPatrons.ID)).join(QOrderPatrons.CUSTOMER_JOIN, patron))
                .where(f -> f.in(QOrderPatrons.ID, List.of(1L, 2L, 3L))).orderBy(QOrderPatrons.ID.asc()).build();

        var childKeys = new ArrayList<List<ActorKey>>();
        Enricher<Line> lineProfiles = Enricher.<Line, ActorKey, String>byKeys(keys -> {
            childKeys.add(List.copyOf(keys));
            return keys.stream().collect(Collectors.toMap(key -> key,
                    key -> "P-" + key.userType() + "-" + key.userId()));
        }).key(line -> new ActorKey(1, line.id()), Line::withProfile).reading(QLine.ID);
        ModelQuery<OrderEntity, Long, OrderLines> withChild = QOrderLines.query()
                .fetch(FetchPlan.of(SelectSet.of(QOrderLines.ID)).child(QOrderLines.ITEMS,
                        FetchPlan.of(SelectSet.of(QLine.ID)).enrich(lineProfiles)))
                .where(f -> f.eq(QOrderLines.ID, 1L)).build();

        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor -> {
            // The join plan's enricher: one lookup for the page's distinct joined names, per join.
            assertThat(executor.list(withJoin, Limit.unlimited())).hasSize(3).allSatisfy(
                    o -> assertThat(o.customer()).get().extracting(Patron::tag).asString().endsWith("/"));
            assertThat(joinedNames).hasSize(1);
            assertThat(joinedNames.get(0)).hasSize(3);

            joinedNames.clear();
            assertThat(executor.page(withJoin, PageSpec.ofOffset(1, 1), CountMode.NO_COUNT).content()).hasSize(1);
            assertThat(joinedNames).hasSize(1);

            // The child plan's enricher: one lookup for the page's child keys, once per page.
            assertThat(executor.list(withChild, Limit.unlimited())).singleElement().satisfies(
                    o -> assertThat(o.items()).hasSize(4).allSatisfy(item -> assertThat(item.profile()).isNotNull()));
            assertThat(childKeys).hasSize(1);
            assertThat(childKeys.get(0)).hasSize(4);
        });
    }

    // ---- AC-FCH-16

    @TckTest
    void ac_fch_16_batch_size_chunks_the_keys_and_a_split_lookup_calls_each_source_once_per_chunk(TckDatabase db) {
        var profiles = new SplitProfiles();
        var query = paymentOrders(keysTo(actorKeys(profiles).batchSize(2)));

        withExecutor(JoinTestSupport.dataSource(db), PaymentOrderEntity.class, executor -> {
            assertThat(executor.list(query.build(), Limit.unlimited())).extracting(PaymentOrderView::payer)
                    .containsExactlyElementsOf(PAYERS);

            // ceil(6 / 2) = 3 calls of at most two keys, consecutive in first-seen order, no key in two.
            assertThat(profiles.lookups()).hasSize(3);
            assertThat(profiles.lookups().get(0)).containsExactly(new ActorKey(1, 2), new ActorKey(2, 1));
            assertThat(profiles.lookups().get(1)).containsExactly(new ActorKey(3, 2), new ActorKey(1, 1));
            assertThat(profiles.lookups().get(2)).containsExactly(new ActorKey(1, 3), new ActorKey(3, 1));
            // The lookup splits each chunk by type: one source call per type present, never two in one chunk.
            assertThat(profiles.callsByType().get(1)).hasSize(3);
            assertThat(profiles.callsByType().get(2)).hasSize(1);
            assertThat(profiles.callsByType().get(3)).hasSize(2);
        });
    }

    // ---- AC-FCH-17

    /**
     * The cache a plan built per call captures: the first enricher to need a key loads it, the next one reads it from
     * here, so the two enrichers of one run share one load per key (R-FCH-17).
     */
    private static Function<Set<ActorKey>, Map<ActorKey, String>> cached(SplitProfiles profiles,
            Map<ActorKey, String> cache, List<List<ActorKey>> loads) {
        return keys -> {
            List<ActorKey> missing = keys.stream().filter(key -> !cache.containsKey(key)).toList();
            if (!missing.isEmpty()) {
                loads.add(missing);
                cache.putAll(profiles.find(new LinkedHashSet<>(missing)));
            }
            var found = new LinkedHashMap<ActorKey, String>();
            for (ActorKey key : keys) {
                if (cache.containsKey(key)) {
                    found.put(key, cache.get(key));
                }
            }
            return found;
        };
    }

    @TckTest
    void ac_fch_17_a_child_enrichers_profile_is_reused_by_the_orders_enricher_and_the_run_adds_no_statement(
            TckDatabase db) {
        // The request-time parameter and the cache live in the caller's code, captured per call (R-FCH-17, INV-9).
        int requestedUserType = 1;
        var cache = new HashMap<ActorKey, String>();
        var childLoads = new ArrayList<List<ActorKey>>();
        var orderLoads = new ArrayList<List<ActorKey>>();
        var profiles = new SplitProfiles();

        FetchPlan<Line> items = FetchPlan.of(SelectSet.of(QLine.ID, QLine.QUANTITY))
                .enrich(Enricher.<Line, ActorKey, String>byKeys(cached(profiles, cache, childLoads))
                        .key(line -> new ActorKey(requestedUserType, line.id()), Line::withProfile).reading(QLine.ID));
        FetchPlan<OrderLines> order = FetchPlan.of(SelectSet.of(QOrderLines.ID))
                .child(QOrderLines.ITEMS, items)
                .enrich(Enricher.<OrderLines, ActorKey, String>byKeys(cached(profiles, cache, orderLoads))
                        .key(o -> new ActorKey(requestedUserType, o.id()), OrderLines::withProfile)
                        .reading(QOrderLines.ID));
        ModelQuery<OrderEntity, Long, OrderLines> base = QOrderLines.query().select(SelectSet.of(QOrderLines.ID))
                .where(f -> f.eq(QOrderLines.ID, 1L)).build();

        ModelQuery<OrderEntity, Long, OrderLines> perCall = base.withFetch(order);
        // The run is the root and its child, one statement each: the enrichers read the shared cache, not the
        // database, so applying the per-call plan adds no statement of its own (R-FCH-13, R-FCH-17).
        List<String> statements = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
            List<OrderLines> page = executor.list(perCall, Limit.unlimited());

            assertThat(page).singleElement().satisfies(o -> {
                // The child's enricher loaded the user (1, 1) for item 1; the outer enricher reuses it.
                assertThat(o.profile()).isEqualTo("P-1-1");
                assertThat(o.items()).extracting(Line::id).containsExactly(1L, 5001L, 10001L, 15001L);
                assertThat(o.items()).extracting(Line::profile).containsExactly("P-1-1", null, null, null);
            });
        }));
        assertThat(statements).hasSize(2);
        assertThat(childLoads).containsExactly(List.of(new ActorKey(1, 1), new ActorKey(1, 5001),
                new ActorKey(1, 10001), new ActorKey(1, 15001)));
        // The order's enricher found (1, 1) in the cache and called no source again.
        assertThat(orderLoads).isEmpty();
        assertThat(profiles.lookups()).hasSize(1);
    }
}
