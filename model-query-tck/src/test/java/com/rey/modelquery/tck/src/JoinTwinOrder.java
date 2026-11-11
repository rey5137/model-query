package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/** The order of a customer whose entity join reads its source join (AC-GEN-46). */
@QueryModel(root = OrderEntity.class)
public record JoinTwinOrder(@PrimaryKey Long id, @Join Optional<JoinTwin> customer) {}
