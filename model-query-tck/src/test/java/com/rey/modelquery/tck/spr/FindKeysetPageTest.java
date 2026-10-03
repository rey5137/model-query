package com.rey.modelquery.tck.spr;

import static com.rey.modelquery.tck.spr.ModelQueryRepositoryTest.assertSameAsExecutor;
import static com.rey.modelquery.tck.spr.ModelQueryRepositoryTest.withRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.data.domain.Sort;

/**
 * {@code ModelQueryRepository.findKeysetPage} over the plain-JPA executor's keyset {@code page} (integration/50 §1).
 */
class FindKeysetPageTest {

    record OrderRow(Long id, String status, BigDecimal total) {}

    /** What a keyset page holds and the cursors that reach its neighbours, from the executor's slice. */
    record KeysetPage(List<OrderRow> content, String next, String previous) {

        static KeysetPage of(KeysetSlice<OrderRow> slice) {
            return new KeysetPage(slice.content(), slice.nextCursor().orElse(null),
                    slice.previousCursor().orElse(null));
        }
    }

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);

    /** A keyset query whose definition orders by id ascending, for {@code Sort.unsorted()} to keep. */
    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
            .select(SelectSet.of(ID, STATUS, TOTAL))
            .primaryKey(PrimaryKey.of(ID))
            .keyset();

    private static final ModelQuery<OrderEntity, ?, OrderRow> BY_ID = ROWS.orderBy(ID.asc()).build();

    // ---- AC-SPR-14

    @TckTest
    void ac_spr_14_a_first_page_and_the_page_after_its_cursor_match_the_executor(TckDatabase db) {
        KeysetPage first = assertSameAsExecutor(db,
                executor -> KeysetPage.of(executor.page(BY_ID, KeysetSpec.first(60))),
                repository -> KeysetPage.of(repository.findKeysetPage(BY_ID, KeysetSpec.first(60), Sort.unsorted())));

        assertThat(first.content()).hasSize(60);
        assertThat(first.previous()).isNull();
        assertThat(first.next()).isNotNull();

        String cursor = first.next();
        KeysetPage second = assertSameAsExecutor(db,
                executor -> KeysetPage.of(executor.page(BY_ID, KeysetSpec.after(cursor, 60))),
                repository -> KeysetPage.of(
                        repository.findKeysetPage(BY_ID, KeysetSpec.after(cursor, 60), Sort.unsorted())));

        assertThat(second.content()).hasSize(60);
        assertThat(second.previous()).isNotNull();
        // The definition's id ascending order continues past the first page: no overlap and no gap.
        List<Long> firstIds = first.content().stream().map(OrderRow::id).toList();
        assertThat(second.content()).extracting(OrderRow::id).doesNotContainAnyElementsOf(firstIds);
        assertThat(second.content().get(0).id()).isEqualTo(firstIds.get(firstIds.size() - 1) + 1);
    }

    @TckTest
    void ac_spr_14_a_sorted_sort_replaces_the_order_and_changes_the_cursors_fingerprint(TckDatabase db) {
        KeysetPage unsorted = withRepository(dataSource(db), ModelQueryRepositoryTest.DefaultTransactions.class,
                (repository, context) -> KeysetPage.of(
                        repository.findKeysetPage(BY_ID, KeysetSpec.first(50), Sort.unsorted())));
        KeysetPage sorted = assertSameAsExecutor(db,
                executor -> KeysetPage.of(executor.page(ROWS.orderBy(STATUS.asc()).build(), KeysetSpec.first(50))),
                repository -> KeysetPage.of(
                        repository.findKeysetPage(BY_ID, KeysetSpec.first(50), Sort.by("status"))));

        assertThat(sorted.content()).hasSize(50);
        assertThat(sorted.content()).isSortedAccordingTo(
                Comparator.comparing(OrderRow::status).thenComparing(OrderRow::id));
        assertThat(sorted.next()).isNotEqualTo(unsorted.next());

        // The unsorted order's cursor is not understood with the sorted Sort: MQ2209, before any query.
        assertKeysetRefusedBeforeAnyQuery(db, BY_ID, KeysetSpec.after(unsorted.next(), 50), Sort.by("status"),
                MqCode.MQ2209, "another order");
    }

    @TckTest
    void ac_spr_14_a_query_without_keyset_throws_mq2207(TckDatabase db) {
        var noKeyset = ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
                .select(SelectSet.of(ID, STATUS, TOTAL))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(ID.asc())
                .build();

        assertKeysetRefusedBeforeAnyQuery(db, noKeyset, KeysetSpec.first(10), Sort.unsorted(), MqCode.MQ2207,
                "keyset()");
    }

    // ---- support

    private static DataSource dataSource(TckDatabase db) {
        return JoinTestSupport.dataSource(db);
    }

    /** Asserts {@code findKeysetPage} throws {@code code} with {@code detail} in its message and runs no SQL at all. */
    private static <M> void assertKeysetRefusedBeforeAnyQuery(TckDatabase db, ModelQuery<OrderEntity, ?, M> q,
            KeysetSpec keyset, Sort sort, MqCode code, String detail) {
        List<String> sql = SqlSnapshots.capture(db, ds -> withRepository(ds,
                ModelQueryRepositoryTest.DefaultTransactions.class, (repository, context) -> {
                    assertThatThrownBy(() -> repository.findKeysetPage(q, keyset, sort))
                            .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                    e -> assertThat(e.code()).isEqualTo(code))
                            .hasMessageContaining(detail);
                    return null;
                }));

        assertThat(sql).isEmpty();
    }
}
