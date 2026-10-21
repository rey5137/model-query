package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ConstantRenderer;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import com.rey.modelquery.core.OperandBinder;
import com.rey.modelquery.core.OperatorFeature;
import com.rey.modelquery.core.SequenceRenderer;
import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.core.SubqueryRenderer;
import com.rey.modelquery.core.TreatRenderer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a persistence provider can do better than portable JPA: detect the database without a connection, count
 * groups in the database, render null precedence natively, report a configured default null ordering, stream a
 * query's rows by cursor, name the tables an entity reads, write bulk inserts, change a managed entity and return the
 * rows a write changed. It varies by provider, not by database, so it is not part of {@link VendorProfile} (D-34,
 * D-108). Discovered with {@code ServiceLoader}; the first that {@linkplain #supports supports} a factory serves it.
 * Implementations are stateless and thread-safe.
 *
 * @implSpec R-VND-04, R-EXE-03, R-VND-12, R-VND-13, R-VND-14, R-VND-15, R-VND-17, R-VND-20, R-VND-23, R-VND-24,
 *     R-SRC-11, D-119, D-127, D-143
 */
@Incubating
public interface ProviderSupport {

    /** Whether this implementation serves {@code emf}'s persistence provider. */
    boolean supports(EntityManagerFactory emf);

    /** The database {@code emf} runs on, without opening a connection, or empty when not known (R-VND-05). */
    default Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * A query counting, in the database, the rows {@code groupedQuery} returns, or empty when this implementation
     * cannot build one; the executor then counts client-side (R-EXE-03). The executor runs the returned query itself,
     * so the configured query timeout applies to it (R-EXE-11).
     */
    default Optional<CriteriaQuery<Long>> countQuery(CriteriaQuery<?> groupedQuery) {
        return Optional.empty();
    }

    /** A native null-precedence renderer, or empty to render the portable form (R-COL-12). */
    default Optional<NullPrecedenceRenderer> nullPrecedence() {
        return Optional.empty();
    }

    /**
     * Where {@code emf}'s provider is configured to sort the NULLs of an order it renders without a null precedence,
     * overriding the database's default, such as Hibernate's {@code hibernate.order_by.default_null_ordering}:
     * {@code FIRST} or {@code LAST} in both directions, or empty when not configured, so the database's own default
     * applies. Keyset paging places a {@code DEFAULT}-precedence column's NULLs by it instead of by the profile's
     * {@code defaultAscendingNullOrdering()} (R-PAG-05, D-36).
     */
    default Optional<NullPrecedence> defaultNullPrecedence(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * Opens {@code query}, a query of this implementation's provider, as a stream that reads its rows from the database
     * as they are pulled, {@code fetchSize} at a time, the size the profile's {@code streamingFetchSize} chose. The
     * implementation opens the stream itself, because a provider may stream only through its own API: a portable
     * {@code getResultStream()} may read the whole result first. Closing the stream releases the cursor (R-VND-12,
     * D-108).
     */
    <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize);

    /**
     * Every table reading {@code entity} in {@code emf} touches, such as a joined supertable, a secondary table or a
     * subclass table, each unquoted and qualified with the configured default catalog and schema when it names none,
     * to be compared ignoring case; empty when it cannot tell, the default. A bulk write intersects them with the
     * root's to tell that a sub-query reads a table the write targets (R-VND-13, R-WRT-11, D-109).
     */
    default Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity) {
        return Set.of();
    }

    /**
     * The provider's bulk-insert support, or empty, the default, when it has none: a bulk insert then throws
     * {@code MQ4009} before the flush, while {@code persist} needs none (R-VND-14, D-117).
     */
    default Optional<InsertSupport> inserts() {
        return Optional.empty();
    }

    /**
     * The provider's support for an update {@code throughEntities()}, or empty, the default, when it has none: the
     * engine then writes each attribute through its metamodel member, which a bytecode-enhanced or woven entity's
     * change tracking does not see, and refuses a loaded proxy with {@code MQ2503} (api/14 R-WRT-42, D-119).
     */
    default Optional<EntityWriteSupport> entityWrites() {
        return Optional.empty();
    }

    /**
     * The provider's row-lock support, or empty, the default, when it renders none: a locked read then throws
     * {@code MQ4010} before any statement, the same fail-safe as {@link #inserts()} (R-VND-15, D-117, D-127).
     */
    default Optional<LockSupport> locks() {
        return Optional.empty();
    }

    /**
     * The provider's renderer of a column-typed constant's stored value, resolved from {@code emf}'s metamodel, or
     * empty, the default, when it has none: {@code Expr.constant(typedBy, value)} then throws {@code MQ1508}
     * (R-VND-16, R-COL-27).
     */
    default Optional<ConstantRenderer> constants(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * The provider's support for reading the rows a write statement changed, or empty, the default, when it has none:
     * a returning write then throws {@code MQ4011} at method entry, before any statement, the same fail-safe as
     * {@link #locks()} (R-WRT-50, R-VND-17).
     */
    default Optional<ReturningSupport> returning() {
        return Optional.empty();
    }

    /**
     * The provider's renderer of a sequence's next value, in the dialect {@code emf} uses, or empty, the default,
     * when it has none: {@code Expr.nextval} then throws {@code MQ1509} (R-VND-18, R-COL-28).
     */
    default Optional<SequenceRenderer> sequences(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * How, in {@code emf}, the path of the to-one attribute {@code toOne} of {@code entity} to its target's id renders
     * in a bulk statement: {@link ForeignKeyColumn#LOCAL_COLUMN} when as a column of the entity's own table, with no
     * join or formula, referencing the target's primary key; {@link ForeignKeyColumn#JOINS} when not;
     * {@link ForeignKeyColumn#UNKNOWN}, the default, when the provider cannot tell. A write that reads a to-one by its
     * id is refused with {@code MQ1004}, before any statement, unless it reports {@code LOCAL_COLUMN}
     * (R-COL-29, D-136).
     */
    @Incubating
    default ForeignKeyColumn foreignKeyColumn(EntityManagerFactory emf, Class<?> entity, String toOne) {
        return ForeignKeyColumn.UNKNOWN;
    }

    /**
     * The provider's own settings that boot a factory for {@code vendor} with no database: no JDBC metadata read at
     * boot, no schema generation, no connection provider of the unit's, and the dialect resolved from the target's
     * product name rather than a unit's explicit one. The portability dry run applies them over the unit's properties.
     * Empty, the default, when the provider cannot boot offline: the dry run then refuses the target with
     * {@code MQ4013} rather than booting it against a database (R-VND-20, R-INS-10).
     */
    @Incubating
    default Optional<Map<String, Object>> offlineProperties(DatabaseVendor vendor) {
        return Optional.empty();
    }

    /**
     * The name of {@code emf} the provider knows it by, which {@code MQ4012} quotes to say which factory is not on a
     * target vendor. Empty, the default, when the factory has no non-blank name: the message then names it by class
     * and identity, as JPA 3.1 gives a factory no name (R-VND-19, D-133).
     */
    @Incubating
    default Optional<String> factoryName(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * The provider's support for reading a query source, or empty, the default, when it has none: a query rooted at a
     * source then throws {@code MQ4014} at build time, before any statement, the same fail-safe as
     * {@link #inserts()} (R-SRC-11, D-143).
     */
    default Optional<SourceRenderer> sources() {
        return Optional.empty();
    }

    /**
     * The ordered scalar sub-query renderer, or empty by default: {@code Correlated.first} then throws
     * {@code MQ4014} at build time, before any statement (R-VND-25, R-COL-32).
     */
    @Incubating
    default Optional<SubqueryRenderer> subqueries() {
        return Optional.empty();
    }

    /**
     * Whether the factory's database renders a window function over the page statement (R-EXE-15). True only where
     * {@link #sources()} is present and its window renders {@code COUNT}; {@code false}, the default, makes every
     * {@code CountMode.INLINE_COUNT} page run {@code COUNT}'s statements (R-VND-27). Asked once per executor.
     */
    @Incubating
    default boolean inlineCounts(EntityManagerFactory emf) {
        return false;
    }

    /**
     * Whether the factory renders a criteria value given without a parameter as a literal, such as Hibernate's
     * {@code hibernate.criteria.value_handling_mode=inline}. {@code false}, the default, renders every filter value as
     * today; {@code true} binds each as a parameter, typed by its column's mapping through {@link #operands} or, with
     * none, only a mapping-independent value, the rest {@code MQ1502} (R-VND-28, api/12 R-FLT-08). Asked once per
     * executor.
     */
    @Incubating
    default boolean inlinesValues(EntityManagerFactory emf) {
        return false;
    }

    /** The factory's treated-path renderer, or empty: a treated node is then {@code MQ4014} (R-VND-26). */
    @Incubating
    default Optional<TreatRenderer> treats(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * The factory's operand binder, typing a filter operator's value by its column's mapping, or empty by default:
     * only a value whose binding doesn't depend on the mapping then binds, the rest {@code MQ1502} (R-VND-24,
     * api/12 R-FLT-21). Asked once per executor.
     */
    @Incubating
    default Optional<OperandBinder> operands(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * Whether the factory's provider can render {@code feature} for a filter operator that requires it, {@code false}
     * by default: the filter is then {@code MQ4014} before any statement, after the vendor's
     * {@code VendorProfile#operators} passed (R-VND-23, api/12 R-FLT-20). Asked once per executor and feature.
     */
    @Incubating
    default boolean operators(EntityManagerFactory emf, OperatorFeature feature) {
        return false;
    }
}
