package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.SortSpec;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** Direct source reads through the generated Rows vocabulary (R-SRC-17, AC-SRC-34, AC-SRC-38). */
class SourceReadExecutionTest {

    @TckTest
    void ac_src_34_rows_default_maps_plain_source_rows_into_the_source_model(TckDatabase db) {
        var query = QOrderSource.query(QOrderSource.source().build())
                .select(QOrderSource.Rows.DEFAULT)
                .where(f -> f.lte(QOrderSource.Rows.ID, 3L))
                .orderBy(QOrderSource.Rows.ID.asc())
                .build();
        assertThat(query.primaryKey()).contains(QOrderSource.Rows.KEY);

        SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderSource.class, executor ->
                assertThat(executor.list(query, Limit.unlimited())).containsExactly(
                        new OrderSource(1L, "PAID", new BigDecimal("13.17")),
                        new OrderSource(2L, "SHIPPED", new BigDecimal("26.34")),
                        new OrderSource(3L, "CANCELLED", new BigDecimal("39.51")))));
    }

    @TckTest
    void ac_src_34_a_direct_read_sorts_by_the_source_models_field_name(TckDatabase db) {
        var query = QOrderSource.query(QOrderSource.source().build()).select(QOrderSource.Rows.DEFAULT)
                .where(f -> f.lte(QOrderSource.Rows.ID, 3L)).build()
                .orderedBy(SortSpec.of(SortSpec.Key.desc("id")));
        assertThat(QOrderSource.Rows.ID.property()).contains("id");

        SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderSource.class, executor ->
                assertThat(executor.list(query, Limit.unlimited())).containsExactly(
                        new OrderSource(3L, "CANCELLED", new BigDecimal("39.51")),
                        new OrderSource(2L, "SHIPPED", new BigDecimal("26.34")),
                        new OrderSource(1L, "PAID", new BigDecimal("13.17")))));
    }

    @TckTest
    void ac_src_34_a_window_filter_and_keyset_cursor_read_the_completed_source(TckDatabase db) {
        SourceQuery<OrderSource> inner = QOrderSource.source().where(f -> f.lte(QOrderSource.ID, 8L)).build();
        SourceQuery<RankedOrderSource> source = QRankedOrderSource.source(inner).build();
        var query = QRankedOrderSource.query(source)
                .select(QRankedOrderSource.Rows.DEFAULT)
                .where(f -> f.eq(QRankedOrderSource.Rows.RANK, 1L))
                .orderBy(QRankedOrderSource.Rows.ID.asc())
                .keyset()
                .build();
        assertThat(source.unique()).isTrue();
        assertThat(query.primaryKey()).contains(QRankedOrderSource.Rows.KEY);

        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedOrderSource.class, executor -> {
            KeysetSlice<RankedOrderSource> first = executor.page(query, KeysetSpec.first(2));
            KeysetSlice<RankedOrderSource> second = executor.page(
                    query, KeysetSpec.after(first.nextCursor().orElseThrow(), 2));
            assertThat(first.content()).containsExactly(
                    new RankedOrderSource(5L, "PAID", new BigDecimal("65.85"), 1L),
                    new RankedOrderSource(6L, "SHIPPED", new BigDecimal("79.02"), 1L));
            assertThat(second.content()).containsExactly(
                    new RankedOrderSource(7L, "CANCELLED", new BigDecimal("92.19"), 1L),
                    new RankedOrderSource(8L, "NEW", new BigDecimal("105.36"), 1L));
            assertThat(second.hasNext()).isFalse();
            assertThat(executor.count(query)).isEqualTo(4L);
        }));
    }

    @TckTest
    void ac_src_34_a_non_unique_direct_read_refuses_key_identifying_features_before_sql(TckDatabase db) {
        SourceQuery<FolderNode> walk = QFolderNode.source()
                .where(f -> f.eq(QFolderNode.ID, TckFixture.FOLDER_TREE_ROOT))
                .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), 3)
                .build();
        assertThat(walk.unique()).isFalse();
        assertThat(assertCode(() -> QFolderNode.query(walk).select(QFolderNode.Rows.DEFAULT)
                .orderBy(QFolderNode.Rows.ID.asc()).keyset().build(), MqCode.MQ1901))
                .isInstanceOf(ModelQueryDefinitionException.class);
        assertThat(assertCode(() -> QFolderNode.query(walk).select(QFolderNode.Rows.DEFAULT)
                .orderBy(QFolderNode.Rows.ID.asc()).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build(),
                MqCode.MQ1901)).isInstanceOf(ModelQueryDefinitionException.class);

        var query = QFolderNode.query(walk).select(QFolderNode.Rows.DEFAULT)
                .orderBy(QFolderNode.Rows.ID.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            assertCode(() -> executor.byKeys(query, List.of(1L)), MqCode.MQ2212);
            assertCode(() -> executor.one(query, 1L), MqCode.MQ2212);
            assertCode(() -> executor.export(query, ExportOptions.of(2), page -> page, row -> {}), MqCode.MQ2212);
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_38_a_tree_supports_direct_count_keyed_reads_and_every_export_page(TckDatabase db) {
        SourceQuery<FolderNode> tree = tree();
        var query = QFolderNode.query(tree).select(QFolderNode.Rows.DEFAULT)
                .orderBy(QFolderNode.Rows.ID.asc()).keyset().build();
        List<FolderNode> expected = List.of(
                new FolderNode(1L, null, "node-1", 0), new FolderNode(2L, 1L, "node-2", 1),
                new FolderNode(3L, 1L, "node-3", 1), new FolderNode(4L, 2L, "node-4", 2),
                new FolderNode(5L, 2L, "node-5", 2), new FolderNode(6L, 3L, "node-6", 2));

        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            assertThat(executor.list(query, Limit.unlimited())).containsExactlyElementsOf(expected);
            assertThat(executor.count(query)).isEqualTo(6L);
            assertThat(executor.byKeys(query, List.of(1L, 4L)))
                    .containsOnlyKeys(1L, 4L).containsEntry(1L, expected.get(0)).containsEntry(4L, expected.get(3));
            assertThat(executor.one(query, 4L)).contains(expected.get(3));

            List<FolderNode> exported = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            assertThat(executor.export(query, ExportOptions.of(2), page -> {
                pages.add(page.size());
                return page;
            }, exported::add)).isEqualTo(6L);
            assertThat(pages).containsExactly(2, 2, 2);
            assertThat(exported).containsExactlyElementsOf(expected);
        }));
    }

    @TckTest
    void ac_src_38_keyed_reads_and_export_keep_a_chain_windows_full_source_ranks(TckDatabase db) {
        SourceQuery<RankedFolderNode> source = QRankedFolderNode.source(tree()).build();
        var query = QRankedFolderNode.query(source).select(QRankedFolderNode.Rows.DEFAULT)
                .orderBy(QRankedFolderNode.Rows.ID.asc()).keyset().build();
        List<RankedFolderNode> expected = List.of(
                new RankedFolderNode(1L, null, "node-1", 0, 6L), new RankedFolderNode(2L, 1L, "node-2", 1, 4L),
                new RankedFolderNode(3L, 1L, "node-3", 1, 5L), new RankedFolderNode(4L, 2L, "node-4", 2, 1L),
                new RankedFolderNode(5L, 2L, "node-5", 2, 2L), new RankedFolderNode(6L, 3L, "node-6", 2, 3L));

        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedFolderNode.class, executor -> {
            assertThat(executor.count(query)).isEqualTo(6L);
            assertThat(executor.one(query, 1L)).contains(expected.get(0));
            assertThat(executor.byKeys(query, List.of(1L, 3L)))
                    .containsOnlyKeys(1L, 3L).containsEntry(1L, expected.get(0)).containsEntry(3L, expected.get(2));
            KeysetSlice<RankedFolderNode> first = executor.page(query, KeysetSpec.first(2));
            KeysetSlice<RankedFolderNode> second = executor.page(
                    query, KeysetSpec.after(first.nextCursor().orElseThrow(), 2));
            assertThat(first.content()).containsExactlyElementsOf(expected.subList(0, 2));
            assertThat(second.content()).containsExactlyElementsOf(expected.subList(2, 4));

            List<RankedFolderNode> exported = new ArrayList<>();
            assertThat(executor.export(query, ExportOptions.of(2), page -> page, exported::add)).isEqualTo(6L);
            assertThat(exported).containsExactlyElementsOf(expected);
        }));
    }

    @TckTest
    void ac_src_38_a_grouped_chain_maps_aggregates_and_pages_by_its_source_key(TckDatabase db) {
        SourceQuery<SubtreeSize> source = QSubtreeSize.source(tree()).build();
        var query = QSubtreeSize.query(source).select(QSubtreeSize.Rows.DEFAULT)
                .orderBy(QSubtreeSize.Rows.LEVEL.asc()).keyset().build();
        assertThat(source.unique()).isTrue();
        assertThat(query.primaryKey()).contains(QSubtreeSize.Rows.KEY);
        List<SubtreeSize> expected = List.of(new SubtreeSize(0, 1L), new SubtreeSize(1, 2L), new SubtreeSize(2, 3L));

        SqlSnapshots.capture(db, ds -> withExecutor(ds, SubtreeSize.class, executor -> {
            assertThat(executor.list(query, Limit.unlimited())).containsExactlyElementsOf(expected);
            assertThat(executor.count(query)).isEqualTo(3L);
            assertThat(executor.byKeys(query, List.of(0, 2)))
                    .containsOnlyKeys(0, 2).containsEntry(0, expected.get(0)).containsEntry(2, expected.get(2));
            assertThat(executor.one(query, 1)).contains(expected.get(1));
            List<SubtreeSize> exported = new ArrayList<>();
            assertThat(executor.export(query, ExportOptions.of(2), page -> page, exported::add)).isEqualTo(3L);
            assertThat(exported).containsExactlyElementsOf(expected);
        }));
    }

    private static SourceQuery<FolderNode> tree() {
        return QFolderNode.source().where(f -> f.eq(QFolderNode.ID, TckFixture.FOLDER_TREE_ROOT))
                .tree(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), 3).build();
    }

    private static ModelQueryException assertCode(Runnable work, MqCode code) {
        Throwable refusal = catchThrowable(work::run);
        assertThat(refusal).isInstanceOf(ModelQueryException.class);
        assertThat(((ModelQueryException) refusal).code()).isEqualTo(code);
        return (ModelQueryException) refusal;
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
