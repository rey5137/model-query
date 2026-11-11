package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/** The middle level of {@link JoinBuyerItem}: an order whose customer joins a source and an entity (AC-GEN-42). */
@QueryModel(root = OrderEntity.class)
public record JoinBuyerHolder(@PrimaryKey Long id, @Join Optional<JoinBuyer> customer) {}
