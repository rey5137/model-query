package com.rey.modelquery.tck.exe;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerNoteEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.fch.FetchTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** {@code byKeys} on every vendor, and the {@code one} statements without an {@code ORDER BY} (engine/20 R-EXE-13). */
class ByKeysTest {

    record OrderRow(Long id, String status, BigDecimal total) {}

    record NoteRow(Long id, String email) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);

    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<OrderRow, OrderItemEntity, String> ITEM_PRODUCT_FILTER =
            ColumnField.of(OrderRow.class, ITEMS, "productCode", String.class);

    private static final TableField<CustomerNoteEntity, CustomerNoteEntity> NOTES =
            TableField.root(CustomerNoteEntity.class);
    private static final ColumnField<NoteRow, CustomerNoteEntity, Long> NOTE_ID =
            ColumnField.of(NoteRow.class, NOTES, "id", Long.class);
    private static final ColumnField<NoteRow, CustomerNoteEntity, String> NOTE_EMAIL =
            ColumnField.of(NoteRow.class, NOTES, "customerEmail", String.class);

    private static ModelQuery.Builder<OrderEntity, Long, OrderRow> orders() {
        return ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
                .select(SelectSet.of(ID, STATUS, TOTAL))
                .primaryKey(PrimaryKey.of(ID));
    }

    // ---- AC-EXE-14

    @TckTest
    void ac_exe_14_by_keys_reads_the_keys_in_one_statement_in_first_occurrence_order(TckDatabase db) {
        var q = orders().orderBy(TOTAL.desc()).build();
        List<Map<Long, OrderRow>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-14-by-keys", ds -> withExecutor(ds,
                executor -> results.add(executor.byKeys(q, Arrays.asList(7L, -1L, 3L, 7L, 5L)))));
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).doesNotContainIgnoringCase("order by");
        assertThat(results).singleElement().satisfies(map -> assertThat(map.keySet()).containsExactly(7L, 3L, 5L));
    }

    @TckTest
    void ac_exe_14_by_keys_chunks_over_the_vendor_limit_whatever_primary_key_first_batch_size_is(TckDatabase db) {
        List<Integer> enriched = new ArrayList<>();
        var q = orders().fetch(FetchPlan.of(SelectSet.of(ID, STATUS, TOTAL)).enrich(Enricher.of(page -> {
            enriched.add(page.size());
            return page;
        }))).build();
        List<Long> keys = List.of(9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L, -1L);
        for (int batch : new int[] {0, 1, 1_000}) {
            enriched.clear();
            var config = ModelQueryConfig.defaults().vendorProfiles(List.of(FetchTestSupport.limited(db, 4)));
            if (batch > 0) {
                config = config.primaryKeyFirstBatchSize(batch);
            }
            ModelQueryConfig used = config;
            List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, used, executor ->
                    assertThat(executor.byKeys(q, keys).keySet()).containsExactly(9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L)));
            // Ten distinct keys in IN lists of at most 4 values are three statements, and one fetch-plan run.
            assertThat(sql).hasSize(3);
            assertThat(enriched).containsExactly(9);
        }
    }

    @TckTest
    void ac_exe_14_a_key_matching_two_rows_throws_mq2003_naming_the_filter_only_join(TckDatabase db) {
        // Order 1 holds four items of product P001, so the filter's join repeats it four times.
        var q = orders().where(f -> f.eq(ITEM_PRODUCT_FILTER, "P001")).build();
        withExecutor(db, ModelQueryConfig.defaults(), executor -> assertMq(() -> executor.byKeys(q, List.of(1L)),
                MqCode.MQ2003, "OrderRow: byKeys(query, keys) found more than one row for key 1; if the to-many join "
                        + "OrderEntity.items, which no selected column reads, repeats the row once per matching "
                        + "child, filter with Filters.exists(...) instead"));
    }

    @TckTest
    void ac_exe_14_a_case_insensitive_string_key_throws_mq2005_naming_the_value(TckDatabase db) {
        var notes = ModelQuery.builder(NOTES, row -> new NoteRow(row.get(NOTE_ID), row.get(NOTE_EMAIL)))
                .select(SelectSet.of(NOTE_ID, NOTE_EMAIL))
                .primaryKey(PrimaryKey.of(NOTE_EMAIL))
                .where(f -> f.eq(NOTE_ID, 5L))
                .build();
        withExecutor(db, CustomerNoteEntity.class, ModelQueryConfig.defaults(), executor -> {
            // Note 5 is stored as CUSTOMER0002@EXAMPLE.TEST: the exact spelling matches, another case matches only by
            // the column's case-insensitive collation, which Java tells apart.
            assertThat(executor.byKeys(notes, List.of("CUSTOMER0002@EXAMPLE.TEST"))).containsOnlyKeys(
                    "CUSTOMER0002@EXAMPLE.TEST");
            assertMq(() -> executor.byKeys(notes, List.of("customer0002@example.test")), MqCode.MQ2005,
                    "NoteRow: byKeys(query, keys) read a row whose key CUSTOMER0002@EXAMPLE.TEST equals none of the "
                            + "requested keys, as a case-insensitive or padding collation, or a BigDecimal scale, "
                            + "can match; compare the key as the database does");
        });
    }

    // ---- AC-EXE-15

    @TckTest
    void ac_exe_15_one_renders_no_order_by(TckDatabase db) {
        var q = orders().where(f -> f.eq(ID, 7L)).orderBy(TOTAL.desc()).build();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-15-one-no-order", ds -> withExecutor(ds, executor -> {
            assertThat(executor.one(q)).isPresent();
            assertThat(executor.one(q, 7L)).isPresent();
        }));
        assertThat(sql).hasSize(2).allSatisfy(statement -> assertThat(statement).doesNotContainIgnoringCase(
                "order by"));
    }

    private static void assertMq(Runnable call, MqCode code, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessage(code.code() + ": " + message);
    }

    private static void withExecutor(TckDatabase db, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<OrderEntity>> work) {
        withExecutor(db, OrderEntity.class, config, work);
    }

    private static <E> void withExecutor(TckDatabase db, Class<E> root, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, config)));
        }
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        withExecutor(ds, ModelQueryConfig.defaults(), work);
    }

    private static void withExecutor(DataSource ds, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class, config)));
        }
    }
}
