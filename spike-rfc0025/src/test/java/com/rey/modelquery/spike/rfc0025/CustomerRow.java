package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A customer alone: the to-one children of {@link OrderLines}. */
@QueryModel(root = Entities.SpCustomer.class)
public record CustomerRow(@PrimaryKey Long id, String name) {}
