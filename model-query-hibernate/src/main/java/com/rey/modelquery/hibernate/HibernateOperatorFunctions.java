package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.OperatorFeature;
import java.util.List;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.type.StandardBasicTypes;

/**
 * The boolean-valued {@code mq_*} pattern functions {@link HibernateOperators} render through, registered for
 * PostgreSQL's dialect and its subclasses only; on any other dialect none is, so
 * {@link HibernateProviderSupport#operators} answers their features {@code false} and the filter is {@code MQ4014}
 * before Hibernate could render an unknown function through to the database. Loaded by Hibernate from
 * {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}; not API. Identical on both lines.
 *
 * @implSpec vendor/40 R-VND-23, rfc/0017 §4
 */
public final class HibernateOperatorFunctions implements FunctionContributor {

    /** {@link OperatorFeature#REGEX_MATCH}'s function: PostgreSQL's case-sensitive {@code ~}. */
    static final String REGEX_MATCH = "mq_regex_match";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        if (!(contributions.getDialect() instanceof PostgreSQLDialect)) {
            return;
        }
        SqmFunctionRegistry registry = contributions.getFunctionRegistry();
        // Parenthesised so the operator binds as one predicate wherever Hibernate places the call.
        registry.patternDescriptorBuilder(REGEX_MATCH, "(?1 ~ ?2)")
                .setExactArgumentCount(2)
                .setInvariantType(contributions.getTypeConfiguration().getBasicTypeRegistry()
                        .resolve(StandardBasicTypes.BOOLEAN))
                .register();
    }

    /**
     * The {@code mq_*} functions a pattern {@code feature} renders through, all of which must be registered for the
     * provider to serve it; empty for {@code ILIKE}, an HQL predicate rather than a function, and any later feature.
     * The array, {@code jsonb} and full-text names are looked up before their functions are registered, so those
     * features answer {@code false} until they are.
     */
    static List<String> functions(OperatorFeature feature) {
        return switch (feature) {
            case REGEX_MATCH -> List.of(REGEX_MATCH);
            case ARRAY_CONTAINMENT -> List.of("mq_array_contains", "mq_array_overlaps");
            case JSONB_CONTAINMENT -> List.of("mq_jsonb_contains");
            case FULL_TEXT -> List.of("mq_ts_match");
            default -> List.of();
        };
    }
}
