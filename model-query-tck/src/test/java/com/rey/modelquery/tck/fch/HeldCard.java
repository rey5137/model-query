package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.TaggedCardEntity;

@QueryModel(root = TaggedCardEntity.class)
public record HeldCard(@PrimaryKey Long id, String name) {}
