package com.rey.modelquery.processor.fixture;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A query model that is already compiled: on the classpath of a test compilation, never one of its sources. */
@QueryModel(root = CustomerEntity.class)
public record CustomerSummary(@PrimaryKey Long id, String name) {}
