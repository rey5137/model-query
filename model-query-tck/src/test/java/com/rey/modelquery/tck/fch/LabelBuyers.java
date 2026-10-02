package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.LabelEntity;
import java.util.List;

/** A label and the customers of its orders: a path through the orders, a collection, then a to-one (R-FCH-14). */
@QueryModel(root = LabelEntity.class)
public record LabelBuyers(@PrimaryKey Long id, @Child(through = "orders.customer") List<Buyer> buyers) {}
