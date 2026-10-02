package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;

/** A customer as a to-one child. */
@QueryModel(root = CustomerEntity.class)
public record Buyer(@PrimaryKey Long id, String name) {}
