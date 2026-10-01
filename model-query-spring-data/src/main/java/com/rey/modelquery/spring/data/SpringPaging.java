package com.rey.modelquery.spring.data;

import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.SortSpec;
import java.util.ArrayList;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Converts Spring Data's {@link Pageable} and {@link Sort} to {@code core}'s {@link PageSpec} and {@link SortSpec},
 * so a repository call reaches the same executor call a plain-JPA caller makes (INV-8).
 *
 * @implSpec R-SPR-04, R-SPR-05, R-SPR-06
 */
final class SpringPaging {

    private SpringPaging() {}

    /**
     * The page {@code pageable} asks for, at its own offset as Spring Data's JPA repositories read it.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for {@link Pageable#unpaged()}, {@code MQ2002} for an
     *     offset that does not fit an {@code int}
     */
    static PageSpec pageSpec(Pageable pageable) {
        if (pageable.isUnpaged()) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "Pageable.unpaged() has no page size; findPage "
                    + "needs a paged Pageable, and findAll(q, Limit) reads rows without a page");
        }
        long offset = pageable.getOffset();
        if (offset > Integer.MAX_VALUE) {
            throw new ModelQueryExecutionException(MqCode.MQ2002, "page " + pageable.getPageNumber() + " of size "
                    + pageable.getPageSize() + " has offset " + offset + ", which is above " + Integer.MAX_VALUE);
        }
        return PageSpec.ofOffset((int) offset, pageable.getPageSize());
    }

    /**
     * {@code sort}'s orders as keys, in order; {@link SortSpec#unsorted()} for an unsorted {@code sort}.
     *
     * @throws ModelQueryExecutionException {@code MQ2301} for an order that asks {@code ignoreCase()}, which the
     *     engine cannot honour, rather than dropping it (INV-5)
     */
    static SortSpec sortSpec(Sort sort) {
        if (sort.isUnsorted()) {
            return SortSpec.unsorted();
        }
        var keys = new ArrayList<SortSpec.Key>();
        for (Sort.Order order : sort) {
            if (order.isIgnoreCase()) {
                throw new ModelQueryExecutionException(MqCode.MQ2301, "sort property '" + order.getProperty()
                        + "' asks ignoreCase(), which the engine cannot honour; sort by it without ignoreCase()");
            }
            keys.add(new SortSpec.Key(order.getProperty(), order.isAscending(), nulls(order.getNullHandling())));
        }
        return new SortSpec(keys);
    }

    private static NullPrecedence nulls(Sort.NullHandling handling) {
        return switch (handling) {
            case NULLS_FIRST -> NullPrecedence.FIRST;
            case NULLS_LAST -> NullPrecedence.LAST;
            case NATIVE -> NullPrecedence.DEFAULT;
        };
    }
}
