package com.rey.modelquery.hibernate;

import java.lang.reflect.Method;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.spi.QueryImplementor;
import org.hibernate.query.sqm.internal.DomainParameterXref;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.sql.ast.spi.SqlAstCreationContext;
import org.hibernate.sql.ast.tree.select.SelectStatement;

/**
 * Hibernate 7.4's select translation for the statement preflight (R-VND-26): the {@code SqlAstCreationContext} is the
 * factory's SQL translation engine, which 6.6's factory does not have, so it is reached reflectively and the module
 * compiles on either line. Written and tested on 7.4.11.Final; {@link Hibernate6Preflight} is the 6.6 line's.
 *
 * @implSpec R-VND-26
 */
final class Hibernate7Preflight implements PreflightTranslation {

    /** This line's {@code SqlAstCreationContext}, absent on 6.6, where {@link #probes} then refuses the adapter. */
    private static final Method TRANSLATION_ENGINE = HibernateMutations.getter(SessionFactoryImplementor.class,
            "getSqlTranslationEngine", SqlAstCreationContext.class);

    @Override
    public boolean probes(SessionFactoryImplementor sf) {
        return TRANSLATION_ENGINE != null && HibernateMutations.method(DomainParameterXref.class, "from", 1) != null;
    }

    @Override
    public String tested() {
        return Hibernate7Mutations.TESTED;
    }

    @Override
    public SelectStatement translate(SessionFactoryImplementor sf, QueryImplementor<?> query,
            SqmSelectStatement<?> sqm) {
        SqlAstCreationContext context;
        try {
            context = (SqlAstCreationContext) TRANSLATION_ENGINE.invoke(sf);
        } catch (ReflectiveOperationException moved) {
            throw new IllegalStateException("the SQL translation engine is unreachable: " + moved, moved);
        }
        return sf.getQueryEngine().getSqmTranslatorFactory().createSelectTranslator(sqm, query.getQueryOptions(),
                DomainParameterXref.from(sqm), query.getParameterBindings(),
                query.getSession().getLoadQueryInfluencers(), context, true)
                .translate().getSqlAst();
    }
}
