package org.example.orders;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.jpa.ForwardingModelQueryExecutor;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * An application's executor that counts the reads it times, then delegates: a decorator of its own, outside the
 * library's packages, which a recorder it wraps never takes for the call site (R-INS-14, R-SPR-18). It extends the
 * forwarding base, so it overrides only those reads (D-225).
 */
public final class TimingExecutor<E> extends ForwardingModelQueryExecutor<E> {

    private final AtomicInteger calls;

    /** {@code delegate}, counting each timed read in {@code calls}. */
    public TimingExecutor(ModelQueryExecutor<E> delegate, AtomicInteger calls) {
        super(delegate);
        this.calls = calls;
    }

    private <T> T timed(Supplier<T> call) {
        calls.incrementAndGet();
        return call.get();
    }

    @Override
    public <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit) {
        return timed(() -> delegate().list(q, limit));
    }

    @Override
    public <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode) {
        return timed(() -> delegate().page(q, page, mode));
    }

    @Override
    public <M> KeysetSlice<M> page(ModelQuery<E, ?, M> q, KeysetSpec keyset) {
        return timed(() -> delegate().page(q, keyset));
    }

    @Override
    public <K, M> Optional<M> one(ModelQuery<E, K, M> q, K key) {
        return timed(() -> delegate().one(q, key));
    }
}
