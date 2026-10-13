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
 * Hibernate 6.6's mutation calls (R-WRT-50): the session factory is itself the {@code SqlAstCreationContext}, and
 * {@code SqmUtil.createJdbcParameterBindings} takes the mapping metamodel and the from-clause access before its
 * resolution access. Written and tested on 6.6.58.Final. Every {@code org.hibernate..internal..} type this module uses
 * lives here or in {@link Hibernate7Mutations} (R-WRT-50).
 *
 * @implSpec R-WRT-50, R-VND-17
 */
final class Hibernate6Mutations implements Mutations {

    /** The Hibernate version this adapter was tested on; another version on the line runs with one WARN. */
    static final String TESTED = "6.6.58.Final";

    /** This line's binding call, which 7.x replaced: {@link #probes} is false where it is missing. */
    private static final Method CREATE_BINDINGS =
            HibernateMutations.method(SqmUtil.class, "createJdbcParameterBindings", 7);

    @Override
    public boolean probes(SessionFactoryImplementor sf) {
        return CREATE_BINDINGS != null
                && HibernateMutations.method(DomainParameterXref.class, "from", 1) != null
                && HibernateMutations.method(SqmUtil.class, "generateJdbcParamsXref", 2) != null
                && HibernateMutations.method(BulkOperationCleanupAction.class, "schedule", 2) != null
                && HibernateMutations.method(
                                SqmJdbcExecutionContextAdapter.class, "omittingLockingAndPaging", 1)
                        != null
                && sf instanceof SqlAstCreationContext;
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
                        context.getSession().getLoadQueryInfluencers(), (SqlAstCreationContext) sf)
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

    /** This line's bindings, through the seven-argument {@code createJdbcParameterBindings} (R-WRT-50). */
    private static JdbcParameterBindings bindings(SqmTranslation<? extends MutationStatement> translation,
            DomainQueryExecutionContext context, DomainParameterXref xref) {
        var jdbcParamsXref = SqmUtil.generateJdbcParamsXref(xref, translation::getJdbcParamsBySqmParam);
        try {
            return (JdbcParameterBindings) CREATE_BINDINGS.invoke(null, context.getQueryParameterBindings(), xref,
                    jdbcParamsXref, context.getSession().getFactory().getRuntimeMetamodels().getMappingMetamodel(),
                    HibernateMutations.tableGroupAccess(translation), HibernateMutations.resolutions(translation),
                    context.getSession());
        } catch (ReflectiveOperationException moved) {
            throw HibernateMutations.cannotTranslate(moved);
        }
    }
}
