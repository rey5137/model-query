package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;

/**
 * Recipe 3: films grouped by an expression key, the classic/modern split {@link FilmBandKey} computes from the release
 * year, summing the expression {@link FilmTickets} computes over {@code tickets}.
 */
@QueryModel(root = FilmEntity.class)
public record FilmBand(
        @GroupBy @Computed(FilmBandKey.class) String band,
        @Aggregate(fn = AggregateFunction.SUM, expression = FilmTickets.class) Long tickets) {}
