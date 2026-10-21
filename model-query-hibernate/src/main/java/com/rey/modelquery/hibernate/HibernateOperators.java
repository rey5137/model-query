package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OperatorContext;
import com.rey.modelquery.core.OperatorFeature;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

/**
 * Filter operators whose SQL is a database's own, for {@code Filters.op} and {@code Having.op}. Each requires an
 * {@link OperatorFeature}, so where the target's vendor or Hibernate lacks it the filter is refused before any
 * statement ({@code MQ4015}, then {@code MQ4014}) rather than emulated or sent to a database that refuses it. Each
 * factory returns one cached instance, which may be kept in a constant.
 *
 * @implSpec api/12 R-FLT-20, vendor/40 R-VND-23, rfc/0017 §4, D-171, D-172, D-173
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

    // One instance per operator for every element type, cast in its factory: an array operator's value carries no
    // declared type, so it binds through the OperandBinder, typed by the column (D-169, D-170).
    private static final FilterOperator<Object[], Object[]> ARRAY_CONTAINS =
            pattern("arrayContains", HibernateOperatorFunctions.ARRAY_CONTAINS)
                    .requires(OperatorFeature.ARRAY_CONTAINMENT);

    private static final FilterOperator<Object[], Object[]> ARRAY_OVERLAPS =
            pattern("arrayOverlaps", HibernateOperatorFunctions.ARRAY_OVERLAPS)
                    .requires(OperatorFeature.ARRAY_CONTAINMENT);

    private static final FilterOperator<Object, String> JSONB_CONTAINS = FilterOperator.<Object, String>of(
                    "jsonbContains", String.class, (ctx, left, right) -> ctx.cb().isTrue(ctx.cb().function(
                            HibernateOperatorFunctions.JSONB_CONTAINS, Boolean.class, left, right)))
            .requires(OperatorFeature.JSONB_CONTAINMENT);

    /** A plain SQL identifier, optionally schema-qualified: nothing a literal could be broken out of (D-173). */
    private static final Pattern CONFIGURATION =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    /** One instance per configuration; configurations are constants of definitions, so the map stays small. */
    private static final Map<String, FilterOperator<String, String>> FULL_TEXT = new ConcurrentHashMap<>();

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

    /**
     * PostgreSQL's array containment, {@code col @> ?}: the column holds every element of the value; requires
     * {@link OperatorFeature#ARRAY_CONTAINMENT}. The value is bound typed by the column's mapping, so with no
     * {@code OperandBinder} it is {@code MQ1502}.
     */
    @SuppressWarnings("unchecked")
    public static <E> FilterOperator<E[], E[]> arrayContains() {
        return (FilterOperator<E[], E[]>) (FilterOperator<?, ?>) ARRAY_CONTAINS;
    }

    /**
     * PostgreSQL's array overlap, {@code col && ?}: the column and the value share an element; requires
     * {@link OperatorFeature#ARRAY_CONTAINMENT}. The value is bound as {@link #arrayContains()}'s is.
     */
    @SuppressWarnings("unchecked")
    public static <E> FilterOperator<E[], E[]> arrayOverlaps() {
        return (FilterOperator<E[], E[]>) (FilterOperator<?, ?>) ARRAY_OVERLAPS;
    }

    /**
     * PostgreSQL's {@code jsonb} containment, {@code col @> cast(? as jsonb)}, on a column of any attribute type;
     * requires {@link OperatorFeature#JSONB_CONTAINMENT}. The value is the JSON document as text, bound as a
     * {@code String} and never converted (R-FLT-21).
     */
    @SuppressWarnings("unchecked")
    public static <C> FilterOperator<C, String> jsonbContains() {
        return (FilterOperator<C, String>) (FilterOperator<?, ?>) JSONB_CONTAINS;
    }

    /**
     * PostgreSQL's full text, {@code to_tsvector('<configuration>', col) @@ plainto_tsquery('<configuration>', ?)},
     * on a {@code String} attribute; requires {@link OperatorFeature#FULL_TEXT}. The configuration is rendered as a
     * literal in both calls, so a {@code gin (to_tsvector('<configuration>', col))} index can serve the filter; the
     * value is the request's text, read as plain words that must all match (never tsquery syntax), bound as a
     * {@code String} and never converted (R-FLT-21). Text with no lexemes, blank or only stop words, matches no row,
     * so skip the filter for blank input. Equal configurations return the same instance: the configuration is a
     * constant of the definition, never request input, and each distinct one is kept for the JVM's life, so call
     * this once per configuration and keep the result in a constant.
     *
     * @throws ModelQueryDefinitionException {@code MQ1315} for a null, blank or other configuration than a plain,
     *     optionally schema-qualified SQL identifier ({@code english}, {@code public.my_config})
     * @implSpec api/12 AC-FLT-32, vendor/40 R-VND-23, D-173
     */
    public static FilterOperator<String, String> fullTextMatch(String configuration) {
        if (configuration == null || !CONFIGURATION.matcher(configuration).matches()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1315, "fullTextMatch(" + (configuration == null ? "null"
                    : "\"" + configuration + "\"") + "): the configuration must be a plain, optionally"
                    + " schema-qualified SQL identifier, such as english or public.my_config");
        }
        return FULL_TEXT.computeIfAbsent(configuration, HibernateOperators::fullTextOf);
    }

    private static FilterOperator<String, String> fullTextOf(String configuration) {
        return FilterOperator.<String, String>of("fullTextMatch(" + configuration + ")", String.class,
                        (ctx, left, right) -> ctx.cb().isTrue(ctx.cb().function(HibernateOperatorFunctions.TS_MATCH,
                                Boolean.class, ctx.cb().literal(configuration), left, right)))
                .requiringAttribute(String.class).requires(OperatorFeature.FULL_TEXT);
    }

    private static FilterOperator<Object[], Object[]> pattern(String name, String function) {
        return FilterOperator.<Object[]>of(name, (ctx, left, right) -> ctx.cb().isTrue(ctx.cb().function(function,
                Boolean.class, left, right)));
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
