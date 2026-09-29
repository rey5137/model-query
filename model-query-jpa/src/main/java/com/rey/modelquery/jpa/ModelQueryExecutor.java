package com.rey.modelquery.jpa;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfig;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Slice;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Runs model queries against a JPA {@code EntityManager}. An executor holds no state beyond its
 * {@code EntityManager}, so it is as thread-safe as that is.
 *
 * @param <E> the root entity type
 * @implSpec R-QRY-10, R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04, R-EXE-07, R-EXE-09
 */
@Incubating
public interface ModelQueryExecutor<E> {

    /**
     * An executor over {@code em} for queries rooted at {@code rootEntity}; enough to use the library without Spring
     * (INV-8).
     */
    static <E> ModelQueryExecutor<E> create(EntityManager em, Class<E> rootEntity, ModelQueryConfig config) {
        return new DefaultModelQueryExecutor<>(em, rootEntity, config);
    }

    /**
     * Runs {@code q} once and returns the mapped rows in order. {@code Limit.of(0)} returns an empty list without
     * querying (R-EXE-01).
     */
    <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit);

    /**
     * Reads one page of {@code q}: an exact total, a {@code hasNext} probe, or the total alone (R-EXE-02). An invalid
     * page never reaches here: {@link PageSpec} rejects it when built (R-EXE-06).
     */
    <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode);

    /**
     * The number of rows {@code q} returns: the number of groups for a grouped query (R-EXE-03), the number of
     * distinct roots when a to-many join would inflate it, and the number of rows when a selected column is read
     * through one (R-EXE-04).
     */
    long count(ModelQuery<E, ?, ?> q);

    /**
     * Passes the rows of {@code q} to {@code body} as a stream read one at a time, and closes the stream when
     * {@code body} returns or throws, so an early exit releases the connection too (R-EXE-07, R-EXE-09). No method
     * returns an open stream, so a caller cannot leak a result set. {@code Limit.of(0)} passes an empty stream without
     * querying (R-EXE-06). For a single pass over a large result inside one transaction; keyset {@code export} is the
     * default for very large ones (R-EXE-10).
     *
     * @param body reads the stream and returns the result; it must not let the stream escape
     */
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);
}
