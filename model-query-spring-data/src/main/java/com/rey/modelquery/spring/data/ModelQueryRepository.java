package com.rey.modelquery.spring.data;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.SortSpec;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;

/**
 * A repository fragment that runs model queries rooted at the repository's entity: a repository extends it next to
 * {@code JpaRepository} or any other Spring Data interface and keeps its own {@code repositoryBaseClass} (R-SPR-12).
 * Its implementation is added by {@link ModelQueryRepositoryFactoryBean}; without it the repository fails at startup
 * (R-SPR-02). Every method delegates to the {@link ModelQueryExecutor} method of the same shape and adds no semantics
 * (R-SPR-01, INV-8).
 *
 * @param <E> the root entity type, the repository's domain type
 * @implSpec R-SPR-01, R-SPR-02, R-SPR-03, R-SPR-10, R-SPR-12
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
    long update(ModelUpdate<E, ?> u);

    /** {@link ModelQueryExecutor#delete(ModelDelete)}, in a transaction as {@link #update} runs (R-SPR-10). */
    long delete(ModelDelete<E, ?> d);
}
