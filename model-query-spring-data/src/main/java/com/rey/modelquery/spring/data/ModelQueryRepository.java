package com.rey.modelquery.spring.data;

import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A repository fragment that runs model queries rooted at the repository's entity: a repository extends it next to
 * {@code JpaRepository} or any other Spring Data interface and keeps its own {@code repositoryBaseClass} (R-SPR-12).
 * Its implementation is added by {@link ModelQueryRepositoryFactoryBean}; without it the repository fails at startup
 * (R-SPR-02). Every method delegates to the {@link ModelQueryExecutor} method of the same shape and adds no semantics
 * (R-SPR-01, INV-8).
 *
 * @param <E> the root entity type, the repository's domain type
 * @implSpec R-SPR-01, R-SPR-02, R-SPR-03, R-SPR-12
 */
@Incubating
public interface ModelQueryRepository<E> {

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
}
