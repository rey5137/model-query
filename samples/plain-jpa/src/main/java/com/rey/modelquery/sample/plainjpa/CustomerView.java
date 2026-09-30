package com.rey.modelquery.sample.plainjpa;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A record model, nested in {@link OrderView}. */
@QueryModel(root = CustomerEntity.class)
public record CustomerView(@PrimaryKey Long id, String name, String country) {}
