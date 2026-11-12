package com.rey.modelquery.tck.portability.child;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.Optional;

/** An order with its customer as a {@code @Join} nested model, whose plan has children of its own. */
@QueryModel(root = ChildEntities.TreeOrderEntity.class)
public record OrderWithTree(@PrimaryKey Long id, String status, @Join Optional<TreeCustomer> customer) {}
