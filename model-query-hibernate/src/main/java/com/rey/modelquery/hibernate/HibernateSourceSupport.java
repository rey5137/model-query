package com.rey.modelquery.hibernate;

import com.rey.modelquery.jpa.spi.SourceSupport;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;

/**
 * Hibernate's {@link SourceSupport}: {@code JpaCriteriaQuery#with} registers the source's body as the statement's
 * common table expression and {@code JpaCriteriaQuery#from(JpaCteCriteria)} reads it, identical on Hibernate 6.6 and
 * 7.4 (R-SRC-12, D-143). The CTE is registered on each statement {@code jpa} builds over a source, so the call is
 * per statement and carries no state.
 */
final class HibernateSourceSupport implements SourceSupport {

    static final HibernateSourceSupport INSTANCE = new HibernateSourceSupport();

    private HibernateSourceSupport() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
        // jpa builds the outer statement with the provider's own CriteriaBuilder, so it is Hibernate's query type.
        JpaCriteriaQuery<Tuple> query = (JpaCriteriaQuery<Tuple>) outer;
        JpaCteCriteria<Tuple> cte = query.with(body);
        return query.from(cte);
    }
}
