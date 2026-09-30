package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
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
 * Null precedence rendered portably through plain JPA, and through the executor's profile with and without
 * {@code model-query-hibernate} (spec api/10 R-COL-12).
 */
class NullPrecedenceTest {

    record SortView(Long id) {}

    private static final TableField<NullableSortEntity, NullableSortEntity> ROOT =
            TableField.root(NullableSortEntity.class);

    private static final ColumnField<SortView, NullableSortEntity, Long> ID =
            ColumnField.of(SortView.class, ROOT, "id", Long.class);
    private static final ColumnField<SortView, NullableSortEntity, Integer> SORT_INT =
            ColumnField.of(SortView.class, ROOT, "sortInt", Integer.class);
    private static final ColumnField<SortView, NullableSortEntity, String> SORT_TEXT =
            ColumnField.of(SortView.class, ROOT, "sortText", String.class);
    private static final ColumnField<SortView, NullableSortEntity, LocalDateTime> SORT_TS =
            ColumnField.of(SortView.class, ROOT, "sortTs", LocalDateTime.class);

    private record Sort(long id, Integer sortInt, String sortText, LocalDateTime sortTs) {}

    /** The ids of the nullable-sort rows, ordered by the test. */
    private static final ModelQuery.Builder<NullableSortEntity, Object, SortView> SORT_IDS = ModelQuery
            .builder(ROOT, row -> new SortView(row.get(ID)))
            .columns(ColumnSet.of(ID));

