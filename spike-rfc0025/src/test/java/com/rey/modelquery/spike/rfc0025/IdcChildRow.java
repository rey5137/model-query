package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A child of {@link IdcParentRow}. */
@QueryModel(root = Entities.IdcChild.class)
public record IdcChildRow(@PrimaryKey Long id) {}
