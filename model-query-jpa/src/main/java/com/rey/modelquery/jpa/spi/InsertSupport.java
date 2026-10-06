package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.List;
import java.util.Optional;

/**
 * What a bulk insert needs of the persistence provider, which portable JPA has no API for: the root's generator, keys
 * drawn from it ahead of the statement, and the insert statements themselves, built from {@code jakarta.persistence}
 * types only (INV-7). Reached through {@link ProviderSupport#inserts()}; a bulk insert on a factory with none throws
 * {@code MQ4009} before the flush, while {@code persist} needs none. Implementations are stateless and thread-safe
 * (R-VND-14, D-117).
 *
 * @implSpec R-VND-14, D-116, D-117
 */
@Incubating
public interface InsertSupport {

    /**
     * How the provider generates {@code entity}'s id in {@code emf}, and the mappings of {@code entity} no bulk insert
     * writes. The engine checks an insert against them on its first execution: the generator allowlist of api/14
     * R-WRT-26 and those mappings are {@code MQ1805}, an id the model names against its generator {@code MQ1802}.
     */
    InsertTarget target(EntityManagerFactory emf, Class<?> entity);

    /**
     * {@code count} keys drawn from {@code entity}'s generator in {@code em}'s session, in order: the keys an
     * insert-values writes as its rows' ids, so they are known before the statement (R-WRT-26, R-WRT-33). Called only
     * for a {@link IdGeneration.Sequence}, {@link IdGeneration.Table} or {@link IdGeneration.Uuid} generator.
     */
    List<Object> generateKeys(EntityManager em, Class<?> entity, int count);

    /**
     * Whether the provider renders a conflict clause's {@code doNothing} for {@code emf}'s database; where it does not
     * (Hibernate 6 on a {@code MERGE} vendor writes a plain insert) the engine refuses it with {@code MQ1804} on first
     * execution (R-WRT-34, D-116).
     */
    boolean doNothingRendered(EntityManagerFactory emf);

    /**
     * The bind parameters the provider adds to each row of an insert of {@code entity}, beyond its columns, such as
     * the {@code @Version} seed; the engine counts them when it sizes a statement's rows (R-WRT-29, D-80).
     */
    int providerBindsPerRow(EntityManagerFactory emf, Class<?> entity);

    /**
     * An insert of {@code source}'s rows into {@code entity}: each tuple's elements, in order, are written to
     * {@code attributes}. The engine runs the returned query with {@code executeUpdate()}, after applying the query
     * timeout, as for an update or delete (R-WRT-27).
     */
    <E> Query insertSelect(EntityManager em, Class<E> entity, List<String> attributes, CriteriaQuery<Tuple> source);

    /**
     * An insert of {@code rows} into {@code entity}, one statement: each row's values, in order, are written to
     * {@code attributes}, with {@code conflict}'s clause when present. The values are attribute values, a
     * {@code null} included, and an attribute is dotted through embeddables, a to-one's ending in its target's id
     * ({@code customer.id}); the implementation binds each value as a parameter, never inlined. The engine runs the
     * returned query with {@code executeUpdate()} (R-WRT-29, R-WRT-30, R-WRT-34).
     */
    <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes, List<List<Object>> rows,
            Optional<ConflictClause<E>> conflict);
}
