package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderItemEntity;
import java.util.Optional;

/** A record model nesting the class {@link OrderView}, which nests a record in turn. */
@QueryModel(root = OrderItemEntity.class)
public record ItemView(@PrimaryKey Long id, String productCode, @Join Optional<OrderView> order) {}
