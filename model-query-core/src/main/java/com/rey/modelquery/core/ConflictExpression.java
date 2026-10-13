package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;

/**
 * A conflict update's assignment computed from the stored and the incoming row, an escape hatch like
 * {@link Assignment.Expression}: its path has the entity attribute's type, so a column with a converter is refused
 * at {@code build()} with {@code MQ1609}. Mirrors {@code ModelUpdate.setExpression} with the incoming ({@code
 * excluded}) row added (R-WRT-51).
 *
 * @param <C> the column's Java type
 * @implSpec R-WRT-51
 */
@FunctionalInterface
@Incubating
public interface ConflictExpression<C> {

    /**
     * The value written, given the stored row's {@code stored} path and the incoming row's {@code incoming} path.
     *
     * @param stored the stored row's column
     * @param incoming the incoming row's column, the {@code excluded} value
     * @param cb the criteria builder
     */
    Expression<? extends C> apply(Path<C> stored, Path<C> incoming, CriteriaBuilder cb);
}
