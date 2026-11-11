package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.JoinSource;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.SourceColumn;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.LabelEntity;

/** A nested model with a source join and an entity join of its own, read under {@code @Join} (AC-GEN-41). */
@QueryModel(root = CustomerEntity.class)
@JoinSource(name = "tally", source = CustomerOrderCount.class, on = @JoinSource.On(column = "id", key = "id"))
@JoinEntity(name = "label", entity = LabelEntity.class, on = @JoinEntity.On(column = "id"))
public record JoinBuyer(
        @PrimaryKey Long id,
        String name,
        @SourceColumn(join = "tally", field = "rows") Long rows,
        @Column(entityJoin = "label", attribute = "name") String labelName) {}
