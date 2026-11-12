package com.rey.modelquery.tck.portability.child;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;

/** A plain source over orders. */
@SourceModel(root = ChildEntities.TreeOrderEntity.class)
public record TreeOrderSource(@PrimaryKey Long id, String status) {}
