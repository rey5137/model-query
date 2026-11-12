package com.rey.modelquery.tck.portability.child;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** Level 3: an order line. */
@QueryModel(root = ChildEntities.TreeLineEntity.class)
public record TreeLine(@PrimaryKey Long id, String product) {}
