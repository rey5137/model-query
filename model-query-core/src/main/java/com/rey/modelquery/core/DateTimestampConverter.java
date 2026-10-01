package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.sql.Timestamp;
import java.util.Date;

/**
 * A {@link Date} model value over a {@link Timestamp} attribute. {@link #toModel} returns the {@code Timestamp} itself,
 * typed as a {@code Date}, so no sub-millisecond digits are lost, and a value read back binds exactly what was read:
 * an {@code eq}, {@code gt} or {@code lte} against a stored value with microseconds is exact. {@link #toAttribute}
 * keeps a {@code Timestamp} as it is and converts any other {@code Date} by its milliseconds.
 *
 * @implSpec R-COL-14, D-84
 */
@Incubating
public final class DateTimestampConverter implements OrderedColumnConverter<Date, Timestamp> {

    /** The one instance; the converter is stateless. */
    public static final DateTimestampConverter INSTANCE = new DateTimestampConverter();

    private DateTimestampConverter() {}

    @Override
    public Date toModel(Timestamp attribute) {
        return attribute;
    }

    @Override
    public Timestamp toAttribute(Date model) {
        return model instanceof Timestamp timestamp ? timestamp : new Timestamp(model.getTime());
    }
}
