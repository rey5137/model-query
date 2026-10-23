package com.rey.modelquery.spike.rfc0020;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.dialect.function.array.ElementViaArrayArgumentReturnTypeResolver;

/**
 * B.1's workaround for Hibernate's {@code array_get} rendering: {@code rfc0020_first(a)} renders {@code (a)[1]}, the
 * parenthesised subscript PostgreSQL requires after an aggregate, typed as the array's element (as Hibernate's own
 * {@code array_get} is), the way {@code HibernateOperatorFunctions} registers its {@code mq_*} patterns.
 */
public final class SpikeFunctions implements FunctionContributor {

    static final String FIRST = "rfc0020_first";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        contributions.getFunctionRegistry().patternDescriptorBuilder(FIRST, "(?1)[1]")
                .setExactArgumentCount(1)
                .setReturnTypeResolver(ElementViaArrayArgumentReturnTypeResolver.DEFAULT_INSTANCE)
                .register();
    }
}
