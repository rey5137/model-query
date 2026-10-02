package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * An {@link Instant} model value over a {@link Timestamp} attribute, through {@link Timestamp#toInstant} and
 * {@link Timestamp#from}: nanoseconds are kept both ways, and the order of instants is the order of timestamps. An
 * instant beyond the {@code Timestamp} range, such as {@link Instant#MAX}, is refused with {@code MQ1308} when a
 * filter or write takes it, not bound as a different value.
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
        Timestamp attribute = Timestamp.from(model);
        // Beyond the Timestamp range, such as Instant.MAX as an "open" bound, from() throws or, on some JDKs, returns
        // a different instant; either is refused rather than bound as a wrong value.
        if (!attribute.toInstant().equals(model)) {
            throw new IllegalArgumentException(model + " is outside the range of a java.sql.Timestamp");
        }
        return attribute;
    }
}
