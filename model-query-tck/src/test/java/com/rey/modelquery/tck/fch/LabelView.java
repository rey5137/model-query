package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.LabelEntity;

/** A label, a child of several orders. */
@QueryModel(root = LabelEntity.class)
public record LabelView(@PrimaryKey Long id, String name) {}
