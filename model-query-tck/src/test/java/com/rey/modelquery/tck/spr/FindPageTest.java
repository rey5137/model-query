package com.rey.modelquery.tck.spr;

import static com.rey.modelquery.tck.spr.ModelQueryRepositoryTest.assertSameAsExecutor;
import static com.rey.modelquery.tck.spr.ModelQueryRepositoryTest.withRepository;
import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.SortSpec;
import com.rey.modelquery.core.SortSpec.Key;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spring.data.ModelPage;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;

/** {@code ModelQueryRepository.findPage} over the plain-JPA executor's {@code page} (integration/50 §2). */
class FindPageTest {

    record BuyerRow(Long id, String buyer, Long referrerId) {}

    record OrderRow(Long id, String status, BigDecimal total) {}

    record TwoNames(Long id, String first, String second) {}

    /** What a page holds, whether it came from the executor's {@code Slice} or the repository's {@code ModelPage}. */
    record Paged(List<?> content, int number, int size, boolean hasNext, Long total) {

        static Paged of(Slice<?> slice) {
            return new Paged(slice.content(), slice.pageNumber(), slice.pageSize(), slice.hasNext(),
                    slice.total().isPresent() ? slice.total().getAsLong() : null);
        }

        static Paged of(ModelPage<?> page) {
            return new Paged(page.getContent(), page.getNumber(), page.getSize(), page.hasNext(),
                    page.getTotalElements());
        }
    }

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> BUYER =
            TableField.<OrderEntity, CustomerEntity>join(ORDERS, "customer", INNER).named("buyer");

    // A joined column whose property path (buyer.name) differs from its attribute path (customer.name), and a
    // nullable root column with no property, which a sort names by attribute path only (D-55).
    private static final ColumnField<BuyerRow, OrderEntity, Long> BUYER_ROW_ID =
            ColumnField.of(BuyerRow.class, ORDERS, "id", Long.class).named("id");
    private static final ColumnField<BuyerRow, CustomerEntity, String> BUYER_NAME =
            ColumnField.of(BuyerRow.class, BUYER, "name", String.class).named("name");
    private static final ColumnField<BuyerRow, OrderEntity, Long> REFERRER_ID =
            ColumnField.of(BuyerRow.class, ORDERS, "referrerId", Long.class);

