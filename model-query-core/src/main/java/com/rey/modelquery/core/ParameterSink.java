package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.ParameterExpression;

/**
 * Where {@link ValueOperands#valueOperand} records the parameters it makes: a build's {@code JoinContext}, or an
 * executor path collecting its own statement's parameters. A parameter binds one value per statement, so the helper
 * asks first whether the statement already binds one a provider's binder returned (INV-5, R-FLT-08).
 *
 * @implSpec api/12 R-FLT-08, R-FLT-21, D-177
 */
@EngineFacing
@Incubating
public interface ParameterSink {

    /** Whether this statement already binds {@code parameter}. */
    boolean binds(ParameterExpression<?> parameter);

    /** Records {@code parameter} to bind {@code value} on the statement that renders it. */
    void add(ParameterExpression<?> parameter, Object value);
}
