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

/**
 * Runs model queries against a JPA {@code EntityManager}. An executor holds no state beyond its
 * {@code EntityManager}, so it is as thread-safe as that is.
 *
 * @param <E> the root entity type
 * @implSpec R-QRY-10, R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04
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
}
