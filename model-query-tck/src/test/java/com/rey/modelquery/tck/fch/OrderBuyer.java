package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/**
 * An order and to-one children by key: its buyer through a to-one path, its referrer through a nullable plain column,
 * and an order of the same buyer, of which there are several.
 */
@QueryModel(root = OrderEntity.class)
public record OrderBuyer(@PrimaryKey Long id,
        @Child(key = "customer.id") Optional<Buyer> buyer,
        @Child(key = "referrerId") Optional<Buyer> referrer,
        @Child(key = "customer.id", foreignKey = "customer.id") Optional<OrderRef> sameBuyerOrder) {}
