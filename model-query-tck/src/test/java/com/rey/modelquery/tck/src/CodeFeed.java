package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderStatus;

/** The outer model over {@link CodeSource}: the converted status read back as the enum it is stored as. */
@QueryModel(root = CodeSource.class)
public record CodeFeed(@PrimaryKey Long id, OrderStatus status) {}
