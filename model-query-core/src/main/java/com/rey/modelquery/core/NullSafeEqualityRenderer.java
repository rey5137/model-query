package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * The provider's native null-safe comparison of two operands, beyond standard Criteria (R-VND-30, INV-7). The executor
 * supplies it through {@code ProviderSupport#nullSafeEquality()} and folds it in only where the vendor has the fact
 * {@code VendorProfile#nativeNullSafeEquality()} (R-VND-29).
 */
@EngineFacing
@Incubating
public interface NullSafeEqualityRenderer {

    /** {@code a IS NOT DISTINCT FROM b}: true when both are NULL or equal, never NULL. */
    @Incubating
    Predicate notDistinctFrom(CriteriaBuilder cb, Expression<?> a, Expression<?> b);

    /** {@code a IS DISTINCT FROM b}: the negation of {@link #notDistinctFrom}, never NULL. */
    @Incubating
    Predicate distinctFrom(CriteriaBuilder cb, Expression<?> a, Expression<?> b);
}
