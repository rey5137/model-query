package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;

/**
 * Makes a filter operator's statement parameter typed by the column's mapping, where a {@code core} parameter can only
 * be typed by a Java class: an {@code @Enumerated(STRING)} value then binds as its name and a {@code @Convert} value
 * through its converter, rather than by ordinal or not at all (api/12 R-FLT-21). The engine sets the value on the
 * parameter per statement. Without one, only a value whose binding doesn't depend on the mapping binds.
 *
 * <p>Vendor- and provider-neutral, so {@code core} names no provider (INV-7); {@code ProviderSupport#operands(emf)}
 * supplies one, resolved once per executor. Immutable and thread-safe.
 *
 * @implSpec vendor/40 R-VND-24, api/12 R-FLT-07, R-FLT-21, D-169, D-177
 */
@Incubating
@FunctionalInterface
public interface OperandBinder {

    /**
     * A parameter of {@code type} whose binding follows {@code typedBy}'s mapping, the operator's rendered column or
     * aggregate expression, new on every call: the engine sets one value on each parameter per statement, so a
     * parameter returned twice is {@code MQ1502}. It never falls back to a parameter typed by {@code type} alone: for a
     * {@code typedBy} it can't type by it throws, and the engine reports a thrown {@code RuntimeException} or a
     * {@code null} result as {@code MQ1502} naming the operator and the column, the exception as the cause.
     */
    <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type);

    /**
     * Whether {@code typedBy} carries a mapping this binder types a value by, asked before {@link #parameter}. When it
     * doesn't, a function of a column say, whose type the provider knows only from its Java class, the engine binds
     * the value as with no binder: only a mapping-independent value binds, anything else is {@code MQ1502} (R-FLT-21,
     * D-168). Every expression by default.
     */
    default boolean typesBy(Expression<?> typedBy) {
        return true;
    }

    /**
     * Whether a value typed by {@code typedBy} goes through a converter of the mapping, a JPA
     * {@code AttributeConverter} ({@code @Convert} or auto-applied), when bound; asked only where {@link #typesBy} is
     * {@code true}. An enum's built-in conversion is no such converter. Where it is {@code true}, an ignore-case filter
     * binds its value through {@link #parameter} and folds both sides, {@code lower(col) = lower(?)}, rather than
     * comparing with a value lower-cased in Java that the converter never sees (api/12 R-FLT-07). {@code false} by
     * default.
     */
    @Incubating
    default boolean converts(Expression<?> typedBy) {
        return false;
    }
}
