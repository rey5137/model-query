package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * An {@link Instant} model value over a {@link Timestamp} attribute, through {@link Timestamp#toInstant} and
 * {@link Timestamp#from}: nanoseconds are kept both ways, and the order of instants is the order of timestamps.
 *
 * @implSpec R-COL-14, D-84
 */
@Incubating
public final class InstantTimestampConverter implements OrderedColumnConverter<Instant, Timestamp> {

    /** The one instance; the converter is stateless. */
    public static final InstantTimestampConverter INSTANCE = new InstantTimestampConverter();

    private InstantTimestampConverter() {}

    @Override
    public Instant toModel(Timestamp attribute) {
        return attribute.toInstant();
    }

    @Override
    public Timestamp toAttribute(Instant model) {
        return Timestamp.from(model);
    }
}
