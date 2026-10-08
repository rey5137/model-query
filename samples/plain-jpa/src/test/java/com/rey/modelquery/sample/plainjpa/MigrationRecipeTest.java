package com.rey.modelquery.sample.plainjpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/**
 * The "from JPQL" migration recipe (docs/site/docs/recipes.md): one dynamic DTO query, before and after, returning the
 * same rows.
 */
class MigrationRecipeTest {

    /** Before: the query string grows with each optional filter, and each parameter is bound a second time. */
    static List<OrderRow> ordersWithJpql(EntityManager em, Optional<String> status, Optional<String> country) {
        var jpql = new StringBuilder("select new com.rey.modelquery.sample.plainjpa.OrderRow(o.id, o.status, o.total,"
                + " c.name) from OrderEntity o left join o.customer c where 1 = 1");
        status.ifPresent(s -> jpql.append(" and o.status = :status"));
        country.ifPresent(s -> jpql.append(" and c.country = :country"));
        jpql.append(" order by o.id");
        var query = em.createQuery(jpql.toString(), OrderRow.class);
        status.ifPresent(s -> query.setParameter("status", s));
        country.ifPresent(s -> query.setParameter("country", s));
        return query.setMaxResults(100).getResultList();
    }

    /** After: the model holds the columns and the join, and an empty {@code Optional} skips its filter. */
    static List<OrderView> ordersWithModelQuery(EntityManager em, Optional<String> status, Optional<String> country) {
        var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
        var query = QOrderView.query()
                .select(QOrderView.ALL.with(QOrderView.CUSTOMER))
                .where(f -> f.eq(QOrderView.STATUS, status)
                        .eq(QOrderView.CUSTOMER_COUNTRY, country))
                .orderBy(QOrderView.ID.asc())
                .build();
        return executor.list(query, Limit.of(100));
    }

    @Test
    void bothQueriesReturnTheSameOrdersForEveryFilterCombination() {
        try (SessionFactory sessionFactory = ShopTour.sessionFactory("migration")) {
            sessionFactory.inSession(em -> {
                ShopTour.seed(em);
                for (var status : List.of(Optional.<String>empty(), Optional.of("PAID"))) {
                    for (var country : List.of(Optional.<String>empty(), Optional.of("DE"))) {
                        List<OrderRow> before = ordersWithJpql(em, status, country);
                        List<OrderView> after = ordersWithModelQuery(em, status, country);
                        assertThat(after).extracting(OrderView::getId)
                                .containsExactlyElementsOf(before.stream().map(OrderRow::id).toList());
                        assertThat(after).extracting(o -> o.getCustomer().map(CustomerView::name).orElse(null))
                                .containsExactlyElementsOf(before.stream().map(OrderRow::customerName).toList());
                    }
                }
                // Paid orders from Germany: only Ann's first order.
                assertThat(ordersWithModelQuery(em, Optional.of("PAID"), Optional.of("DE")))
                        .extracting(OrderView::getId).containsExactly(1L);
            });
        }
    }
}
