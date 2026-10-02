package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.PatronEntity;

/** A customer that referred another's order. */
@QueryModel(root = PatronEntity.class)
public record Referrer(@PrimaryKey Long id, String name) {}
