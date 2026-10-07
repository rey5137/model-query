package com.rey.modelquery.tck.sel;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/**
 * The generated {@code @Selected} set against a database (spec processor/31 R-GEN-29 to R-GEN-31, D-120). It runs
 * on H2 alone: what the set holds is decided by the row's selection, not by a vendor's SQL.
 */
class SelectedFieldsTest {

    private static final TckDatabase DB = TckDatabases.get(TckTarget.h2());

    @Test
    void ac_gen_17_a_partial_select_leaves_the_unselected_column_out_and_keeps_a_selected_null_one() {
        var query = QSelOrder.query().select(SelectSet.of(QSelOrder.ID, QSelOrder.REFERRER_KEY))
                .orderBy(QSelOrder.ID.asc()).build();
        List<SelOrder> orders = list(query, 4);

        assertThat(orders).hasSize(4);
        // Orders 1 and 2 have no referrer: the key is selected and NULL, the status was never selected.
        assertThat(orders.get(0).referrerKey()).isNull();
        assertThat(orders.get(0).status()).isNull();
        assertThat(orders).allSatisfy(order -> {
            assertThat(order.selected().contains(QSelOrder.ID)).isTrue();
            assertThat(order.selected().contains(QSelOrder.REFERRER_KEY)).isTrue();
            assertThat(order.selected().contains(QSelOrder.STATUS)).isFalse();
            assertThat(order.selected().contains(QSelOrder.TOTAL)).isFalse();
            assertThat(order.selected()).isEqualTo(SelectSet.of(QSelOrder.REFERRER_KEY, QSelOrder.ID));
            // One instance for the whole query
            assertThat(order.selected()).isSameAs(orders.get(0).selected());
        });
        assertThat(orders.get(2).referrerKey()).isNotNull();
    }

