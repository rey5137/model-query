package com.rey.modelquery.tck.col;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A row of the {@code @Subselect} customer root (TCK AC-COL-29). */
@QueryModel(root = SubselectCustomerEntity.class)
public record SubselectCustomerRow(@PrimaryKey Long id, String name, String country) {}
