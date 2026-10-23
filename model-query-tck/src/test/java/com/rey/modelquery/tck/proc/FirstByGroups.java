package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.FirstByRowEntity;

/** A grouped model whose value is the first row's by a descending key, NULL keys last (R-PROC-45, AC-AGG-26). */
@QueryModel(root = FirstByRowEntity.class)
public record FirstByGroups(
        @GroupBy Integer grp,
        @Aggregate(fn = AggregateFunction.FIRST_BY, attribute = "value", orderBy = "key desc nulls last")
        String top) {}
