package com.rey.modelquery.tck.portability.child;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A customer alone: the to-one children of {@link TreeOrder}. */
@QueryModel(root = ChildEntities.TreeCustomerEntity.class)
public record TreeBuyer(@PrimaryKey Long id, String name) {}
