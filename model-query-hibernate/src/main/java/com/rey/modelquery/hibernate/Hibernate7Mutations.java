package com.rey.modelquery.hibernate;

import java.lang.reflect.Method;
import org.hibernate.action.internal.BulkOperationCleanupAction;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.internal.DomainParameterXref;
import org.hibernate.query.sqm.internal.SqmJdbcExecutionContextAdapter;
import org.hibernate.query.sqm.internal.SqmUtil;
import org.hibernate.query.sqm.sql.SqmTranslation;
import org.hibernate.query.sqm.tree.SqmDmlStatement;
import org.hibernate.sql.ast.spi.SqlAstCreationContext;
import org.hibernate.sql.ast.tree.MutationStatement;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;

/**
 * Hibernate 7.x's mutation calls (R-WRT-50): the {@code SqlAstCreationContext} is the factory's SQL translation
 * engine, which 6.6's factory does not have, and {@code SqmUtil.createJdbcParameterBindings} dropped the mapping
 * metamodel and the from-clause access. Written and tested on 7.4.11.Final. Every {@code org.hibernate..internal..}
 * type this module uses lives here or in {@link Hibernate6Mutations} (R-WRT-50).
 *
 * @implSpec R-WRT-50, R-VND-17
 */
final class Hibernate7Mutations implements Mutations {

    /** The Hibernate version this adapter was tested on; another version on the line runs with one WARN. */
    static final String TESTED = "7.4.11.Final";

    /** This line's binding call, absent on 6.6, where {@link #probes} then refuses the adapter. */
    private static final Method CREATE_BINDINGS =
            HibernateMutations.method(SqmUtil.class, "createJdbcParameterBindings", 5);

    /** This line's {@code SqlAstCreationContext}, absent on 6.6, where the factory itself is one. */
    private static final Method TRANSLATION_ENGINE =
            HibernateMutations.getter(SessionFactoryImplementor.class, "getSqlTranslationEngine",
                    SqlAstCreationContext.class);

    @Override
    public boolean probes(SessionFactoryImplementor sf) {
        return CREATE_BINDINGS != null
                && HibernateMutations.method(DomainParameterXref.class, "from", 1) != null
                && HibernateMutations.method(SqmUtil.class, "generateJdbcParamsXref", 2) != null
                && HibernateMutations.method(BulkOperationCleanupAction.class, "schedule", 2) != null
                && HibernateMutations.method(
                                SqmJdbcExecutionContextAdapter.class, "omittingLockingAndPaging", 1)
                        != null
                && TRANSLATION_ENGINE != null;
    }

    @Override
    public String tested() {
        return TESTED;
    }

    @Override
    public Translation translate(SessionFactoryImplementor sf, SqmDmlStatement<?> dml, QueryOptions options,
            DomainQueryExecutionContext context) {
        DomainParameterXref xref = DomainParameterXref.from(dml);
        SqmTranslation<? extends MutationStatement> translation = sf.getQueryEngine().getSqmTranslatorFactory()
                .createMutationTranslator(dml, options, xref, context.getQueryParameterBindings(),
                        context.getSession().getLoadQueryInfluencers(), creationContext(sf))
                .translate();
        return new Translation(translation, bindings(translation, context, xref));
    }

    @Override
    public void invalidate(SharedSessionContractImplementor session, SqmDmlStatement<?> dml) {
        BulkOperationCleanupAction.schedule(session, dml);
    }

    @Override
    public ExecutionContext execution(DomainQueryExecutionContext context) {
        return SqmJdbcExecutionContextAdapter.omittingLockingAndPaging(context);
    }

    /** This line's {@code SqlAstCreationContext}, from the factory's SQL translation engine. */
    private static SqlAstCreationContext creationContext(SessionFactoryImplementor sf) {
        try {
            return (SqlAstCreationContext) TRANSLATION_ENGINE.invoke(sf);
        } catch (ReflectiveOperationException moved) {
            throw HibernateMutations.cannotTranslate(moved);
        }
    }

    /** This line's bindings, through the five-argument {@code createJdbcParameterBindings} (R-WRT-50). */
    private static JdbcParameterBindings bindings(SqmTranslation<? extends MutationStatement> translation,
            DomainQueryExecutionContext context, DomainParameterXref xref) {
        var jdbcParamsXref = SqmUtil.generateJdbcParamsXref(xref, translation::getJdbcParamsBySqmParam);
        try {
            return (JdbcParameterBindings) CREATE_BINDINGS.invoke(null, context.getQueryParameterBindings(), xref,
                    jdbcParamsXref, HibernateMutations.resolutions(translation), context.getSession());
        } catch (ReflectiveOperationException moved) {
            throw HibernateMutations.cannotTranslate(moved);
        }
    }
}
