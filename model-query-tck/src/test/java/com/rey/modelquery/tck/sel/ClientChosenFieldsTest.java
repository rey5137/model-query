package com.rey.modelquery.tck.sel;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/**
 * Client-chosen field names resolved through the generated {@code fields()} into a query (spec api/10 R-COL-23,
 * R-COL-24, processor/31 R-GEN-32). It runs on H2 alone: the names decide the selection, not a vendor's SQL.
 */
class ClientChosenFieldsTest {

    private static final TckDatabase DB = TckDatabases.get(TckTarget.h2());

    /** What an application exposes: part of the model, narrowed once at startup. */
    private static final FieldIndex<SelOrder> API_FIELDS =
            QSelOrder.fields().only(List.of("id", "status", "referrer", "referrer.name", "referrerChild"));

    private record Outcome(List<SelOrder> rows, String sql) {}

    private static Outcome run(List<String> requested) {
        var resolved = API_FIELDS.resolve(requested);
        SelectSet<SelOrder> select = resolved.select().isEmpty() ? QSelOrder.DEFAULT : resolved.select();
        var query = QSelOrder.query().select(select).orderBy(QSelOrder.ID.asc()).build();
        List<SelOrder> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(DB, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor
                        .create(em, OrderEntity.class, ModelQueryConfig.defaults()).list(query, Limit.of(5))));
            }
        });
        assertThat(sql).hasSize(1);
        return new Outcome(rows, sql.get(0));
    }

    @Test
    void ac_col_27_names_select_only_the_fields_they_name_and_no_join_when_none_is_needed() {
        Outcome outcome = run(List.of("id", "status"));

        assertThat(outcome.sql()).doesNotContain(" join ");
        assertThat(outcome.rows()).isNotEmpty().allSatisfy(order -> {
            assertThat(order.id()).isNotNull();
            assertThat(order.status()).isNotNull();
            assertThat(order.total()).isNull();
            assertThat(order.selected()).isEqualTo(SelectSet.of(QSelOrder.ID, QSelOrder.STATUS));
            assertThat(order.selected().contains(QSelOrder.TOTAL)).isFalse();
        });
    }

    @Test
    void ac_col_27_a_joined_name_adds_the_join_and_fills_exactly_the_selected_columns() {
        Outcome outcome = run(List.of("id", "referrer.name"));

        assertThat(outcome.sql().split(" join ", -1)).hasSize(2);
        assertThat(outcome.rows()).anySatisfy(order -> assertThat(order.referrer()).isPresent().get()
                .satisfies(customer -> {
                    assertThat(customer.name()).isNotNull();
                    assertThat(customer.country()).isNull();
                    assertThat(customer.selected().contains(QSelCustomer.NAME)).isTrue();
                    assertThat(customer.selected().contains(QSelCustomer.COUNTRY)).isFalse();
                }));
        assertThat(outcome.rows()).allSatisfy(order -> {
            assertThat(order.status()).isNull();
            assertThat(order.selected().contains(QSelOrder.STATUS)).isFalse();
        });
    }

    @Test
    void ac_col_27_an_unknown_name_is_reported_and_the_known_ones_still_resolve() {
        var resolved = API_FIELDS.resolve(List.of("id", "nope", "total", "nope"));

        // 'total' exists on the model but not in the application's whitelist
        assertThat(resolved.unknown()).containsExactly("nope", "total");
        assertThat(resolved.select()).isEqualTo(SelectSet.of(QSelOrder.ID));
    }

    @Test
    void ac_col_27_only_a_child_name_leaves_the_select_empty_so_the_query_falls_back_to_default() {
        var resolved = API_FIELDS.resolve(List.of("referrerChild"));

        assertThat(resolved.select().isEmpty()).isTrue();
        assertThat(resolved.children()).containsExactly(QSelOrder.REFERRER_CHILD);
        Outcome outcome = run(List.of("referrerChild"));
        assertThat(outcome.rows()).isNotEmpty().allSatisfy(order -> {
            assertThat(order.selected()).isEqualTo(QSelOrder.DEFAULT);
            assertThat(order.status()).isNotNull();
        });
    }
}
