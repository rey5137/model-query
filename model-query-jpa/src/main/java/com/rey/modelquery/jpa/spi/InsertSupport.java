package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What a bulk insert needs of the persistence provider, which portable JPA has no API for: the root's generator, keys
 * drawn from it ahead of the statement, and the insert statements themselves, built from {@code jakarta.persistence}
 * types only (INV-7). Reached through {@link ProviderSupport#inserts()}; a bulk insert on a factory with none throws
 * {@code MQ4009} before the flush, while {@code persist} needs none. Implementations are stateless and thread-safe
 * (R-VND-14, D-117).
 *
 * @implSpec R-VND-14, D-116, D-117, D-130
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
     * The sets of {@code entity}'s attributes its mapping declares unique: the id, the natural id, each unique column
     * and each unique constraint whose columns are all attributes' columns. An attribute is named as a model column
     * names it, dotted through embeddables and a to-one by its own name. The engine holds a conflict clause's columns
     * against them on its first execution: a set of columns that is none of them is {@code MQ1804} (R-WRT-34).
     */
    List<Set<String>> uniqueKeys(EntityManagerFactory emf, Class<?> entity);

    /**
     * An insert of {@code source}'s rows into {@code entity}: each tuple's elements, in order, are written to
     * {@code attributes}. {@code constants} holds the values of {@code source}'s named parameters by name, in the
     * order of the attributes they are written to, the last {@code constants.size()} of {@code attributes}; the
     * implementation binds each as its attribute's type, so a converted or enumerated value is written through the
     * attribute's mapping. The engine runs the returned query with {@code executeUpdate()}, after applying the query
     * timeout, as for an update or delete (R-WRT-27, R-WRT-30).
     */
    <E> Query insertSelect(EntityManager em, Class<E> entity, List<String> attributes, CriteriaQuery<Tuple> source,
            Map<String, Object> constants);

    /**
     * An insert of {@code rows} into {@code entity}, one statement: each row's values, in order, are written to
     * {@code attributes}, with {@code conflict}'s clause when present. The values are attribute values, a
     * {@code null} included, and an attribute is dotted through embeddables, a to-one's ending in its target's id
     * ({@code customer.id}); the implementation binds each value as a parameter, never inlined. The engine runs the
     * returned query with {@code executeUpdate()} (R-WRT-29, R-WRT-30, R-WRT-34).
     */
    <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes, List<List<Object>> rows,
            Optional<ConflictClause<E>> conflict);

    /**
     * As {@link #insertValues(EntityManager, Class, List, List, Optional)}, each row also writing each of
     * {@code computed}'s attributes, after {@code attributes}, the expression its factory returns for the statement's
     * criteria builder: an expression that reads no column and binds nothing, such as a sequence's next value, so the
     * engine counts no bind for it. The implementation calls each factory once per row, so no Criteria node is shared
     * between rows and a sequence gives each row its own value (R-WRT-53, D-130).
     *
     * <p>The default delegates to the five-argument form when {@code computed} is empty and otherwise throws
     * {@code MQ1613} instead of building the statement: an implementation written before this method refuses a
     * computed value rather than dropping it. The engine, seeing the default in place, refuses such an insert with
     * {@code MQ1613} itself before the flush and any statement.
     *
     * @throws ModelQueryDefinitionException {@code MQ1613} by default, for a non-empty {@code computed}
     */
    @Incubating
    default <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
            List<List<Object>> rows, Map<String, Function<CriteriaBuilder, Expression<?>>> computed,
            Optional<ConflictClause<E>> conflict) {
        if (!computed.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1613, entity.getSimpleName() + ": setExpression(...) on "
                    + computed.keySet() + ", which this provider's InsertSupport (" + getClass().getName()
                    + ") cannot render in an insert's VALUES");
        }
        return insertValues(em, entity, attributes, rows, conflict);
    }
}
