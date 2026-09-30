package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;

/** A record model, nested in the class {@link OrderView}. */
@QueryModel(root = CustomerEntity.class)
public record CustomerView(@PrimaryKey Long id, String name, String country) {}
