package com.rey.modelquery.spring.data;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.jpa.ModelQueryExecutor;

/**
 * Wraps the executor of each {@link ModelQueryRepository} in the context, for recording, metrics or tracing. Every
 * decorator bean is applied to every repository's executor on the repository's first call, not when the repository is
 * created, in {@code @Order} / {@code Ordered} order: the first decorator wraps the executor itself, so it is the
 * innermost, and the last is the one the repository calls (R-SPR-18).
 *
 * <p>A decorator delegates every call to the executor it was given and rethrows the delegate's throwable unchanged, so
 * Spring's exception translation and the application see what they see without it; the repository adds no wrapping of
 * its own. Only repository executors are decorated: an executor the application creates with
 * {@link ModelQueryExecutor#create} is the application's to wrap.
 *
 * <p>The method is generic, so a lambda cannot implement it (JLS 15.27.3), and the interface is not a functional
 * interface; its signature keeps a decorator from returning an executor of another root.
 *
 * @implSpec R-SPR-18, D-223
 */
@Incubating
public interface ModelQueryExecutorDecorator {

    /**
     * The executor the repository of {@code context.rootEntity()} calls in place of {@code executor}.
     *
     * @param <E>      the repository's root entity
     * @param executor the executor to wrap: the repository's own, or the previous decorator's result
     * @param context  the repository's root, {@code EntityManager} and config
     * @return the executor to call, {@code executor} to leave it as it is; never {@code null}
     */
    <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context);
}
