package com.rey.modelquery.spring.data;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.SortSpec;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A repository fragment that runs model queries rooted at the repository's entity: a repository extends it next to
 * {@code JpaRepository} or any other Spring Data interface and keeps its own {@code repositoryBaseClass} (R-SPR-12).
 * Its implementation is added by {@link ModelQueryRepositoryFactoryBean}; without it the repository fails at startup
 * (R-SPR-02). Every method delegates to the {@link ModelQueryExecutor} method of the same shape and adds no semantics
 * (R-SPR-01, INV-8).
 *
 * @param <E> the root entity type, the repository's domain type
 * @implSpec R-SPR-01, R-SPR-02, R-SPR-03, R-SPR-10, R-SPR-12, R-SPR-14
 */
@Incubating
public interface ModelQueryRepository<E> {

    /**
     * {@link ModelQueryExecutor#page(ModelQuery, PageSpec, CountMode)} at {@code pageable}'s offset and size; a
     * sorted {@code pageable} first replaces {@code q}'s {@code orderBy} through
     * {@link ModelQuery#orderedBy(SortSpec)}, and an unsorted one keeps it (R-SPR-04, R-SPR-05). The totals are
     * {@code null} under {@link CountMode#NO_COUNT} (R-SPR-07). Keyset paging is not offered here (D-51).
     *
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2001} for {@link Pageable#unpaged()},
     *     {@code MQ2002} for an offset beyond {@code int}, and {@code MQ2301} before any query runs: for a sort
     *     property that matches no selected column or different ones, or that asks {@code ignoreCase()}, for a sort
     *     on an ungrouped query without a primary key, and, with the build failure as cause, for a sorted copy that
     *     fails the checks of {@code build()} (R-SPR-06, {@link ModelQuery#orderedBy(SortSpec)})
     */
    <M> ModelPage<M> findPage(ModelQuery<E, ?, M> q, Pageable pageable, CountMode mode);

    /**
     * {@link ModelQueryExecutor#page(ModelQuery, KeysetSpec)} with {@code q}'s order replaced by {@code sort}'s:
     * {@link Sort#unsorted()} keeps the definition's order, and a sorted {@code sort} applies through
     * {@link ModelQuery#orderedBy(SortSpec)} (R-SPR-04). Either way the effective order decides the cursor's
     * fingerprint ({@code engine/21} R-PAG-19), so a cursor is only understood with the {@code Sort} that issued it.
     * The call adds no semantics of its own (R-SPR-01, R-SPR-14).
     *
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2207} for a query without
     *     {@code keyset()}, {@code MQ2204} for a selection read through a to-many join, {@code MQ2206} for a
     *     {@code primaryKeyFirst(...)} query whose customizer narrows the phases differently, {@code MQ2210} for a
     *     key column no codec carries, {@code MQ2209} for a cursor of another order, {@code MQ2208} for a decoded
     *     value of the wrong type or a NULL in a refusing or primary-key column, all before any query runs; after
     *     reading, {@code MQ2202} for a NULL in a refusing keyset column and {@code MQ2205} for the cursor's own key
     */
    @Incubating
    <M> KeysetSlice<M> findKeysetPage(ModelQuery<E, ?, M> q, KeysetSpec keyset, Sort sort);

    /** {@link ModelQueryExecutor#list(ModelQuery, Limit)}. */
    <M> List<M> findAll(ModelQuery<E, ?, M> q, Limit limit);

    /** {@link ModelQueryExecutor#count(ModelQuery)}. */
    long count(ModelQuery<E, ?, ?> q);

    /**
     * {@link ModelQueryExecutor#stream(ModelQuery, Limit, Function)} inside a read-only transaction of the
     * repository's own transaction manager, opened when none is active and joined otherwise; {@code body} runs, and
     * the stream closes, before that transaction ends (R-SPR-03).
     */
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);

    /** {@link ModelQueryExecutor#export(ModelQuery, ExportOptions, Function, Consumer)}. */
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options, Function<List<M>, List<S>> pageTransformer,
            Consumer<S> sink);

    /**
     * {@link ModelQueryExecutor#update(ModelUpdate)} in a transaction of the repository's own transaction manager,
     * joined when one is active and opened otherwise, as the modifying methods of {@code SimpleJpaRepository} run. A
     * {@code commitEachChunk()} write opens none: each of its chunks commits on its own through the config's
     * {@code ChunkTransactions}, so it is meant to be called outside a transaction (R-SPR-10, R-WRT-19).
     */
    @Incubating
    long update(ModelUpdate<E, ?> u);

    /** {@link ModelQueryExecutor#delete(ModelDelete)}, in a transaction as {@link #update} runs (R-SPR-10). */
    @Incubating
    long delete(ModelDelete<E, ?> d);

    /**
     * {@link ModelQueryExecutor#insert(ModelInsert)} in a transaction as {@link #update} runs: joined when one is
     * active, opened otherwise, and none for a {@code commitEachChunk()} insert-select (R-SPR-10, R-WRT-18).
     */
    @Incubating
    long insert(ModelInsert<E, ?> i);

    /**
     * {@link ModelQueryExecutor#insertReturningKeys(ValuesInsert)}, in a transaction joined or opened as
     * {@link #update} runs; its keys are drawn and returned inside it (R-SPR-10, R-WRT-33).
     */
    @Incubating
    <K> List<K> insertReturningKeys(ValuesInsert<E, K, ?> i);

    /**
     * {@link ModelQueryExecutor#persist(ModelPersist)}, in a transaction joined or opened as {@link #update} runs,
     * which {@code persist} needs: outside one the executor throws {@code MQ2501} (R-SPR-10, R-WRT-39).
     */
    @Incubating
    <K> K persist(ModelPersist<E, K, ?> p);

    /**
     * {@link ModelQueryExecutor#persist(ModelPersist, ModelQuery)}, in one transaction joined or opened as
     * {@link #persist(ModelPersist)} runs; the model is built inside it, before the entity is detached (R-SPR-10,
     * R-WRT-48).
     */
    @Incubating
    <R> R persist(ModelPersist<E, ?, ?> persist, ModelQuery<E, ?, R> returning);
}
