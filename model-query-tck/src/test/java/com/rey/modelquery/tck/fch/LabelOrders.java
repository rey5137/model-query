package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.LabelEntity;
import java.util.List;

/** A label and its orders, through the owning side of the label-order many-to-many (R-FCH-14). */
@QueryModel(root = LabelEntity.class)
public record LabelOrders(@PrimaryKey Long id, @Child(through = "orders") List<OrderLines> orders) {}
