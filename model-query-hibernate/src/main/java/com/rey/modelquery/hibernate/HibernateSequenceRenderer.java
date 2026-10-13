package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.SequenceRenderer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import org.hibernate.dialect.Dialect;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

/**
 * Hibernate's {@link SequenceRenderer}: the sequence call the {@link Dialect}'s {@code SequenceSupport} spells,
 * rendered as a SQL fragment through {@link HibernateCriteriaBuilder#sql}. Compiled against Hibernate 6.6 and run on
 * 7.x as well.
 *
 * @implSpec R-VND-18, R-COL-28
 */
final class HibernateSequenceRenderer implements SequenceRenderer {

    private final Dialect dialect;

    HibernateSequenceRenderer(Dialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public Expression<Long> nextValue(CriteriaBuilder cb, String sequence) {
        if (!(cb instanceof HibernateCriteriaBuilder hibernate)) {
            throw new IllegalStateException("the criteria builder is not Hibernate's: " + cb.getClass().getName());
        }
        String sql = dialect.getSequenceSupport().getSelectSequenceNextValString(sequence);
        return hibernate.sql(sql, Long.class);
    }
}
