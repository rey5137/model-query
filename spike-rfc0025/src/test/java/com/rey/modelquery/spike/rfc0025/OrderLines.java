package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;
import java.util.Optional;

/**
 * Level 2: an order and its lines; also a to-one child by key ({@code customer}) and a to-one child {@code through}
 * an association ({@code buyer}), the one shape {@code verifyMissing} key groups take.
 */
@QueryModel(root = Entities.SpOrder.class)
public record OrderLines(@PrimaryKey Long id, String status,
        @Child(foreignKey = "order.id") List<LineRow> lines,
        @Child(key = "customer.id") Optional<CustomerRow> customer,
        @Child(through = "customer") Optional<CustomerRow> buyer) {}
