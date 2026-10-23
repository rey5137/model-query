package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * An aggregate form a database may lack, which a {@code VendorProfile} answers per target so the engine can refuse a
 * query that uses it before any statement ({@code MQ4015}), as it does for an {@link OperatorFeature}
 * (R-VND-29, D-185).
 *
 * <p>A profile written before a constant was added must answer {@code false} for it.
 *
 * @implSpec R-VND-29, D-185
 */
@EngineFacing
@Incubating
public enum AggregateFeature {

    /** {@code Agg.firstBy}: an ordered array aggregate with {@code FILTER}, which MySQL has no form of. */
    @Incubating
    FIRST_BY
}
