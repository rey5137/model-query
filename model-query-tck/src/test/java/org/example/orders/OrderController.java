package org.example.orders;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.spring.data.ModelPage;
import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

/**
 * An application's controller over an orders repository, outside the library's packages, so each method is the call
 * site a recorder reads through the repository's proxies (R-INS-14, R-SPR-18).
 */
public final class OrderController {

    private final ModelQueryRepository<OrderEntity> orders;

    /** A controller calling {@code orders}. */
    public OrderController(ModelQueryRepository<OrderEntity> orders) {
        this.orders = orders;
    }

    /** The requested page of {@code query}, counted. */
    public <M> ModelPage<M> list(ModelQuery<OrderEntity, ?, M> query, Pageable pageable) {
        return orders.findPage(query, pageable, CountMode.COUNT);
    }

    /** The row of {@code query} with {@code key}. */
    public <K, M> Optional<M> show(ModelQuery<OrderEntity, K, M> query, K key) {
        return orders.findByKey(query, key);
    }

    /** The first rows of {@code query}. */
    public <M> List<M> recent(ModelQuery<OrderEntity, ?, M> query) {
        return orders.findAll(query, Limit.of(3));
    }
}
