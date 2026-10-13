package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;

/**
 * Renders a database sequence's next value, as the persistence provider's dialect spells it: PostgreSQL's
 * {@code nextval('s')}, H2's {@code next value for s}. Without one, {@code Expr.nextval} is {@code MQ1509}
 * (R-COL-28).
 *
 * <p>Vendor- and provider-neutral, so {@code core} names no provider (INV-7); {@code model-query-hibernate} supplies
 * one. The executor offers it only where the {@code VendorProfile} says the database has sequences. Immutable and
 * thread-safe.
 *
 * @implSpec R-COL-28, R-VND-18, D-129
 */
@Incubating
@FunctionalInterface
public interface SequenceRenderer {

    /**
     * The expression reading {@code sequence}'s next value; {@code sequence} is already checked as a plain,
     * optionally schema-qualified SQL identifier.
     */
    Expression<Long> nextValue(CriteriaBuilder cb, String sequence);
}
