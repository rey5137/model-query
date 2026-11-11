package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.JoinSource;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.SourceColumn;
import com.rey.modelquery.tck.col.CustomerEntity;

/** A nested model whose entity join is anchored on a column of its own source join (AC-GEN-46). */
@QueryModel(root = CustomerEntity.class)
@JoinSource(name = "tally", source = CustomerOrderCount.class, on = @JoinSource.On(column = "id", key = "id"))
@JoinEntity(name = "twin", source = "tally", entity = CustomerEntity.class, on = @JoinEntity.On(field = "id"))
public record JoinTwin(
        @PrimaryKey Long id,
        @SourceColumn(join = "tally", field = "rows") Long rows,
        @Column(entityJoin = "twin", attribute = "name") String twinName) {}
