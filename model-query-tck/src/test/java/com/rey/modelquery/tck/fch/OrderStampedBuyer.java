package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/** An order and its buyer, a {@code through} child with one row per key, for key groups (R-FCH-14, R-FCH-18). */
@QueryModel(root = OrderEntity.class)
public record OrderStampedBuyer(@PrimaryKey Long id, @Child(through = "customer") Optional<StampedBuyer> buyer) {}
