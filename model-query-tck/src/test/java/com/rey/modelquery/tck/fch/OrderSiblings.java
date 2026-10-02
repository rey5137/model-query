package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.List;

/** An order and every order of its customer, itself included: a to-one then a collection, back to its own entity. */
@QueryModel(root = OrderEntity.class)
public record OrderSiblings(@PrimaryKey Long id, @Child(through = "customer.orders") List<OrderRef> siblings) {}
