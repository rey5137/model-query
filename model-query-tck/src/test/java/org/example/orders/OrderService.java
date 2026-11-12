package org.example.orders;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An application's service over orders, outside the library's packages, so each method is the call site a recorder
 * reads (R-INS-14).
 */
public final class OrderService {

    private final ModelQueryExecutor<OrderEntity> orders;

    /** A service calling {@code orders}. */
    public OrderService(ModelQueryExecutor<OrderEntity> orders) {
        this.orders = orders;
    }

    /** The first page of {@code query}, counted. */
    public <M> Slice<M> search(ModelQuery<OrderEntity, ?, M> query) {
        return orders.page(query, PageSpec.of(0, 5), CountMode.COUNT);
    }

    /** The one row of {@code query}. */
    public <M> Optional<M> find(ModelQuery<OrderEntity, ?, M> query) {
        return orders.one(query);
    }

    /** The row of {@code query} with {@code key}. */
    public <K, M> Optional<M> find(ModelQuery<OrderEntity, K, M> query, K key) {
        return orders.one(query, key);
    }

    /** The rows of {@code query} with {@code keys}. */
    public <K, M> Map<K, M> byKeys(ModelQuery<OrderEntity, K, M> query, Collection<K> keys) {
        return orders.byKeys(query, keys);
    }

    /** {@code body} over the first rows of {@code query}, streamed. */
    public <M, R> R streamed(ModelQuery<OrderEntity, ?, M> query, Function<Stream<M>, R> body) {
        return orders.stream(query, Limit.of(3), body);
    }

    /** Every row of {@code query}, two per page, to {@code sink}. */
    public <M> long export(ModelQuery<OrderEntity, ?, M> query, Consumer<M> sink) {
        return orders.export(query, ExportOptions.of(2), page -> page, sink);
    }

    /** Runs {@code update}. */
    public long rename(ModelUpdate<OrderEntity, ?> update) {
        return orders.update(update);
    }

    /** Runs {@code call} from another call site of the application. */
    public static <R> R elsewhere(Supplier<R> call) {
        return call.get();
    }
}
