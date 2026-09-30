package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Keyset paging over nullable columns through the resolved profile, with and without {@code model-query-hibernate}
 * (vendor/41 R-PRF-08..10, engine/21 R-PAG-05, vendor/40 R-VND-06). Where each vendor sorts its NULLs by default is
 * why this package may name a vendor (R-VND-04).
 */
class NullOrderingTest {

    record SortRow(Long id, Integer sortInt, String sortText, LocalDateTime sortTs) {}

    private static final TableField<NullableSortEntity, NullableSortEntity> ROOT =
            TableField.root(NullableSortEntity.class);
    private static final ColumnField<SortRow, NullableSortEntity, Long> ID =
            ColumnField.of(SortRow.class, ROOT, "id", Long.class);
    private static final ColumnField<SortRow, NullableSortEntity, Integer> SORT_INT =
            ColumnField.of(SortRow.class, ROOT, "sortInt", Integer.class);
    private static final ColumnField<SortRow, NullableSortEntity, String> SORT_TEXT =
            ColumnField.of(SortRow.class, ROOT, "sortText", String.class);
    private static final ColumnField<SortRow, NullableSortEntity, LocalDateTime> SORT_TS =
            ColumnField.of(SortRow.class, ROOT, "sortTs", LocalDateTime.class);

    /** Every nullable-sort row, keyset-paged: 3 000 rows. */
    private static final ModelQuery.Builder<NullableSortEntity, Long, SortRow> SORT_ROWS = ModelQuery
            .builder(ROOT, row -> new SortRow(row.get(ID), row.get(SORT_INT), row.get(SORT_TEXT), row.get(SORT_TS)))
            .columns(ColumnSet.of(ID, SORT_INT, SORT_TEXT, SORT_TS))
            .primaryKey(PrimaryKey.of(ID))
            .keyset();

