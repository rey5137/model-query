package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.List;

/** An order and its labels through the inverse side of the many-to-many, as {@link OrderLabels} loads them. */
@QueryModel(root = OrderEntity.class)
public record OrderLabelSet(@PrimaryKey Long id, @Child(through = "labels") List<LabelView> labels) {}
