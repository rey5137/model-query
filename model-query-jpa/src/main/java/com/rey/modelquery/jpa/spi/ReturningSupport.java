package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import java.util.List;

/**
 * What a returning write needs of the persistence provider, which portable JPA has no API for: the rows a write
 * statement changed, read from the statement itself. Built from {@code jakarta.persistence} types only (INV-7),
 * apart from the core query it is handed. Reached through {@link ProviderSupport#returning()}; a returning write on a
 * factory with none, or with one that does not render this entity in this shape, throws {@code MQ4011} at method
 * entry, before any statement. Implementations are stateless and thread-safe (R-WRT-50, R-VND-17).
 *
 * @implSpec R-WRT-50, R-VND-17, D-132
 */
@Incubating
public interface ReturningSupport {

    /**
     * Whether {@link #rows} would return {@code entity}'s written rows on {@code emf}'s provider in {@code shape}: a
     * provider that would fall back to a plain write, that renders the shape on a translation it cannot reach, or
     * whose line's translation calls are missing answers false, so the engine refuses the write with {@code MQ4011}
     * instead of running it without its rows (R-WRT-50).
     */
    boolean rendered(EntityManagerFactory emf, Class<?> entity, ReturningShape shape);

    /**
     * Checks {@code attributes}, the returning query's columns, against the table {@code entity}'s write statement
     * writes on {@code emf}'s provider, before the flush and any statement (R-WRT-50, INV-5, D-132): the engine calls
     * it once {@link #rendered} has answered true. An implementation refuses here what {@link #rows} would refuse.
     *
     * <p>The default checks nothing, leaving the refusal to {@link #rows}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1809} for a column the implementation cannot express on the
     *     written table
     */
    @Incubating
    default void check(EntityManagerFactory emf, Class<?> entity, List<String> attributes) {
    }

    /**
     * The rows {@code statement} wrote, as the returning query's columns: one {@code Object[]} per row, each holding
     * the value of {@code attributes}' column in that order, as the entity holds it, before the column's converter,
     * exactly as a tuple of a read query holds it. {@code statement} is the write's own statement, its parameters
     * bound and its timeout applied; the implementation runs it through the provider's own machinery, so its SQL
     * logging, its statement inspector, its parameter binders and its query-space invalidation are unchanged. Row
     * order is unspecified. A to-one's id is the written row's foreign key. An implementation refuses with
     * {@code MQ1809} a column it cannot express on the written table (R-WRT-50).
     */
    List<Object[]> rows(Query statement, Class<?> entity, List<String> attributes, ReturningShape shape);
}
