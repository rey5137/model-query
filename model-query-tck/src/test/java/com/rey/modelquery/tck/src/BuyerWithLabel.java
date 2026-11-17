package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.LabelEntity;
import java.util.Optional;

/** A customer model that joins its label via an entity join (processor/31 R-GEN-43, AC-GEN-50, AC-GEN-51). */
@QueryModel(root = CustomerEntity.class)
@JoinEntity(name = "label", entity = LabelEntity.class, on = @JoinEntity.On(column = "id"))
public record BuyerWithLabel(
        @PrimaryKey Long id,
        String name,
        @Join(entityJoin = "label") Optional<LabelSummary> label) {}
