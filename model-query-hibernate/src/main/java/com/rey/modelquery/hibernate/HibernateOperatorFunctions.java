package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.EngineFacing;
import com.rey.modelquery.core.OperatorFeature;
import java.util.List;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.function.array.ElementViaArrayArgumentReturnTypeResolver;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.type.StandardBasicTypes;

/**
 * The boolean-valued {@code mq_*} pattern functions {@link HibernateOperators} render through, registered for
 * PostgreSQL's dialect and its subclasses only; on any other dialect none is, so
 * {@link HibernateProviderSupport#operators} answers their features {@code false} and the filter is {@code MQ4014}
 * before Hibernate could render an unknown function through to the database. Loaded by Hibernate from
 * {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}; not API. Identical on both lines.
 *
 * @implSpec vendor/40 R-VND-23, rfc/0017 §4, D-172, D-173
 */
@EngineFacing
public final class HibernateOperatorFunctions implements FunctionContributor {

    /** {@link OperatorFeature#REGEX_MATCH}'s function: PostgreSQL's case-sensitive {@code ~}. */
    static final String REGEX_MATCH = "mq_regex_match";
    /** {@link OperatorFeature#ARRAY_CONTAINMENT}'s functions: the column holds every element of the value. */
    static final String ARRAY_CONTAINS = "mq_array_contains";
    /** The column and the value share an element. */
    static final String ARRAY_OVERLAPS = "mq_array_overlaps";
    /** {@link OperatorFeature#JSONB_CONTAINMENT}'s function: the value, passed as its text, cast to {@code jsonb}. */
    static final String JSONB_CONTAINS = "mq_jsonb_contains";
    /**
     * {@link OperatorFeature#FULL_TEXT}'s function: the configuration, a literal, then the column and the request
     * text; the one configuration argument feeds both calls, so the expression index's form always matches.
     */
    static final String TS_MATCH = "mq_ts_match";

    /**
     * {@code Agg.firstBy}'s function: the first element of an ordered {@code array_agg}, registered on PostgreSQL
     * ({@code (?1)[1]}) and H2 ({@code ?1[1]}) only. Hibernate's {@code array_get} renders {@code array_agg(...)[?]}
     * without the parentheses PostgreSQL needs, and on H2 aggregates twice (spike B.1).
     */
    static final String FIRST_ELEMENT = "mq_first_element";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        if (contributions.getDialect() instanceof H2Dialect) {
            registerFirstElement(contributions, "?1[1]");
        }
        if (!(contributions.getDialect() instanceof PostgreSQLDialect)) {
            return;
        }
        registerFirstElement(contributions, "(?1)[1]");
        // Each parenthesised so the operator binds as one predicate wherever Hibernate places the call.
        register(contributions, REGEX_MATCH, "(?1 ~ ?2)");
        // Not Hibernate's array_includes / array_overlaps: it registers those on H2 too, where they fail or emulate.
        register(contributions, ARRAY_CONTAINS, "(?1 @> ?2)");
        register(contributions, ARRAY_OVERLAPS, "(?1 && ?2)");
        register(contributions, JSONB_CONTAINS, "(?1 @> cast(?2 as jsonb))");
        // plainto_tsquery reads the text as words, never as tsquery syntax (D-173).
        register(contributions, TS_MATCH, "(to_tsvector(?1, ?2) @@ plainto_tsquery(?1, ?3))", 3);
    }

    /** Typed as the array's element by Hibernate's own resolver, so a converted element reads through its converter. */
    private static void registerFirstElement(FunctionContributions contributions, String pattern) {
        contributions.getFunctionRegistry().patternDescriptorBuilder(FIRST_ELEMENT, pattern)
                .setExactArgumentCount(1)
                .setReturnTypeResolver(ElementViaArrayArgumentReturnTypeResolver.DEFAULT_INSTANCE)
                .register();
    }

    private static void register(FunctionContributions contributions, String name, String pattern) {
        register(contributions, name, pattern, 2);
    }

    private static void register(FunctionContributions contributions, String name, String pattern, int arguments) {
        SqmFunctionRegistry registry = contributions.getFunctionRegistry();
        registry.patternDescriptorBuilder(name, pattern)
                .setExactArgumentCount(arguments)
                .setInvariantType(contributions.getTypeConfiguration().getBasicTypeRegistry()
                        .resolve(StandardBasicTypes.BOOLEAN))
                .register();
    }

    /**
     * The {@code mq_*} functions a pattern {@code feature} renders through, all of which must be registered for the
     * provider to serve it; empty for {@code ILIKE}, an HQL predicate rather than a function, and any later feature.
     */
    static List<String> functions(OperatorFeature feature) {
        return switch (feature) {
            case REGEX_MATCH -> List.of(REGEX_MATCH);
            case ARRAY_CONTAINMENT -> List.of(ARRAY_CONTAINS, ARRAY_OVERLAPS);
            case JSONB_CONTAINMENT -> List.of(JSONB_CONTAINS);
            case FULL_TEXT -> List.of(TS_MATCH);
            default -> List.of();
        };
    }
}
