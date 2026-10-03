package com.rey.modelquery.tck.pag;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.EmbeddedKeyEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.StringKeyProductEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;

/**
 * Keyset paging, keyset export and primary-key-first paging over a String {@code @Id} and an {@code @EmbeddedId}
 * (spec engine/21 §2-3, D-111 item 9).
 */
class StringAndEmbeddedKeyTest {

    record StringRow(String code, String name, String category) {}

    record EmbeddedRow(String regionCode, int seqNo, String label) {}

    // ---- the String key

    private static final TableField<StringKeyProductEntity, StringKeyProductEntity> PRODUCTS =
            TableField.root(StringKeyProductEntity.class);
    private static final ColumnField<StringRow, StringKeyProductEntity, String> CODE =
            ColumnField.of(StringRow.class, PRODUCTS, "code", String.class);
    private static final ColumnField<StringRow, StringKeyProductEntity, String> NAME =
            ColumnField.of(StringRow.class, PRODUCTS, "name", String.class);
    private static final ColumnField<StringRow, StringKeyProductEntity, String> CATEGORY =
            ColumnField.of(StringRow.class, PRODUCTS, "category", String.class);

    /** Category has three values, so every order key is shared by ten rows and only the String key closes the order. */
    private static final Comparator<StringRow> STRING_ORDER =
            Comparator.comparing(StringRow::category).thenComparing(StringRow::code);

    private static ModelQuery.Builder<StringKeyProductEntity, String, StringRow> stringRows() {
        return ModelQuery.builder(PRODUCTS, row -> new StringRow(row.get(CODE), row.get(NAME), row.get(CATEGORY)))
                .select(SelectSet.of(CODE, NAME, CATEGORY))
                .primaryKey(PrimaryKey.of(CODE));
    }

    // ---- AC-PAG-24

    @TckTest
    void ac_pag_24_a_string_key_keyset_page_walks_forward_and_back_over_tied_sort_keys(TckDatabase db) {
        // Three pages of size 4 walk 30 rows whose category ties straddle every boundary; the appended String key
        // closes the order (R-PAG-04, R-PAG-16).
        var q = stringRows().orderBy(CATEGORY.asc()).keyset().build();
        var list = stringRows().orderBy(CATEGORY.asc()).build();
        withExecutor(db, StringKeyProductEntity.class, executor -> {
            List<KeysetSlice<StringRow>> pages = forward(executor, q, 4);
            List<StringRow> rows = flatten(pages);
            assertThat(pages.get(0).hasPrevious()).isFalse();
            assertThat(pages.get(pages.size() - 1).hasNext()).isFalse();
            assertThat(rows).extracting(StringRow::code).hasSize(TckFixture.STRING_KEY_PRODUCTS)
                    .doesNotHaveDuplicates();
            assertThat(rows).isSortedAccordingTo(STRING_ORDER);
            assertThat(rows).containsExactlyInAnyOrderElementsOf(executor.list(list, Limit.unlimited()));
            assertThat(walkBack(executor, q, 4, pages)).isEqualTo(rows);
        });
    }

    @TckTest
    void ac_pag_24_a_string_key_keyset_export_visits_the_same_rows_as_list(TckDatabase db) {
        // Pages of 7 are not a divisor of the ten-row tie groups, so ties straddle every boundary (R-PAG-04).
        var q = stringRows().orderBy(CATEGORY.asc()).keyset().build();
        var list = stringRows().orderBy(CATEGORY.asc()).build();
        List<StringRow> exported = new ArrayList<>();
        withExecutor(db, StringKeyProductEntity.class, executor -> {
            assertThat(executor.export(q, ExportOptions.of(7), page -> page, exported::add))
                    .isEqualTo(TckFixture.STRING_KEY_PRODUCTS);
            assertThat(exported).containsExactlyInAnyOrderElementsOf(executor.list(list, Limit.unlimited()));
        });
        assertThat(exported).doesNotHaveDuplicates().isSortedAccordingTo(STRING_ORDER);
    }

