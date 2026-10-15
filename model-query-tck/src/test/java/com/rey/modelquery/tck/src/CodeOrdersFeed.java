package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderStatus;
import java.util.List;

/**
 * The outer model over {@link CodeSource} with a {@code @Child} keyed by the converted source field {@code status}:
 * the parent value the child load binds must be the enum the converted attribute is compared with (R-SRC-10).
 */
@QueryModel(root = CodeSource.class)
public record CodeOrdersFeed(@PrimaryKey Long id, OrderStatus status,
        @Child(key = "status", foreignKey = "statusCode") List<StatusOrder> orders) {}