    private static final ModelQuery<OrderEntity, ?, BuyerRow> BUYER_ROWS = ModelQuery
            .builder(ORDERS, row -> new BuyerRow(row.get(BUYER_ROW_ID), row.get(BUYER_NAME), row.get(REFERRER_ID)))
            .columns(ColumnSet.of(BUYER_ROW_ID, BUYER_NAME, REFERRER_ID))
            .primaryKey(PrimaryKey.of(BUYER_ROW_ID))
            .orderBy(BUYER_ROW_ID.asc())
            .build();

    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);

    /** A quarter of the orders, ordered by id descending, so an unsorted page is told apart from an ascending one. */
    private static final ModelQuery<OrderEntity, ?, OrderRow> NEW_ORDERS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
            .columns(ColumnSet.of(ID, STATUS, TOTAL))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.desc())
            .where(f -> f.eq(STATUS, Optional.of("NEW")))
            .build();

    private static final long NEW = TckFixture.ORDERS / 4;

    // ---- AC-SPR-04

    @TckTest
    void ac_spr_04_a_joined_column_sorts_by_its_property_path_and_by_its_attribute_path(TckDatabase db) {
        var byProperty = SortSpec.of(Key.desc("buyer.name"));
        Paged viaProperty = assertSameAsExecutor(db,
                executor -> Paged.of(executor.page(BUYER_ROWS.orderedBy(byProperty), PageSpec.of(2, 50),
                        CountMode.COUNT)),
                repository -> Paged.of(repository.findPage(BUYER_ROWS,
                        PageRequest.of(2, 50, Sort.by(Order.desc("buyer.name"))), CountMode.COUNT)));
        Paged viaAttribute = assertSameAsExecutor(db,
                executor -> Paged.of(executor.page(BUYER_ROWS.orderedBy(byProperty), PageSpec.of(2, 50),
                        CountMode.COUNT)),
                repository -> Paged.of(repository.findPage(BUYER_ROWS,
                        PageRequest.of(2, 50, Sort.by(Order.desc("customer.name"))), CountMode.COUNT)));

        assertThat(viaAttribute).isEqualTo(viaProperty);
        assertThat(viaProperty.content()).hasSize(50).map(BuyerRow.class::cast)
                .isSortedAccordingTo(Comparator.comparing(BuyerRow::buyer).reversed()
                        .thenComparing(BuyerRow::id));
    }

    @TckTest
    void ac_spr_04_nulls_first_last_and_native_map_to_first_last_and_default(TckDatabase db) {
        List<List<?>> contents = new ArrayList<>();
        for (var mapping : List.of(
                new Object[] {Order.asc("referrerId").nullsFirst(), NullPrecedence.FIRST},
                new Object[] {Order.asc("referrerId").nullsLast(), NullPrecedence.LAST},
                new Object[] {Order.asc("referrerId").nullsNative(), NullPrecedence.DEFAULT})) {
            var order = (Order) mapping[0];
            var spec = SortSpec.of(Key.asc("referrerId").nulls((NullPrecedence) mapping[1]));
            contents.add(assertSameAsExecutor(db,
                    executor -> Paged.of(executor.page(BUYER_ROWS.orderedBy(spec), PageSpec.of(0, 50),
                            CountMode.NO_COUNT)),
                    repository -> Paged.of(repository.findPage(BUYER_ROWS, PageRequest.of(0, 50, Sort.by(order)),
                            CountMode.NO_COUNT))).content());
        }

        // Two orders in three have no referrer, so a page of 50 is all NULL keys first, and none last.
        assertThat(contents.get(0)).hasSize(50).map(BuyerRow.class::cast)
                .extracting(BuyerRow::referrerId).containsOnlyNulls();
        assertThat(contents.get(1)).hasSize(50).map(BuyerRow.class::cast)
                .extracting(BuyerRow::referrerId).doesNotContainNull();
    }

    // ---- AC-SPR-05

    @TckTest
    void ac_spr_05_an_unknown_sort_property_throws_mq2301_naming_it(TckDatabase db) {
        assertRefusedBeforeAnyQuery(db, BUYER_ROWS, PageRequest.of(0, 10, Sort.by("nope")), MqCode.MQ2301, "'nope'");
    }

    @TckTest
    void ac_spr_05_an_ambiguous_sort_property_throws_mq2301_naming_it(TckDatabase db) {
        // Two unnamed joins of one attribute: both columns have the attribute path customer.name.
        var first = ColumnField.of(TwoNames.class, TableField.join(ORDERS, "customer", INNER), "name", String.class);
        var second = ColumnField.of(TwoNames.class, TableField.join(ORDERS, "customer", INNER).as("other"), "name",
                String.class);
        var id = ColumnField.of(TwoNames.class, ORDERS, "id", Long.class);
        var twoNames = ModelQuery.builder(ORDERS, row -> new TwoNames(row.get(id), row.get(first), row.get(second)))
                .columns(ColumnSet.of(id, first, second))
                .primaryKey(PrimaryKey.of(id))
                .build();

        assertRefusedBeforeAnyQuery(db, twoNames, PageRequest.of(0, 10, Sort.by("customer.name")), MqCode.MQ2301,
                "'customer.name' names more than one selected column");
    }

    @TckTest
    void ac_spr_05_an_ignore_case_order_throws_mq2301_naming_its_property(TckDatabase db) {
        assertRefusedBeforeAnyQuery(db, BUYER_ROWS,
                PageRequest.of(0, 10, Sort.by(Order.asc("buyer.name").ignoreCase())), MqCode.MQ2301,
                "'buyer.name' asks ignoreCase()");
    }

    // ---- AC-SPR-06

    @TckTest
    void ac_spr_06_no_count_leaves_both_totals_null_and_reports_has_next_exactly(TckDatabase db) {
        // 1250 NEW orders in pages of 250: the fifth page is full and still the last.
        Paged middle = assertSameAsExecutor(db,
                executor -> Paged.of(executor.page(NEW_ORDERS, PageSpec.of(3, 250), CountMode.NO_COUNT)),
                repository -> Paged.of(repository.findPage(NEW_ORDERS, PageRequest.of(3, 250),
                        CountMode.NO_COUNT)));
        Paged last = assertSameAsExecutor(db,
                executor -> Paged.of(executor.page(NEW_ORDERS, PageSpec.of(4, 250), CountMode.NO_COUNT)),
                repository -> Paged.of(repository.findPage(NEW_ORDERS, PageRequest.of(4, 250),
                        CountMode.NO_COUNT)));
        ModelPage<OrderRow> page = withRepository(dataSource(db), ModelQueryRepositoryTest.DefaultTransactions.class,
                (repository, context) -> repository.findPage(NEW_ORDERS, PageRequest.of(4, 250),
                        CountMode.NO_COUNT));

        assertThat(middle.hasNext()).isTrue();
        assertThat(last.content()).hasSize(250);
        assertThat(last.hasNext()).isFalse();
        assertThat(page.getTotalElements()).isNull();
        assertThat(page.getTotalPages()).isNull();
        assertThat(page.isLast()).isTrue();
    }

    @TckTest
    void ac_spr_06_no_count_pages_followed_by_has_next_visit_every_row_once(TckDatabase db) {
        List<Long> visited = withRepository(dataSource(db), ModelQueryRepositoryTest.DefaultTransactions.class,
                (repository, context) -> {
                    List<Long> ids = new ArrayList<>();
                    Pageable pageable = PageRequest.of(0, 125);
                    ModelPage<OrderRow> page;
                    do {
                        page = repository.findPage(NEW_ORDERS, pageable, CountMode.NO_COUNT);
                        page.forEach(row -> ids.add(row.id()));
                        pageable = page.nextPageable();
                    } while (page.hasNext());
                    return ids;
                });

        // NEW is the first of the fixture's four statuses, given to order i when i % 4 == 0 (ids 1..ORDERS).
        List<Long> newIds = LongStream.rangeClosed(1, TckFixture.ORDERS).filter(i -> i % 4 == 0).boxed().toList();
        assertThat(visited).hasSize((int) NEW).containsExactlyInAnyOrderElementsOf(newIds);
    }

    @TckTest
    void ac_spr_06_count_reports_the_exact_totals(TckDatabase db) {
        Paged second = assertSameAsExecutor(db,
                executor -> Paged.of(executor.page(NEW_ORDERS, PageSpec.of(1, 300), CountMode.COUNT)),
                repository -> Paged.of(repository.findPage(NEW_ORDERS, PageRequest.of(1, 300), CountMode.COUNT)));
        ModelPage<OrderRow> last = withRepository(dataSource(db), ModelQueryRepositoryTest.DefaultTransactions.class,
                (repository, context) -> repository.findPage(NEW_ORDERS, PageRequest.of(4, 300), CountMode.COUNT));

        assertThat(second.total()).isEqualTo(NEW);
        assertThat(second.hasNext()).isTrue();
        assertThat(last.getTotalElements()).isEqualTo(NEW);
        assertThat(last.getTotalPages()).isEqualTo(5);
        assertThat(last.getContent()).hasSize(50);
        assertThat(last.hasNext()).isFalse();
        // A converted page keeps the totals.
        ModelPage<Long> ids = last.map(OrderRow::id);
        assertThat(ids.getTotalElements()).isEqualTo(NEW);
        assertThat(ids.getTotalPages()).isEqualTo(5);
        assertThat(ids.getContent()).isEqualTo(last.getContent().stream().map(OrderRow::id).toList());
        // A converted page with the same content and totals is equal; a plain slice is not, either way round.
        assertThat(last.map(row -> row)).isEqualTo(last).hasSameHashCodeAs(last);
        var slice = new SliceImpl<>(last.getContent(), last.getPageable(), last.hasNext());
        assertThat(slice).isNotEqualTo(last);
        assertThat(last).isNotEqualTo(slice);
    }

    @TckTest
    void ac_spr_06_only_count_reports_the_exact_totals_and_no_content(TckDatabase db) {
        Paged counted = assertSameAsExecutor(db,
                executor -> Paged.of(executor.page(NEW_ORDERS, PageSpec.of(0, 300), CountMode.ONLY_COUNT)),
                repository -> Paged.of(repository.findPage(NEW_ORDERS, PageRequest.of(0, 300),
                        CountMode.ONLY_COUNT)));
        ModelPage<OrderRow> page = withRepository(dataSource(db), ModelQueryRepositoryTest.DefaultTransactions.class,
                (repository, context) -> repository.findPage(NEW_ORDERS, PageRequest.of(0, 300),
                        CountMode.ONLY_COUNT));

        assertThat(counted.content()).isEmpty();
        assertThat(counted.hasNext()).isTrue();
        assertThat(page.getTotalElements()).isEqualTo(NEW);
        assertThat(page.getTotalPages()).isEqualTo(5);
    }

    @TckTest
    void ac_spr_06_an_unpaged_pageable_throws_mq2001(TckDatabase db) {
        assertRefusedBeforeAnyQuery(db, NEW_ORDERS, Pageable.unpaged(), MqCode.MQ2001, "Pageable.unpaged()");
    }

    // ---- AC-SPR-01, for findPage

    @TckTest
    void ac_spr_01_an_unsorted_pageable_keeps_the_definitions_order(TckDatabase db) {
        Paged page = assertSameAsExecutor(db,
                executor -> Paged.of(executor.page(NEW_ORDERS, PageSpec.of(1, 20), CountMode.NO_COUNT)),
                repository -> Paged.of(repository.findPage(NEW_ORDERS, PageRequest.of(1, 20),
                        CountMode.NO_COUNT)));

        assertThat(page.content()).hasSize(20).map(OrderRow.class::cast)
                .isSortedAccordingTo(Comparator.comparing(OrderRow::id).reversed());
    }

    @TckTest
    void ac_spr_01_a_sorted_pageable_replaces_the_definitions_order(TckDatabase db) {
        Paged page = assertSameAsExecutor(db,
                executor -> Paged.of(executor.page(NEW_ORDERS.orderedBy(SortSpec.of(Key.asc("total"))),
                        PageSpec.of(1, 20), CountMode.COUNT)),
                repository -> Paged.of(repository.findPage(NEW_ORDERS, PageRequest.of(1, 20, Sort.by("total")),
                        CountMode.COUNT)));

        assertThat(page.content()).hasSize(20).map(OrderRow.class::cast)
                .isSortedAccordingTo(Comparator.comparing(OrderRow::total).thenComparing(OrderRow::id));
    }

    // ---- support

    private static DataSource dataSource(TckDatabase db) {
        return JoinTestSupport.dataSource(db);
    }

    /** Asserts {@code findPage} throws {@code code} with {@code detail} in its message and runs no SQL at all. */
    private static <M> void assertRefusedBeforeAnyQuery(TckDatabase db, ModelQuery<OrderEntity, ?, M> q,
            Pageable pageable, MqCode code, String detail) {
        List<String> sql = SqlSnapshots.capture(db, ds -> withRepository(ds,
                ModelQueryRepositoryTest.DefaultTransactions.class, (repository, context) -> {
                    assertThatThrownBy(() -> repository.findPage(q, pageable, CountMode.COUNT))
                            .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                    e -> assertThat(e.code()).isEqualTo(code))
                            .hasMessageContaining(detail);
                    return null;
                }));

        assertThat(sql).isEmpty();
    }
}