    private static final ModelQueryConfig HONOUR =
            ModelQueryConfig.defaults().keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);

    /** A nullable column and how a {@link SortRow} holds its value. */
    private record Column<C extends Comparable<? super C>>(ColumnField<SortRow, NullableSortEntity, C> field,
            Function<SortRow, C> value) {}

    private static final List<Column<?>> COLUMNS = List.of(
            new Column<>(SORT_INT, SortRow::sortInt),
            new Column<>(SORT_TEXT, SortRow::sortText),
            new Column<>(SORT_TS, SortRow::sortTs));

    @TckTest
    void ac_prf_07_every_keyset_null_combination_pages_the_whole_table_once_with_and_without_hibernate(
            TckDatabase db) {
        // ASC/DESC x NULLS FIRST/LAST on each nullable type, rendered natively, as a CASE key, or not at all where the
        // vendor's default matches; 600, 428 and 272 NULLs against pages of 97 put cursors on NULL and non-NULL keys.
        List<SortRow> all = jdbc(db);
        for (boolean hibernate : List.of(true, false)) {
            withExecutor(db, hibernate, ModelQueryConfig.defaults(), executor -> {
                for (Column<?> column : COLUMNS) {
                    for (boolean ascending : List.of(true, false)) {
                        for (NullPrecedence nulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
                            checkEveryRowOnce(executor, all, column, ascending, nulls,
                                    "hibernate " + hibernate);
                        }
                    }
                }
            });
        }
    }

    @TckTest
    void ac_prf_07_an_explicit_precedence_pages_once_whatever_default_null_ordering_hibernate_is_configured_with(
            TckDatabase db) {
        // Hibernate applies hibernate.order_by.default_null_ordering to an order rendered without a null precedence,
        // so a precedence that matches the vendor's default must still reach Hibernate as that precedence, or the
        // ORDER BY and the keyset predicate disagree and the NULLs are skipped. Without model-query-hibernate the
        // setting is unreported, so the precedence always renders its own sort key (R-COL-12).
        List<SortRow> all = jdbc(db);
        for (String nullOrdering : List.of("first", "last")) {
            for (boolean hibernate : List.of(true, false)) {
                withExecutor(JoinTestSupport.sessionFactory(db, nullOrdering), hibernate, ModelQueryConfig.defaults(),
                        executor -> {
                            for (boolean ascending : List.of(true, false)) {
                                for (NullPrecedence nulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
                                    checkEveryRowOnce(executor, all, COLUMNS.get(0), ascending, nulls,
                                            "default_null_ordering " + nullOrdering + ", hibernate " + hibernate);
                                }
                            }
                        });
            }
        }
    }

    @TckTest
    void ac_prf_07_honour_null_precedence_pages_a_default_column_where_the_vendor_sorts_its_nulls(TckDatabase db) {
        // Without an explicit precedence the ORDER BY carries no null clause, so the database's own default decides,
        // and the keyset follows the profile's defaultAscendingNullOrdering() instead of refusing the NULLs.
        List<SortRow> all = jdbc(db);
        boolean nullsFirstAscending = db.vendor() != TckVendor.POSTGRESQL;
        for (boolean hibernate : List.of(true, false)) {
            withExecutor(db, hibernate, HONOUR, executor -> {
                for (Column<?> column : COLUMNS) {
                    for (boolean ascending : List.of(true, false)) {
                        NullPrecedence vendorNulls = nullsFirstAscending == ascending
                                ? NullPrecedence.FIRST : NullPrecedence.LAST;
                        checkEveryRowOnce(executor, all, column, ascending, NullPrecedence.DEFAULT, vendorNulls,
                                "hibernate " + hibernate);
                    }
                }
            });
        }
    }

    @TckTest
    void ac_prf_07_honour_null_precedence_pages_a_default_column_where_hibernates_default_null_ordering_puts_it(
            TckDatabase db) {
        // Hibernate's default_null_ordering puts the NULLs of a bare order at that end in both directions, whatever
        // the vendor's own default, so the keyset follows it instead of the profile's ordering (D-36).
        List<SortRow> all = jdbc(db);
        for (String nullOrdering : List.of("first", "last")) {
            NullPrecedence configured = nullOrdering.equals("first") ? NullPrecedence.FIRST : NullPrecedence.LAST;
            withExecutor(JoinTestSupport.sessionFactory(db, nullOrdering), true, HONOUR, executor -> {
                for (boolean ascending : List.of(true, false)) {
                    checkEveryRowOnce(executor, all, COLUMNS.get(0), ascending, NullPrecedence.DEFAULT, configured,
                            "default_null_ordering " + nullOrdering);
                }
            });
        }
    }

    @TckTest
    void ac_prf_07_fail_refuses_a_default_column_null_whatever_default_null_ordering_hibernate_is_configured_with(
            TckDatabase db) {
        // Under fail the NULLs of a DEFAULT column are refused wherever Hibernate's default_null_ordering sorts them:
        // the rows exported before the MQ2202 are exactly the first rows of that order, so none is skipped silently.
        // Without model-query-hibernate nothing reports that setting and the profile's ordering is wrong for it (H2
        // and MySQL report NULLs first where "last" sorts them last); the NULLs are refused all the same (D-35).
        List<SortRow> all = jdbc(db);
        ModelQueryConfig configured =
                ModelQueryConfig.defaults().vendor(DatabaseVendor.valueOf(db.vendor().name()));
        for (String nullOrdering : List.of("first", "last", "first-unreported", "last-unreported")) {
            boolean reported = !nullOrdering.endsWith("-unreported");
            withExecutor(JoinTestSupport.sessionFactory(db, nullOrdering.replace("-unreported", "")), reported,
                    reported ? ModelQueryConfig.defaults() : configured, executor -> {
                        for (boolean ascending : List.of(true, false)) {
                            Comparator<Integer> values = ascending ? Comparator.naturalOrder()
                                    : Comparator.reverseOrder();
                            Comparator<SortRow> byId = Comparator.comparing(SortRow::id);
                            List<Long> expected = all.stream()
                                    .sorted(Comparator.comparing(SortRow::sortInt, nullOrdering.startsWith("first")
                                                    ? Comparator.nullsFirst(values) : Comparator.nullsLast(values))
                                            .thenComparing(ascending ? byId : byId.reversed()))
                                    .map(SortRow::id).toList();
                            OrderField<SortRow, Integer> order = ascending ? SORT_INT.asc() : SORT_INT.desc();
                            List<Long> ids = new ArrayList<>();
                            String name = order + ", default_null_ordering " + nullOrdering;
                            assertThatThrownBy(() -> executor.export(SORT_ROWS.orderBy(order).build(),
                                    ExportOptions.of(97), page -> page, row -> ids.add(row.id())))
                                    .as(name)
                                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2202))
                                    .hasMessageContaining("keyset column sortInt is null in an exported row; order "
                                            + "it with nullsFirst() or nullsLast()");
                            assertThat(ids).as(name).containsExactlyElementsOf(expected.subList(0, ids.size()));
                        }
                    });
        }
    }

    @TckTest
    void ac_prf_07_honour_null_precedence_renders_the_vendors_null_branches(TckDatabase db) {
        // Ids 1..20, NULL on every fifth, pages of 4, DEFAULT precedence under honour-null-precedence. Where the
        // vendor sorts NULLs after the values the non-NULL cursors add "or sort_int is null"; where before, the NULL
        // cursors add "sort_int is not null" (R-PRF-09).
        var first20 = SORT_ROWS.where(f -> f.lte(ID, 20L));
        List<Long> ascIds = new ArrayList<>();
        List<Long> descIds = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "prf-07-keyset-honour-null-precedence", ds -> withExecutor(
                ds, true, HONOUR, executor -> {
                    executor.export(first20.orderBy(SORT_INT.asc()).build(), ExportOptions.of(4), page -> page,
                            row -> ascIds.add(row.id()));
                    executor.export(first20.orderBy(SORT_INT.desc()).build(), ExportOptions.of(4), page -> page,
                            row -> descIds.add(row.id()));
                }));
        assertThat(sql).hasSize(12);
        List<Long> nonNullAsc = List.of(1L, 2L, 3L, 4L, 6L, 7L, 8L, 9L, 11L, 12L, 13L, 14L, 16L, 17L, 18L, 19L);
        List<Long> nullsAsc = List.of(5L, 10L, 15L, 20L);
        List<Long> nonNullDesc = nonNullAsc.stream().sorted(Comparator.reverseOrder()).toList();
        List<Long> nullsDesc = nullsAsc.stream().sorted(Comparator.reverseOrder()).toList();
        if (db.vendor() == TckVendor.POSTGRESQL) {
            assertThat(ascIds).containsExactlyElementsOf(concat(nonNullAsc, nullsAsc));
            assertThat(descIds).containsExactlyElementsOf(concat(nullsDesc, nonNullDesc));
        } else {
            assertThat(ascIds).containsExactlyElementsOf(concat(nullsAsc, nonNullAsc));
            assertThat(descIds).containsExactlyElementsOf(concat(nonNullDesc, nullsDesc));
        }
    }

    @TckTest
    void ac_vnd_05_a_nullable_keyset_column_under_other_is_refused_even_with_honour_null_precedence(TckDatabase db) {
        // OTHER's null ordering is UNKNOWN, so a DEFAULT-precedence NULL cannot be placed; it is refused wherever the
        // database sorts it, with and without model-query-hibernate, and an explicit precedence still pages.
        for (boolean hibernate : List.of(true, false)) {
            for (ModelQueryConfig config : List.of(HONOUR.vendor(DatabaseVendor.OTHER),
                    ModelQueryConfig.defaults().vendor(DatabaseVendor.OTHER))) {
                withExecutor(db, hibernate, config, executor -> {
                    for (OrderField<SortRow, Integer> order : List.of(SORT_INT.asc(), SORT_INT.desc())) {
                        assertThatThrownBy(() -> executor.export(SORT_ROWS.orderBy(order).build(),
                                ExportOptions.of(100), page -> page, row -> {}))
                                .as("%s, hibernate %s, %s", order, hibernate, config.keysetNullKeys())
                                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2202))
                                .hasMessageStartingWith(MqCode.MQ2202.code() + ": SortRow:")
                                .hasMessageContaining("keyset column sortInt is null in an exported row and the "
                                        + "database's null ordering is unknown");
                    }
                });
            }
            withExecutor(db, hibernate, HONOUR.vendor(DatabaseVendor.OTHER), executor -> checkEveryRowOnce(executor,
                    jdbc(db), COLUMNS.get(0), true, NullPrecedence.LAST, "OTHER"));
        }
    }

    /**
     * Exports the nullable-sort rows keyset-paged by {@code column} with explicit {@code nulls} and asserts they
     * arrive in exactly Java's order, each once, with cursors both NULL and not.
     */
    private static void checkEveryRowOnce(ModelQueryExecutor<NullableSortEntity> executor, List<SortRow> all,
            Column<?> column, boolean ascending, NullPrecedence nulls, String context) {
        checkEveryRowOnce(executor, all, column, ascending, nulls, nulls, context);
    }

    /** As above, with the query's {@code nulls} and where the rows' NULLs are expected, {@code expectedNulls}. */
    private static <C extends Comparable<? super C>> void checkEveryRowOnce(
            ModelQueryExecutor<NullableSortEntity> executor, List<SortRow> all, Column<C> column, boolean ascending,
            NullPrecedence nulls, NullPrecedence expectedNulls, String context) {
        Comparator<C> values = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Comparator<SortRow> byId = Comparator.comparing(SortRow::id);
        Comparator<SortRow> order = Comparator.comparing(column.value(), expectedNulls == NullPrecedence.FIRST
                        ? Comparator.nullsFirst(values) : Comparator.nullsLast(values))
                .thenComparing(ascending ? byId : byId.reversed());
        List<Long> expected = all.stream().sorted(order).map(SortRow::id).toList();

        var q = SORT_ROWS.orderBy((ascending ? column.field().asc() : column.field().desc()).nulls(nulls)).build();
        List<Long> ids = new ArrayList<>();
        List<Boolean> cursorIsNull = new ArrayList<>();
        executor.export(q, ExportOptions.of(97), page -> {
            cursorIsNull.add(column.value().apply(page.get(page.size() - 1)) == null);
            return page;
        }, row -> ids.add(row.id()));
        String name = column.field().name() + (ascending ? " asc" : " desc") + " nulls " + nulls + ", " + context;
        assertThat(ids).as(name).containsExactlyElementsOf(expected);
        assertThat(cursorIsNull).as("cursors of %s", name).contains(true, false);
    }

    private static List<Long> concat(List<Long> first, List<Long> second) {
        List<Long> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    /**
     * Runs {@code work} on an executor of its own factory, which resolves with or without the Hibernate SPI; without
     * it, the vendor is read from the factory's DataSource.
     */
    private static void withExecutor(TckDatabase db, boolean hibernate, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<NullableSortEntity>> work) {
        withExecutor(JoinTestSupport.dataSource(db), hibernate, config, work);
    }

    private static void withExecutor(DataSource ds, boolean hibernate, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<NullableSortEntity>> work) {
        withExecutor(JoinTestSupport.sessionFactory(ds), hibernate, config, work);
    }

    private static void withExecutor(SessionFactory factory, boolean hibernate, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<NullableSortEntity>> work) {
        JoinTestSupport.withExecutor(factory, hibernate, NullableSortEntity.class, config, work);
    }

    private static List<SortRow> jdbc(TckDatabase db) {
        List<SortRow> result = new ArrayList<>();
        try (Connection c = db.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT id, sort_int, sort_text, sort_ts FROM nullable_sort_rows")) {
            while (rs.next()) {
                int sortInt = rs.getInt(2);
                Integer boxedInt = rs.wasNull() ? null : sortInt;
                result.add(new SortRow(rs.getLong(1), boxedInt, rs.getString(3),
                        rs.getObject(4, LocalDateTime.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(result).hasSize(TckFixture.NULLABLE_SORT_ROWS);
        return result;
    }
}