    @Test
    void ac_gen_17_an_engine_added_key_is_in_the_set_though_the_caller_did_not_select_it() {
        var query = QSelOrder.query().select(SelectSet.of(QSelOrder.STATUS)).orderBy(QSelOrder.ID.asc()).build();
        SelOrder first = list(query, 1).get(0);

        // The primary key reads the page's cursor and the ordering, so the engine selects it and the row holds it.
        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.selected().contains(QSelOrder.ID)).isTrue();
        assertThat(first.selected().contains(QSelOrder.STATUS)).isTrue();
        assertThat(first.selected().contains(QSelOrder.TOTAL)).isFalse();
    }

    @Test
    void ac_gen_17_a_join_presence_key_is_the_generated_joined_constant_and_its_miss_builds_no_child() {
        var query = QSelOrder.query().select(SelectSet.of(QSelOrder.ID).with(SelectSet.of(QSelOrder.REFERRER_NAME)))
                .orderBy(QSelOrder.ID.asc()).build();
        List<SelOrder> orders = list(query, 3);

        SelOrder miss = orders.get(0);
        SelOrder hit = orders.get(2);
        // Only the name was selected: the engine adds the join's key, which is the joined constant of the model.
        assertThat(hit.selected().contains(QSelOrder.REFERRER_ID)).isTrue();
        assertThat(hit.selected().contains(QSelOrder.REFERRER_NAME)).isTrue();
        assertThat(hit.selected().contains(QSelOrder.REFERRER_COUNTRY)).isFalse();
        assertThat(hit.referrer()).isPresent().get().satisfies(customer -> {
            assertThat(customer.id()).isNotNull();
            assertThat(customer.selected().contains(QSelCustomer.ID)).isTrue();
            assertThat(customer.selected().contains(QSelCustomer.NAME)).isTrue();
            assertThat(customer.selected().contains(QSelCustomer.COUNTRY)).isFalse();
            assertThat(customer.country()).isNull();
        });
        // A LEFT-join miss builds no child, so nothing was filled for it; the parent's own set still is
        assertThat(miss.referrer()).isEmpty();
        assertThat(miss.selected()).isNotNull();
        assertThat(miss.selected().contains(QSelOrder.ID)).isTrue();
    }

    @Test
    void ac_gen_17_a_to_one_child_loaded_by_a_fetch_plan_fills_its_own_set_and_an_unmatched_one_stays_empty() {
        var plan = FetchPlan.of(SelectSet.of(QSelOrder.ID, QSelOrder.REFERRER_KEY))
                .child(QSelOrder.REFERRER_CHILD, FetchPlan.of(SelectSet.of(QSelCustomer.NAME)));
        var query = QSelOrder.query().fetch(plan).orderBy(QSelOrder.ID.asc()).build();
        List<SelOrder> orders = list(query, 3);

        assertThat(orders.get(0).referrerChild()).isEmpty();
        assertThat(orders.get(2).referrerChild()).isPresent().get().satisfies(customer -> {
            assertThat(customer.name()).isNotNull();
            assertThat(customer.selected().contains(QSelCustomer.NAME)).isTrue();
            assertThat(customer.selected().contains(QSelCustomer.COUNTRY)).isFalse();
        });
    }

    @Test
    void ac_gen_18_a_grouped_query_fills_the_group_keys_and_the_aggregates_it_selected() {
        var keysOnly = QSelStatusTotals.query().select(QSelStatusTotals.GROUP_KEYS)
                .orderBy(QSelStatusTotals.STATUS.asc()).build();
        var both = QSelStatusTotals.query().select(QSelStatusTotals.GROUP_KEYS.with(QSelStatusTotals.ORDERS))
                .orderBy(QSelStatusTotals.STATUS.asc()).build();
        List<SelStatusTotals> keys = new ArrayList<>();
        List<SelStatusTotals> totals = new ArrayList<>();
        withExecutor(executor -> {
            keys.addAll(executor.list(keysOnly, Limit.unlimited()));
            totals.addAll(executor.list(both, Limit.unlimited()));
        });

        assertThat(keys).isNotEmpty().allSatisfy(row -> {
            assertThat(row.selected().contains(QSelStatusTotals.STATUS)).isTrue();
            assertThat(row.selected().contains(QSelStatusTotals.ORDERS)).isFalse();
            assertThat(row.orders()).isNull();
        });
        assertThat(totals).hasSameSizeAs(keys).allSatisfy(row -> {
            assertThat(row.selected().contains(QSelStatusTotals.STATUS)).isTrue();
            assertThat(row.selected().contains(QSelStatusTotals.ORDERS)).isTrue();
            assertThat(row.orders()).isPositive();
        });
    }

    @Test
    void ac_gen_18_a_keyset_export_ordered_by_a_filter_only_column_leaves_that_column_out_of_the_set() {
        var query = QSelOrder.query().select(SelectSet.of(QSelOrder.STATUS)).keyset()
                .orderBy(QSelOrder.PLACED.asc()).build();
        List<SelOrder> exported = new ArrayList<>();
        long[] count = new long[1];
        withExecutor(executor -> count[0] = executor.export(query, ExportOptions.of(7), page -> page, exported::add));

        assertThat(count[0]).isEqualTo(TckFixture.ORDERS);
        assertThat(exported).hasSize(TckFixture.ORDERS).allSatisfy(order -> {
            // The ordering column and the key are selected, but no field of the model holds the ordering column.
            assertThat(order.selected().contains(QSelOrder.PLACED)).isFalse();
            assertThat(order.selected().contains(QSelOrder.ID)).isTrue();
            assertThat(order.selected().contains(QSelOrder.STATUS)).isTrue();
        });
    }

    @Test
    void ac_gen_18_with_fetch_keeps_the_set_and_an_enrichers_column_counts_as_selected() {
        ModelQuery<OrderEntity, Long, SelOrder> base = QSelOrder.query().select(SelectSet.of(QSelOrder.STATUS))
                .orderBy(QSelOrder.ID.asc()).build();
        var plan = FetchPlan.of(SelectSet.of(QSelOrder.ID, QSelOrder.STATUS))
                .enrich(Enricher.of(UnaryOperator.identity(), QSelOrder.TOTAL));
        List<SelOrder> orders = list(base.withFetch(plan), 2);

        assertThat(orders).hasSize(2).allSatisfy(order -> {
            assertThat(order.selected().contains(QSelOrder.ID)).isTrue();
            assertThat(order.selected().contains(QSelOrder.STATUS)).isTrue();
            assertThat(order.selected().contains(QSelOrder.TOTAL)).isTrue();
            assertThat(order.selected().contains(QSelOrder.REFERRER_KEY)).isFalse();
            assertThat(order.total()).isNotNull();
        });
    }

    @Test
    void ac_gen_19_a_record_finisher_that_rebuilds_the_record_carries_the_set_over() {
        var query = QSelOrder.query().select(SelectSet.of(QSelOrder.ID, QSelOrder.STATUS))
                .finisher(order -> order.withStatus("seen")).orderBy(QSelOrder.ID.asc()).build();
        SelOrder first = list(query, 1).get(0);

        assertThat(first.status()).isEqualTo("seen");
        assertThat(first.selected()).isEqualTo(SelectSet.of(QSelOrder.ID, QSelOrder.STATUS));
    }

    private static List<SelOrder> list(ModelQuery<OrderEntity, Long, SelOrder> query, int limit) {
        List<SelOrder> rows = new ArrayList<>();
        withExecutor(executor -> rows.addAll(executor.list(query, Limit.of(limit))));
        return rows;
    }

    private static void withExecutor(Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(JoinTestSupport.dataSource(DB))) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }
}
