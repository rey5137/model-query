package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderItemEntity;
import java.util.Optional;

/** Two levels: the item's order, whose customer carries joins of its own (AC-GEN-42). */
@QueryModel(root = OrderItemEntity.class)
public record JoinBuyerItem(@PrimaryKey Long id, @Join Optional<JoinBuyerHolder> order) {}
