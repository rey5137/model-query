package com.rey.modelquery.jpa;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Slice;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Runs model queries against a JPA {@code EntityManager}. An executor holds no mutable state beyond its
 * {@code EntityManager}, so it is as thread-safe as that is. The first execution of a {@code ModelQuery}, by any
 * executor, checks that its customizer narrows every phase alike (R-QRY-09, D-21); on a query with
 * {@code primaryKeyFirst(...)} a mismatch throws {@code MQ2206} before any query runs, whatever the method (R-PAG-15).
 * A customizer that changes a statement's ordering or grouping throws {@code MQ1205} (R-QRY-11).
 *
 * @param <E> the root entity type
 * @implSpec R-QRY-10, R-QRY-09, R-QRY-11, R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04, R-EXE-07, R-EXE-09, R-PAG-01,
 *     R-PAG-02, R-PAG-03, R-PAG-07, R-PAG-08, R-PAG-09, R-PAG-10, R-PAG-11, R-PAG-12, R-PAG-13, R-PAG-14, R-PAG-15,
 *     R-AGG-09
 */
@Incubating
public interface ModelQueryExecutor<E> {

    /**
     * An executor over {@code em} for queries rooted at {@code rootEntity}; enough to use the library without Spring
     * (INV-8). The vendor profile of {@code em}'s factory is resolved on the first call for that factory and reused
     * after (R-VND-02).
     *
     * @throws com.rey.modelquery.core.ModelQueryConfigurationException {@code MQ4002} when two discovered
     *     {@code VendorProfile}s serve one vendor
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
     * page never reaches here: {@link PageSpec} rejects it when built (R-EXE-06). With {@code primaryKeyFirst(...)}, a
     * page whose offset is above its threshold is read in two steps, the page's primary keys in the query's order and
     * then the rows of those keys, in statements of at most the vendor's IN-list and bind-parameter limits; it holds
     * the same rows in the same order as the one-step page (R-PAG-07, R-PAG-08).
     *
     * @throws com.rey.modelquery.core.ModelQueryExecutionException for a two-step page: {@code MQ2204} for a query
     *     selecting a column through a to-many join, before any query runs (R-PAG-13), and {@code MQ2201} when a
     *     row's primary key is {@code null} (R-PAG-03); on any page of a query with {@code primaryKeyFirst(...)},
     *     {@code MQ2206} when its customizer narrows the phases differently, before any query runs (R-PAG-15)
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
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2101} on PostgreSQL outside a
     *     transaction, before any statement runs (R-EXE-08)
     */
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);

    /**
     * Visits every row of {@code q} exactly once, one page of {@code options.pageSize()} rows at a time, 1000 when the
     * options leave the size open (R-QRY-15), with memory bounded by one page (INV-4). Offset pages are read in a
     * stable order, the primary key appended to the query's order (R-PAG-01). A row whose key the previous page or the
     * same page already held is dropped: with a stable order a row can repeat only across a page boundary, or within a
     * page through a to-many join used only by predicates (R-PAG-02). A {@code keyset()} query reads each page after
     * the last row of the one before, its order closed by the primary key in the direction of its last order column
     * (R-PAG-04); it drops a key repeated within a page, and throws on a key of the page before, which only a cursor
     * value that does not compare equal once bound, or a row whose keyset value moved after the cursor, can bring back
     * (R-PAG-14); a {@code Float} or {@code Double} keyset column is refused at build (R-QRY-13). A NULL in a keyset
     * column needs an explicit {@code nullsFirst()} or {@code nullsLast()} (R-PAG-05). An offset page past the {@code
     * primaryKeyFirst(...)} threshold reads its primary keys first, then only the rows of keys not already exported
     * (R-PAG-07). A grouped query visits every group exactly once, offset-paged in an order closed by its group keys,
     * and dedupes on the group-key tuple; it needs no primary key and ignores one (R-PAG-11, R-PAG-12, R-AGG-09). Each
     * page's remaining models go whole to {@code pageTransformer}, and its items one at a time to {@code sink} until
     * {@code options.limit()} is reached; neither is called for an empty page (R-PAG-09). {@code Limit.of(0)} exports
     * nothing without querying.
     *
     * @param pageTransformer receives each page's models, to batch the caller's own lookups; returns the items to sink
     * @param sink receives the items one at a time
     * @return the number of items passed to {@code sink}, which differs from the rows read when
     *     {@code pageTransformer} expands or filters (R-PAG-10)
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2203} for an ungrouped query without a
     *     primary key, and {@code MQ2204} for an ungrouped one selecting a column through a to-many join (R-PAG-13),
     *     both before any query runs;
     *     {@code MQ2201} when a row's primary key is {@code null} (R-PAG-03); {@code MQ2202} when a keyset column
     *     without explicit null precedence is NULL (R-PAG-05); {@code MQ2205} when a keyset page repeats a row of
     *     the page before (R-PAG-14); {@code MQ2206}, before any query runs, when the customizer of a query with
     *     {@code primaryKeyFirst(...)} narrows the phases differently (R-PAG-15)
     */
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink);
}
