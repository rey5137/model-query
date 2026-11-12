package com.rey.modelquery.tck.portability.child;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A child row of every key type. */
@QueryModel(root = ChildEntities.KeyChildEntity.class)
public record KeyChildRow(@PrimaryKey Long id, byte[] bytesKey) {}
