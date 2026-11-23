package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Filters and bound of a map child load. Each method returns a copy (R-FCH-20, INV-9).
 *
 * @param <K> the map key type
 * @param <V> the map value type
 */
@CheckReturnValue
@Incubating
public final class MapChildQuery<K, V> {
    private final List<Filter> where;
    private final int maxPerParent;
    private final Class<?> rootEntity;

    private MapChildQuery(List<Filter> where, int maxPerParent, Class<?> rootEntity) {
        this.where = where;
        this.maxPerParent = maxPerParent;
        this.rootEntity = rootEntity;
    }

    static <K, V> MapChildQuery<K, V> empty(Class<?> rootEntity) {
        return new MapChildQuery<>(List.of(), 0, rootEntity);
    }

    /** Replaces the filters over an element-model value (R-FCH-20). */
    public MapChildQuery<K, V> where(UnaryOperator<Filters<V>> filters) {
        return new MapChildQuery<>(FilterGroup.collect(rootEntity, Objects.requireNonNull(filters, "filters")),
                maxPerParent, rootEntity);
    }

    /** Bounds the filtered entries per parent; {@code n} must be positive (R-FCH-11). */
    public MapChildQuery<K, V> maxPerParent(int n) {
        if (n < 1) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "maxPerParent(" + n + ") must be positive");
        }
        return new MapChildQuery<>(where, n, rootEntity);
    }

    List<Filter> filters() {
        return where;
    }

    int bound() {
        return maxPerParent;
    }
}
