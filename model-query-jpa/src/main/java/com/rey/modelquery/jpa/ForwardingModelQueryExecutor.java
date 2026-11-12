package com.rey.modelquery.jpa;

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
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.ValuesInsert;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A {@link ModelQueryExecutor} that forwards every call to another, for an application's executor decorator to
 * extend and override only the calls it wraps (R-SPR-18, D-225). A method added to {@code ModelQueryExecutor} is
 * added here in the same release, so a decorator extending this class keeps working, where one implementing the
 * interface itself throws {@code AbstractMethodError} on the new method.
 *
 * @param <E> the root entity type
 */
@Incubating
public abstract class ForwardingModelQueryExecutor<E> implements ModelQueryExecutor<E> {

    private final ModelQueryExecutor<E> delegate;

    /** A forwarder to {@code delegate}. */
    protected ForwardingModelQueryExecutor(ModelQueryExecutor<E> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The executor every call is forwarded to. */
    protected final ModelQueryExecutor<E> delegate() {
        return delegate;
    }

    @Override
    public <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit) {
        return delegate.list(q, limit);
    }

    @Override
    public <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode) {
        return delegate.page(q, page, mode);
    }

    @Override
    public <M> KeysetSlice<M> page(ModelQuery<E, ?, M> q, KeysetSpec keyset) {
        return delegate.page(q, keyset);
    }

    @Override
    public long count(ModelQuery<E, ?, ?> q) {
        return delegate.count(q);
    }

    @Override
    public <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body) {
        return delegate.stream(q, limit, body);
    }

    @Override
    public <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        return delegate.export(q, options, pageTransformer, sink);
    }

    @Override
    public <M> Optional<M> one(ModelQuery<E, ?, M> q) {
        return delegate.one(q);
    }

    @Override
    public <M> Optional<M> first(ModelQuery<E, ?, M> q) {
        return delegate.first(q);
    }

    @Override
    public <K, M> Optional<M> one(ModelQuery<E, K, M> q, K key) {
        return delegate.one(q, key);
    }

    @Override
    public <K, M> Map<K, M> byKeys(ModelQuery<E, K, M> q, Collection<? extends K> keys) {
        return delegate.byKeys(q, keys);
    }

    @Override
    public long update(ModelUpdate<E, ?> u) {
        return delegate.update(u);
    }

    @Override
    public long delete(ModelDelete<E, ?> d) {
        return delegate.delete(d);
    }

    @Override
    public <R> List<R> updateReturning(ModelUpdate<E, ?> u, ModelQuery<E, ?, R> returning) {
        return delegate.updateReturning(u, returning);
    }

    @Override
    public <R> List<R> deleteReturning(ModelDelete<E, ?> d, ModelQuery<E, ?, R> returning) {
        return delegate.deleteReturning(d, returning);
    }

    @Override
    public <R> List<R> insertReturning(ModelInsert<E, ?> i, ModelQuery<E, ?, R> returning) {
        return delegate.insertReturning(i, returning);
    }

    @Override
    public long insert(ModelInsert<E, ?> i) {
        return delegate.insert(i);
    }

    @Override
    public <K> List<K> insertReturningKeys(ValuesInsert<E, K, ?> i) {
        return delegate.insertReturningKeys(i);
    }

    @Override
    public <K> K persist(ModelPersist<E, K, ?> p) {
        return delegate.persist(p);
    }

    @Override
    public <R> R persist(ModelPersist<E, ?, ?> persist, ModelQuery<E, ?, R> returning) {
        return delegate.persist(persist, returning);
    }
}
