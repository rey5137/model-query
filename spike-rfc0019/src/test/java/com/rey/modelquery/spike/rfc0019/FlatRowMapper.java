package com.rey.modelquery.spike.rfc0019;

import com.rey.modelquery.core.IndexResolvedValueRow;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.Row;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Spike copy of the flat-row {@code mapAll} for P-B.2 (RB.6): skips {@code Loaded} wrapping,
 * deduping bookkeeping and pre-sizes the result list.
 */
public final class FlatRowMapper {

    private FlatRowMapper() {}

    public static <M> List<M> mapAll(
            List<Tuple> tuples,
            IndexResolvedValueRow.IndexResolver resolver,
            Function<Row, M> mapper,
            Limit limit
    ) {
        int max = limit.maxRows().orElse(Integer.MAX_VALUE);
        int initialCapacity = max == Integer.MAX_VALUE
                ? Math.min(tuples.size(), 1024)
                : Math.min(max, 1024);
        List<M> result = new ArrayList<>(initialCapacity);
        for (Tuple tuple : tuples) {
            IndexResolvedValueRow row = resolver.row(tuple);
            result.add(mapper.apply(row));
        }
        return result;
    }
}
