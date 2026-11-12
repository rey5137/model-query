package com.rey.modelquery.spring.data;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.jpa.ModelQueryConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * What a {@link ModelQueryExecutorDecorator} is told about the repository whose executor it wraps: the values the
 * repository built that executor from (R-SPR-18).
 *
 * @param <E> the repository's root entity
 * @implSpec R-SPR-18, D-223
 */
@Incubating
public interface ExecutorContext<E> {

    /** The repository's domain type, the {@code E} of its {@code ModelQueryRepository<E>}. */
    Class<E> rootEntity();

    /** The shared, transaction-bound {@code EntityManager} the repository's executor runs on. */
    EntityManager entityManager();

    /** The factory of {@link #entityManager()}. */
    EntityManagerFactory entityManagerFactory();

    /** The executor's config: the context's {@code ModelQueryConfig}, after its {@code ModelQueryConfigurer}. */
    ModelQueryConfig config();
}