    @TckTest
    void ac_pag_24_a_string_key_primary_key_first_pages_hold_the_same_rows_as_list(TckDatabase db) {
        var plain = stringRows().orderBy(CATEGORY.asc()).build();
        var twoStep = stringRows().orderBy(CATEGORY.asc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                .build();
        withExecutor(db, StringKeyProductEntity.class, executor -> assertThat(primaryKeyFirst(executor, twoStep,
                        plain, 7))
                .hasSize(TckFixture.STRING_KEY_PRODUCTS).doesNotHaveDuplicates().isSortedAccordingTo(STRING_ORDER));
    }

    @TckTest
    void ac_pag_24_a_string_key_with_a_filter_and_a_non_key_sort_pages_every_path(TckDatabase db) {
        // Twenty of the thirty rows, sorted on the non-key category: the same rows through keyset page, keyset export
        // and primary-key-first (R-PAG-04, R-PAG-07, R-PAG-16).
        var filtered = stringRows().where(f -> f.in(CATEGORY, List.of("cat-00", "cat-02"))).orderBy(CATEGORY.asc());
        var keyset = filtered.keyset().build();
        var plain = filtered.build();
        var twoStep = filtered.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        List<StringRow> exported = new ArrayList<>();
        withExecutor(db, StringKeyProductEntity.class, executor -> {
            List<StringRow> listed = executor.list(plain, Limit.unlimited());
            assertThat(listed).hasSize(20);
            List<KeysetSlice<StringRow>> pages = forward(executor, keyset, 3);
            List<StringRow> walked = flatten(pages);
            assertThat(walked).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(listed)
                    .isSortedAccordingTo(STRING_ORDER);
            assertThat(walkBack(executor, keyset, 3, pages)).isEqualTo(walked);
            assertThat(executor.export(keyset, ExportOptions.of(3), page -> page, exported::add)).isEqualTo(20);
            assertThat(exported).isEqualTo(walked);
            assertThat(primaryKeyFirst(executor, twoStep, plain, 3)).isEqualTo(walked);
        });
    }

    // ---- the @EmbeddedId

    private static final TableField<EmbeddedKeyEntity, EmbeddedKeyEntity> EMBEDDED =
            TableField.root(EmbeddedKeyEntity.class);
    private static final ColumnField<EmbeddedRow, EmbeddedKeyEntity, String> REGION =
            ColumnField.of(EmbeddedRow.class, EMBEDDED, "key.regionCode", String.class);
    private static final ColumnField<EmbeddedRow, EmbeddedKeyEntity, Integer> SEQ =
            ColumnField.of(EmbeddedRow.class, EMBEDDED, "key.seqNo", Integer.class);
    private static final ColumnField<EmbeddedRow, EmbeddedKeyEntity, String> LABEL =
            ColumnField.of(EmbeddedRow.class, EMBEDDED, "label", String.class);

    /** Four labels over thirty rows; the two-column embedded key closes the order (R-PAG-04). */
    private static final Comparator<EmbeddedRow> EMBEDDED_ORDER = Comparator.comparing(EmbeddedRow::label)
            .thenComparing(EmbeddedRow::regionCode)
            .thenComparing(EmbeddedRow::seqNo);

    private static ModelQuery.Builder<EmbeddedKeyEntity, List<Object>, EmbeddedRow> embeddedRows() {
        return ModelQuery
                .builder(EMBEDDED, row -> new EmbeddedRow(row.get(REGION), row.get(SEQ), row.get(LABEL)))
                .select(SelectSet.of(REGION, SEQ, LABEL))
                .primaryKey(PrimaryKey.composite(REGION, SEQ));
    }

    // ---- AC-PAG-25

    @TckTest
    void ac_pag_25_an_embedded_key_keyset_page_walks_forward_and_back_over_tied_sort_keys(TckDatabase db) {
        var q = embeddedRows().orderBy(LABEL.asc()).keyset().build();
        var list = embeddedRows().orderBy(LABEL.asc()).build();
        withExecutor(db, EmbeddedKeyEntity.class, executor -> {
            List<KeysetSlice<EmbeddedRow>> pages = forward(executor, q, 4);
            List<EmbeddedRow> rows = flatten(pages);
            assertThat(pages.get(0).hasPrevious()).isFalse();
            assertThat(pages.get(pages.size() - 1).hasNext()).isFalse();
            assertThat(rows).extracting(EmbeddedRow::regionCode, EmbeddedRow::seqNo)
                    .hasSize(TckFixture.EMBEDDED_KEY_ITEMS)
                    .doesNotHaveDuplicates();
            assertThat(rows).isSortedAccordingTo(EMBEDDED_ORDER);
            assertThat(rows).containsExactlyInAnyOrderElementsOf(executor.list(list, Limit.unlimited()));
            assertThat(walkBack(executor, q, 4, pages)).isEqualTo(rows);
        });
    }

    @TckTest
    void ac_pag_25_an_embedded_key_keyset_export_visits_the_same_rows_as_list(TckDatabase db) {
        // Pages of 5 are not a divisor of the label groups, so ties straddle every boundary (R-PAG-04).
        var q = embeddedRows().orderBy(LABEL.asc()).keyset().build();
        var list = embeddedRows().orderBy(LABEL.asc()).build();
        List<EmbeddedRow> exported = new ArrayList<>();
        withExecutor(db, EmbeddedKeyEntity.class, executor -> {
            assertThat(executor.export(q, ExportOptions.of(5), page -> page, exported::add))
                    .isEqualTo(TckFixture.EMBEDDED_KEY_ITEMS);
            assertThat(exported).containsExactlyInAnyOrderElementsOf(executor.list(list, Limit.unlimited()));
        });
        assertThat(exported).doesNotHaveDuplicates().isSortedAccordingTo(EMBEDDED_ORDER);
    }

    @TckTest
    void ac_pag_25_an_embedded_key_primary_key_first_pages_hold_the_same_rows_as_list(TckDatabase db) {
        var plain = embeddedRows().orderBy(LABEL.asc()).build();
        var twoStep = embeddedRows().orderBy(LABEL.asc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                .build();
        withExecutor(db, EmbeddedKeyEntity.class, executor -> assertThat(primaryKeyFirst(executor, twoStep, plain, 7))
                .hasSize(TckFixture.EMBEDDED_KEY_ITEMS).doesNotHaveDuplicates().isSortedAccordingTo(EMBEDDED_ORDER));
    }

    @TckTest
    void ac_pag_25_an_embedded_key_with_a_filter_and_a_non_key_sort_pages_every_path(TckDatabase db) {
        // Two of the four labels, sorted on the non-key label column: the same rows through keyset page, keyset
        // export and primary-key-first (R-PAG-04, R-PAG-07, R-PAG-16).
        var filtered = embeddedRows().where(f -> f.in(LABEL, List.of("label-00", "label-02"))).orderBy(LABEL.asc());
        var keyset = filtered.keyset().build();
        var plain = filtered.build();
        var twoStep = filtered.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        List<EmbeddedRow> exported = new ArrayList<>();
        withExecutor(db, EmbeddedKeyEntity.class, executor -> {
            List<EmbeddedRow> listed = executor.list(plain, Limit.unlimited());
            assertThat(listed).hasSize(15);
            List<KeysetSlice<EmbeddedRow>> pages = forward(executor, keyset, 5);
            List<EmbeddedRow> walked = flatten(pages);
            assertThat(walked).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(listed)
                    .isSortedAccordingTo(EMBEDDED_ORDER);
            assertThat(walkBack(executor, keyset, 5, pages)).isEqualTo(walked);
            assertThat(executor.export(keyset, ExportOptions.of(5), page -> page, exported::add)).isEqualTo(15);
            assertThat(exported).isEqualTo(walked);
            assertThat(primaryKeyFirst(executor, twoStep, plain, 5)).isEqualTo(walked);
        });
    }

    // ---- support

    /** The pages of a full forward walk, in order. */
    private static <E, M> List<KeysetSlice<M>> forward(ModelQueryExecutor<E> executor, ModelQuery<E, ?, M> q,
            int size) {
        List<KeysetSlice<M>> pages = new ArrayList<>();
        KeysetSpec spec = KeysetSpec.first(size);
        while (true) {
            KeysetSlice<M> page = executor.page(q, spec);
            pages.add(page);
            if (!page.hasNext()) {
                return pages;
            }
            spec = KeysetSpec.after(page.nextCursor().orElseThrow(), size);
        }
    }

    /** The rows of a full backward walk, in the query's order. */
    private static <E, M> List<M> walkBack(ModelQueryExecutor<E> executor, ModelQuery<E, ?, M> q, int size,
            List<KeysetSlice<M>> pages) {
        List<M> rows = new ArrayList<>(pages.get(pages.size() - 1).content());
        Optional<String> cursor = pages.get(pages.size() - 1).previousCursor();
        while (cursor.isPresent()) {
            KeysetSlice<M> page = executor.page(q, KeysetSpec.before(cursor.get(), size));
            rows.addAll(0, page.content());
            cursor = page.previousCursor();
        }
        return rows;
    }

    /** The rows of every primary-key-first page, checked against the one-step pages as it goes. */
    private static <E, M> List<M> primaryKeyFirst(ModelQueryExecutor<E> executor, ModelQuery<E, ?, M> twoStep,
            ModelQuery<E, ?, M> plain, int size) {
        List<M> rows = new ArrayList<>();
        Slice<M> expected;
        int page = 0;
        do {
            PageSpec spec = PageSpec.of(page++, size);
            expected = executor.page(plain, spec, CountMode.NO_COUNT);
            assertThat(executor.page(twoStep, spec, CountMode.NO_COUNT).content())
                    .as("page %d", spec.pageNumber()).isEqualTo(expected.content());
            rows.addAll(expected.content());
        } while (expected.hasNext());
        return rows;
    }

    private static <M> List<M> flatten(List<KeysetSlice<M>> pages) {
        List<M> rows = new ArrayList<>();
        for (KeysetSlice<M> page : pages) {
            rows.addAll(page.content());
        }
        return rows;
    }

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
