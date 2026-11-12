package com.rey.modelquery.tck.portability.child;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;
import java.util.Optional;

/**
 * Level 2: an order and its lines; also a to-one child by key ({@code customer}) and a to-one child {@code through}
 * an association ({@code buyer}), the one shape {@code verifyMissing} key groups take.
 */
@QueryModel(root = ChildEntities.TreeOrderEntity.class)
public record TreeOrder(@PrimaryKey Long id, String status,
        @Child(foreignKey = "order.id") List<TreeLine> lines,
        @Child(key = "customer.id") Optional<TreeBuyer> customer,
        @Child(through = "customer") Optional<TreeBuyer> buyer) {}
