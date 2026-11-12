package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/** A model rooted at {@link OrderSrc}, with a child keyed on the source field {@code id}. */
@QueryModel(root = OrderSrc.class)
public record OrderSrcFeed(@PrimaryKey Long id, String status,
        @Child(key = "id", foreignKey = "order.id") List<LineRow> lines) {}
