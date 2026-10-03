package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.tck.col.OrderItemEntity;

/** An order item: the bottom of a three-level plan, and a model a composite-key enricher fills. */
@QueryModel(root = OrderItemEntity.class)
public record Line(@PrimaryKey Long id, String productCode, Integer quantity, @Transient String profile) {

    Line withProfile(String value) {
        return new Line(id, productCode, quantity, value);
    }
}
