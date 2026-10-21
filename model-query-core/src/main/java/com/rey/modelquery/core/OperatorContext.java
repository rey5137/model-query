package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;

/**
 * What an {@link OperatorRenderer} is given besides its two operands: the statement's {@link CriteriaBuilder}. It
 * may gain methods in a later release, so implement it only in tests.
 *
 * @implSpec api/12 R-FLT-20
 */
@Incubating
public interface OperatorContext {

    /** The criteria builder of the statement being rendered. */
    CriteriaBuilder cb();
}
