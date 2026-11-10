package com.rey.modelquery.tck.part;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/** An order and its events, a to-many child over a partitioned table (R-FCH-18). */
@QueryModel(root = PartOrderEntity.class)
public record PartOrder(@PrimaryKey Long id, @Child(foreignKey = "order.id") List<PartEvent> events) {}
