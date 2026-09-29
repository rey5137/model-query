package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.TableField;
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
import java.util.function.Function;
import org.hibernate.SessionFactory;

/** Null precedence rendered portably through plain JPA (spec api/10 R-COL-12). */
class NullPrecedenceTest {

    static final class SortView {}

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

    private static <C extends Comparable<? super C>> void check(CriteriaBuilder cb,
            jakarta.persistence.EntityManager em, List<Sort> all, SelectField<SortView, C> column,
            Function<Sort, C> value) {
        for (NullPrecedence nulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
            for (boolean ascending : List.of(true, false)) {
                OrderField<SortView, C> order = (ascending ? column.asc() : column.desc()).nulls(nulls);
                Comparator<C> values = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
                Comparator<C> withNulls = nulls == NullPrecedence.FIRST
                        ? Comparator.nullsFirst(values) : Comparator.nullsLast(values);
                List<Long> expected = all.stream()
                        .sorted(Comparator.comparing(value, withNulls).thenComparing(Sort::id))
                        .map(Sort::id).toList();

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