    @TckTest
    void ac_col_08_nulls_first_and_last_give_identical_orderings_on_every_vendor(TckDatabase db) {
        List<Sort> all = jdbc(db);
        assertThat(all).anyMatch(r -> r.sortInt() == null).anyMatch(r -> r.sortInt() != null);
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                check(em.getCriteriaBuilder(), em, all, SORT_INT, Sort::sortInt);
                check(em.getCriteriaBuilder(), em, all, SORT_TEXT, Sort::sortText);
                check(em.getCriteriaBuilder(), em, all, SORT_TS, Sort::sortTs);
            });
        }
    }

    @TckTest
    void ac_col_08_nulls_first_and_last_order_identically_with_and_without_model_query_hibernate(TckDatabase db) {
        // Through the executor: HibernateCriteriaBuilder#sort with model-query-hibernate, else the portable CASE key.
        List<Sort> all = jdbc(db);
        for (boolean hibernate : List.of(true, false)) {
            withExecutor(db, hibernate, executor -> {
                checkThroughExecutor(executor, hibernate, all, SORT_INT, Sort::sortInt);
                checkThroughExecutor(executor, hibernate, all, SORT_TEXT, Sort::sortText);
                checkThroughExecutor(executor, hibernate, all, SORT_TS, Sort::sortTs);
            });
        }
    }

    @TckTest
    void ac_col_08_only_model_query_hibernate_renders_no_null_key_where_the_vendor_default_matches(TckDatabase db) {
        // Per vendor, with then without model-query-hibernate: asc/desc x nulls first/last on sort_int. Hibernate
        // renders the column alone where the dialect's default matches, else renders or emulates the precedence;
        // plain JPA always prepends the CASE key, since a bare order would take a default null ordering the provider
        // is configured with.
        List<String> sql = SqlSnapshots.assertMatches(db, "col-08-null-precedence-profile", ds -> {
            for (boolean hibernate : List.of(true, false)) {
                withExecutor(ds, hibernate, executor -> {
                    for (OrderField<SortView, Integer> order : List.of(SORT_INT.asc().nullsFirst(),
                            SORT_INT.asc().nullsLast(), SORT_INT.desc().nullsFirst(), SORT_INT.desc().nullsLast())) {
                        executor.list(SORT_IDS.orderBy(order, ID.asc()).build(), Limit.of(3));
                    }
                });
            }
        });
        assertThat(sql).hasSize(8);
        // Each vendor's default matches exactly one precedence per direction, so with Hibernate two of the four
        // orders render the column alone.
        assertThat(sql.subList(4, 8)).filteredOn(s -> s.contains("case when")).hasSize(4);
        assertThat(sql.subList(0, 4)).filteredOn(s -> s.contains(" nulls ") || s.contains("case when")).hasSize(2);
    }

    private static <C extends Comparable<? super C>> void checkThroughExecutor(
            ModelQueryExecutor<NullableSortEntity> executor, boolean hibernate, List<Sort> all,
            ColumnField<SortView, NullableSortEntity, C> column, Function<Sort, C> value) {
        for (NullPrecedence nulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
            for (boolean ascending : List.of(true, false)) {
                OrderField<SortView, C> order = (ascending ? column.asc() : column.desc()).nulls(nulls);
                assertThat(executor.list(SORT_IDS.orderBy(order, ID.asc()).build(), Limit.unlimited()))
                        .extracting(SortView::id)
                        .as("%s %s nulls %s, hibernate %s", column.name(), ascending ? "asc" : "desc", nulls,
                                hibernate)
                        .containsExactlyElementsOf(expected(all, value, ascending, nulls));
            }
        }
    }

    /** The ids of {@code all} in Java's order for the column, then by id. */
    private static <C extends Comparable<? super C>> List<Long> expected(List<Sort> all, Function<Sort, C> value,
            boolean ascending, NullPrecedence nulls) {
        Comparator<C> values = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Comparator<C> withNulls = nulls == NullPrecedence.FIRST
                ? Comparator.nullsFirst(values) : Comparator.nullsLast(values);
        return all.stream().sorted(Comparator.comparing(value, withNulls).thenComparing(Sort::id))
                .map(Sort::id).toList();
    }

    /**
     * Runs {@code work} on an executor of its own factory, which resolves with or without the Hibernate SPI; without
     * it, the vendor is read from the factory's DataSource.
     */
    private static void withExecutor(TckDatabase db, boolean hibernate,
            Consumer<ModelQueryExecutor<NullableSortEntity>> work) {
        withExecutor(JoinTestSupport.dataSource(db), hibernate, work);
    }

    private static void withExecutor(DataSource ds, boolean hibernate,
            Consumer<ModelQueryExecutor<NullableSortEntity>> work) {
        withExecutor(JoinTestSupport.sessionFactory(ds), hibernate, work);
    }

    private static void withExecutor(SessionFactory factory, boolean hibernate,
            Consumer<ModelQueryExecutor<NullableSortEntity>> work) {
        JoinTestSupport.withExecutor(factory, hibernate, NullableSortEntity.class, ModelQueryConfig.defaults(), work);
    }

    private static <C extends Comparable<? super C>> void check(CriteriaBuilder cb,
            jakarta.persistence.EntityManager em, List<Sort> all, SelectField<SortView, C> column,
            Function<Sort, C> value) {
        for (NullPrecedence nulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
            for (boolean ascending : List.of(true, false)) {
                OrderField<SortView, C> order = (ascending ? column.asc() : column.desc()).nulls(nulls);
                List<Long> expected = expected(all, value, ascending, nulls);

                CriteriaQuery<Long> q = cb.createQuery(Long.class);
                Root<NullableSortEntity> root = q.from(NullableSortEntity.class);
                JoinContext ctx = JoinContext.of(root, cb);
                List<jakarta.persistence.criteria.Order> orders = new ArrayList<>(order.toOrders(ctx, cb));
                orders.add(cb.asc(ID.path(ctx)));
                q.select(ID.path(ctx)).orderBy(orders);
                assertThat(em.createQuery(q).getResultList())
                        .as("%s %s nulls %s", column.name(), ascending ? "asc" : "desc", nulls)
                        .containsExactlyElementsOf(expected);
            }
        }
    }

    @TckTest
    void ac_col_08_default_renders_no_null_clause_and_first_last_prepend_a_case_key(TckDatabase db) {
        assertThat(SORT_INT.asc().nulls()).isEqualTo(NullPrecedence.DEFAULT);
        assertThat(SORT_INT.desc().nullsLast().ascending()).isFalse();
        SqlSnapshots.assertMatches(db, "col-08-null-precedence", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    for (OrderField<SortView, Integer> order : List.of(SORT_INT.asc(), SORT_INT.asc().nullsFirst(),
                            SORT_INT.desc().nullsLast())) {
                        CriteriaQuery<Long> q = cb.createQuery(Long.class);
                        Root<NullableSortEntity> root = q.from(NullableSortEntity.class);
                        JoinContext ctx = JoinContext.of(root, cb);
                        q.select(ID.path(ctx)).orderBy(order.toOrders(ctx, cb));
                        em.createQuery(q).setMaxResults(3).getResultList();
                    }
                });
            }
        });
    }

    private static List<Sort> jdbc(TckDatabase db) {
        List<Sort> result = new ArrayList<>();
        try (Connection c = db.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT id, sort_int, sort_text, sort_ts FROM nullable_sort_rows")) {
            while (rs.next()) {
                int sortInt = rs.getInt(2);
                Integer boxedInt = rs.wasNull() ? null : sortInt;
                result.add(new Sort(rs.getLong(1), boxedInt, rs.getString(3), rs.getObject(4, LocalDateTime.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }
}
