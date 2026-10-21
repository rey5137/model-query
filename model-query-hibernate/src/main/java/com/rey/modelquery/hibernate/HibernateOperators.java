package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OperatorContext;
import com.rey.modelquery.core.OperatorFeature;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

/**
 * Filter operators whose SQL is a database's own, for {@code Filters.op} and {@code Having.op}. Each requires an
 * {@link OperatorFeature}, so where the target's vendor or Hibernate lacks it the filter is refused before any
 * statement ({@code MQ4015}, then {@code MQ4014}) rather than emulated or sent to a database that refuses it. Each
 * factory returns one cached instance, which may be kept in a constant.
 *
 * @implSpec api/12 R-FLT-20, vendor/40 R-VND-23, rfc/0017 §4, D-171
 */
@Incubating
public final class HibernateOperators {

    private static final Map<LikeMode, FilterOperator<String, String>> ILIKE = new EnumMap<>(LikeMode.class);

    static {
        for (LikeMode mode : LikeMode.values()) {
            ILIKE.put(mode, ilikeOf(mode));
        }
    }

    private static final FilterOperator<String, String> REGEX_MATCH = FilterOperator.<String>of("regexMatch",
                    (ctx, left, right) -> ctx.cb().isTrue(ctx.cb().function(HibernateOperatorFunctions.REGEX_MATCH,
                            Boolean.class, left, right)))
            .requiringAttribute(String.class).requires(OperatorFeature.REGEX_MATCH);

    private HibernateOperators() {
    }

    /**
     * Case-insensitive {@code col ILIKE ? ESCAPE '\'}, through {@code HibernateCriteriaBuilder#ilike}, on a
     * {@code String} attribute; requires {@link OperatorFeature#ILIKE}: native on PostgreSQL and H2, refused
     * elsewhere rather than emulated as {@code lower(col) like lower(?)}. The value is prepared as {@code like}'s is
     * (R-FLT-06): escaped and wrapped for every mode but {@code EXACT}, which passes no escape character, as
     * {@code like}'s doesn't, so its {@code \} is literal; on a converted column, from the attribute value
     * (R-FLT-21). In the field form the right column is the pattern as stored, its {@code \} an escape for every mode
     * but {@code EXACT}.
     */
    public static FilterOperator<String, String> ilike(LikeMode mode) {
        return ILIKE.get(Objects.requireNonNull(mode, "mode"));
    }

    /**
     * PostgreSQL's case-sensitive regular-expression match, {@code col ~ ?}, on a {@code String} attribute; requires
     * {@link OperatorFeature#REGEX_MATCH}. The value is the expression, unprepared.
     */
    public static FilterOperator<String, String> regexMatch() {
        return REGEX_MATCH;
    }

    private static FilterOperator<String, String> ilikeOf(LikeMode mode) {
        return FilterOperator.<String>of("ilike(" + mode + ")", (ctx, left, right) -> mode == LikeMode.EXACT
                        ? hibernate(ctx).ilike(left, right)
                        : hibernate(ctx).ilike(left, right, LikeMode.escapeCharacter()))
                .requiringAttribute(String.class).requires(OperatorFeature.ILIKE).preparing(mode::pattern);
    }

    /** The statement's builder as Hibernate's, which only a provider check answering wrongly would not give. */
    private static HibernateCriteriaBuilder hibernate(OperatorContext ctx) {
        if (ctx.cb() instanceof HibernateCriteriaBuilder hibernate) {
            return hibernate;
        }
        throw new ModelQueryConfigurationException(MqCode.MQ4014, "ilike needs Hibernate's criteria builder, not "
                + ctx.cb().getClass().getName() + "; ProviderSupport#operators(ILIKE) answered true for another"
                + " provider");
    }
}
