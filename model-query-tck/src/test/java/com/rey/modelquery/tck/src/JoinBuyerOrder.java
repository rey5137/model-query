package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/** Two {@code @Join} fields of one nested type, each with its own entity and source join (AC-GEN-41). */
@QueryModel(root = OrderEntity.class)
public record JoinBuyerOrder(
        @PrimaryKey Long id,
        @Join Optional<JoinBuyer> customer,
        @Join Optional<JoinBuyer> referrer) {}
