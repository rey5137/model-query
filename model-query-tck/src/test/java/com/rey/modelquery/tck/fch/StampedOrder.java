package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.time.LocalDateTime;

/** An order and when it was placed: a key-grouped child bounded on {@code placedAt} (R-FCH-18). */
@QueryModel(root = OrderEntity.class)
public record StampedOrder(@PrimaryKey Long id, LocalDateTime placedAt) {}
