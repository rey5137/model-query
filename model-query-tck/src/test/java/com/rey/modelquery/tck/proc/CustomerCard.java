package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;

/** The read side of {@link CustomerContact}. */
@QueryModel(root = CustomerEntity.class)
public record CustomerCard(@PrimaryKey Long id, String name, String country) implements CustomerContact {}
