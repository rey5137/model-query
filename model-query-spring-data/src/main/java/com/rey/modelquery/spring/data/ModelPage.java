package com.rey.modelquery.spring.data;

import com.rey.modelquery.core.CountMode;
import java.util.function.Function;
import org.springframework.data.domain.Slice;

/**
 * One page read by {@link ModelQueryRepository#findPage}: a Spring Data {@link Slice} plus the total when the
 * {@link CountMode} counted it. Not a Spring Data {@code Page}, whose primitive total cannot be unknown (D-51).
 *
 * @param <M> the model type
 * @implSpec R-SPR-07, D-51
 */
public interface ModelPage<M> extends Slice<M> {

    /**
     * The exact number of rows behind the query under {@link CountMode#COUNT} and {@link CountMode#ONLY_COUNT};
     * {@code null} under {@link CountMode#NO_COUNT}, never a number made up from the current page.
     */
    Long getTotalElements();

    /**
     * The exact number of pages of {@link #getSize()} rows under {@link CountMode#COUNT} and
     * {@link CountMode#ONLY_COUNT}; {@code null} under {@link CountMode#NO_COUNT}.
     *
     * @throws ArithmeticException when that number does not fit an {@code int}
     */
    Integer getTotalPages();

    /** A page of the converted content, with the same page, {@code hasNext} and totals. */
    @Override
    <U> ModelPage<U> map(Function<? super M, ? extends U> converter);
}
