package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.SampleValueEntity;
import java.math.BigDecimal;

/**
 * A grouped model whose {@code AVG} over a {@code BigDecimal} is the exact average, filtered to the positive values
 * and rounded to two decimals (R-PROC-45, AC-AGG-26). The decimal itself is a filter-only column.
 */
@QueryModel(root = SampleValueEntity.class)
@FilterColumn(name = "DEC", path = "decValue")
public record SampleAverages(
        @GroupBy Integer intValue,
        @Aggregate(fn = AggregateFunction.AVG, attribute = "decValue", filter = PositiveSamples.class, scale = 2)
        BigDecimal average) {}
