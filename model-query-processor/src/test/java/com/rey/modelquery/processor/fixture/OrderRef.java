package com.rey.modelquery.processor.fixture;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A child model that is already compiled, as {@link CustomerSummary} is a nested one. */
@QueryModel(root = OrderEntity.class)
public record OrderRef(@PrimaryKey Long id, String status) {}
