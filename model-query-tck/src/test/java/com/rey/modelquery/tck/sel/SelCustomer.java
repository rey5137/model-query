package com.rey.modelquery.tck.sel;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Selected;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.tck.col.CustomerEntity;

/** A record model with a {@code @Selected} set, nested in {@link SelOrder} (D-120). */
@QueryModel(root = CustomerEntity.class)
public record SelCustomer(
        @PrimaryKey Long id, String name, String country, @Selected SelectSet<SelCustomer> selected) {}
