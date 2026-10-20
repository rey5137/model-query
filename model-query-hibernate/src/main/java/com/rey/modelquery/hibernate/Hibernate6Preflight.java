package com.rey.modelquery.hibernate;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.spi.QueryImplementor;
import org.hibernate.query.sqm.internal.DomainParameterXref;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.sql.ast.spi.SqlAstCreationContext;
import org.hibernate.sql.ast.tree.select.SelectStatement;

/**
 * Hibernate 6.6's select translation for the statement preflight (R-VND-26): the session factory is itself the
 * {@code SqlAstCreationContext}. Written and tested on 6.6.58.Final; {@link Hibernate7Preflight} is the 7.4 line's.
 *
 * @implSpec R-VND-26
 */
final class Hibernate6Preflight implements PreflightTranslation {

    @Override
    public boolean probes(SessionFactoryImplementor sf) {
        return HibernateMutations.getter(SessionFactoryImplementor.class, "getSqlTranslationEngine",
                        SqlAstCreationContext.class) == null
                && HibernateMutations.method(DomainParameterXref.class, "from", 1) != null
                && sf instanceof SqlAstCreationContext;
    }

    @Override
    public String tested() {
        return Hibernate6Mutations.TESTED;
    }

    @Override
    public SelectStatement translate(SessionFactoryImplementor sf, QueryImplementor<?> query,
            SqmSelectStatement<?> sqm) {
        return sf.getQueryEngine().getSqmTranslatorFactory().createSelectTranslator(sqm, query.getQueryOptions(),
                DomainParameterXref.from(sqm), query.getParameterBindings(),
                query.getSession().getLoadQueryInfluencers(), (SqlAstCreationContext) sf, true)
                .translate().getSqlAst();
    }
}
