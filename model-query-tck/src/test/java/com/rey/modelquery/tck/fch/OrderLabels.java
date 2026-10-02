package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.List;

/** An order and its labels, many-to-many: the label's {@code foreignKey} crosses its collection of orders (D-99). */
@QueryModel(root = OrderEntity.class)
public record OrderLabels(@PrimaryKey Long id, @Child(foreignKey = "orders.id") List<LabelView> labels) {}
