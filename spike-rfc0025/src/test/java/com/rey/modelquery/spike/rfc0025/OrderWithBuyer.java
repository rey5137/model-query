package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.Optional;

/** An order with its customer as a {@code @Join} nested model, whose plan has children of its own. */
@QueryModel(root = Entities.SpOrder.class)
public record OrderWithBuyer(@PrimaryKey Long id, String status, @Join Optional<CustomerTree> customer) {}
