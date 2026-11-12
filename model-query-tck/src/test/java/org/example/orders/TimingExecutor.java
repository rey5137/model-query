package org.example.orders;

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
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An application's executor that counts each call, then delegates: a decorator of its own, outside the library's
 * packages, which a recorder it wraps never takes for the call site (R-INS-14, R-SPR-18).
 */
public final class TimingExecutor<E> implements ModelQueryExecutor<E> {

    private final ModelQueryExecutor<E> delegate;
    private final AtomicInteger calls;

    /** {@code delegate}, counting each call in {@code calls}. */
    public TimingExecutor(ModelQueryExecutor<E> delegate, AtomicInteger calls) {
        this.delegate = delegate;
        this.calls = calls;
    }

    private <T> T timed(Supplier<T> call) {
        calls.incrementAndGet();
        return call.get();
    }

    @Override
    public <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit) {
        return timed(() -> delegate.list(q, limit));
    }

    @Override
    public <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode) {
        return timed(() -> delegate.page(q, page, mode));
    }

    @Override
    public <M> KeysetSlice<M> page(ModelQuery<E, ?, M> q, KeysetSpec keyset) {
        return timed(() -> delegate.page(q, keyset));
    }

    @Override
    public long count(ModelQuery<E, ?, ?> q) {
        return timed(() -> delegate.count(q));
    }

    @Override
    public <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body) {
        return timed(() -> delegate.stream(q, limit, body));
    }

    @Override
    public <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        return timed(() -> delegate.export(q, options, pageTransformer, sink));
    }

    @Override
    public <M> Optional<M> one(ModelQuery<E, ?, M> q) {
        return timed(() -> delegate.one(q));
    }

    @Override
    public <M> Optional<M> first(ModelQuery<E, ?, M> q) {
        return timed(() -> delegate.first(q));
    }

    @Override
    public <K, M> Optional<M> one(ModelQuery<E, K, M> q, K key) {
        return timed(() -> delegate.one(q, key));
    }

    @Override
    public <K, M> Map<K, M> byKeys(ModelQuery<E, K, M> q, Collection<? extends K> keys) {
        return timed(() -> delegate.byKeys(q, keys));
    }

    @Override
    public long update(ModelUpdate<E, ?> u) {
        return timed(() -> delegate.update(u));
    }

    @Override
    public long delete(ModelDelete<E, ?> d) {
        return timed(() -> delegate.delete(d));
    }

    @Override
    public <R> List<R> updateReturning(ModelUpdate<E, ?> u, ModelQuery<E, ?, R> returning) {
        return timed(() -> delegate.updateReturning(u, returning));
    }

    @Override
    public <R> List<R> deleteReturning(ModelDelete<E, ?> d, ModelQuery<E, ?, R> returning) {
        return timed(() -> delegate.deleteReturning(d, returning));
    }

    @Override
    public <R> List<R> insertReturning(ModelInsert<E, ?> i, ModelQuery<E, ?, R> returning) {
        return timed(() -> delegate.insertReturning(i, returning));
    }

    @Override
    public long insert(ModelInsert<E, ?> i) {
        return timed(() -> delegate.insert(i));
    }

    @Override
    public <K> List<K> insertReturningKeys(ValuesInsert<E, K, ?> i) {
        return timed(() -> delegate.insertReturningKeys(i));
    }

    @Override
    public <K> K persist(ModelPersist<E, K, ?> p) {
        return timed(() -> delegate.persist(p));
    }

    @Override
    public <R> R persist(ModelPersist<E, ?, ?> persist, ModelQuery<E, ?, R> returning) {
        return timed(() -> delegate.persist(persist, returning));
    }
}
