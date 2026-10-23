package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.FilterDefinition;
import com.rey.modelquery.core.Filters;
import java.math.BigDecimal;
import java.util.function.UnaryOperator;

/** The condition of {@link SampleAverages}'s average: only the rows whose decimal is above zero (R-PROC-45). */
public final class PositiveSamples implements FilterDefinition<SampleAverages> {

    public static final PositiveSamples INSTANCE = new PositiveSamples();

    private PositiveSamples() {}

    @Override
    public UnaryOperator<Filters<SampleAverages>> condition() {
        return f -> f.gt(QSampleAverages.DEC, BigDecimal.ZERO);
    }
}
