package com.rey.modelquery.jpa;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfig;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Slice;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Runs model queries against a JPA {@code EntityManager}. An executor holds no state beyond its
 * {@code EntityManager}, so it is as thread-safe as that is. The first execution of a {@code ModelQuery}, by any
 * executor, checks that its customizer narrows every phase alike (R-QRY-09, D-21).
 *
 * @param <E> the root entity type
 * @implSpec R-QRY-10, R-QRY-09, R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04, R-EXE-07, R-EXE-09, R-PAG-01, R-PAG-02,
 *     R-PAG-03, R-PAG-09, R-PAG-10, R-PAG-13
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

    /**
     * Visits every row of {@code q} exactly once, one page of {@code options.pageSize()} rows at a time, with memory
     * bounded by one page (INV-4). Offset pages are read in a stable order, the primary key appended to the query's
     * order (R-PAG-01). A row whose key the previous page or the same page already held is dropped: with a stable
     * order a row can repeat only across a page boundary, or within a page through a to-many join used only by
     * predicates (R-PAG-02). A {@code keyset()} query reads each page after the last row of the one before, its
     * order closed by the primary key in the direction of its last order column (R-PAG-04); a NULL in a keyset
     * column needs an explicit {@code nullsFirst()} or {@code nullsLast()} (R-PAG-05). Each page's remaining models
     * go whole to
     * {@code pageTransformer}, and its items one at a time to {@code sink} until {@code options.limit()} is reached;
     * neither is called for an empty page (R-PAG-09). {@code Limit.of(0)} exports nothing without querying.
     *
     * @param pageTransformer receives each page's models, to batch the caller's own lookups; returns the items to sink
     * @param sink receives the items one at a time
     * @return the number of items passed to {@code sink}, which differs from the rows read when
     *     {@code pageTransformer} expands or filters (R-PAG-10)
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2203} for a query without a primary key,
     *     and {@code MQ2204} for one selecting a column through a to-many join (R-PAG-13), both before any query runs;
     *     {@code MQ2201} when a row's primary key is {@code null} (R-PAG-03); {@code MQ2202} when a keyset column
     *     without explicit null precedence is NULL (R-PAG-05)
     */
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink);
}
